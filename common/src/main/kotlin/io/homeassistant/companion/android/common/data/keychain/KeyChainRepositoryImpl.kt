package io.homeassistant.companion.android.common.data.keychain

import android.content.Context
import android.security.KeyChain
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.database.server.ServerDao
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

internal class KeyChainRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepository: PrefsRepository,
    private val serverDao: ServerDao,
) : KeyChainRepository {

    private val mutex = Mutex()

    /** Alias selected for each endpoint, `null` until read from the storage. Only accessed while holding [mutex]. */
    private var aliases: Map<ClientCertEndpoint, String>? = null

    /** Certificates already retrieved from the [KeyChain], by alias. Only accessed while holding [mutex]. */
    private var certificatesByAlias: Map<String, ClientCertificate> = emptyMap()

    /** Written under [mutex]; read lock-free through [provider] from non-suspending contexts like TLS handshakes. */
    @Volatile
    private var certificates: Map<ClientCertEndpoint, ClientCertificate> = emptyMap()

    /** Whether every persisted selection has its certificate in [certificates]. Written under [mutex]. */
    @Volatile
    private var isLoaded = false

    private val provider = object : ClientCertProvider {
        override val hasCertificate: Boolean
            get() = certificates.isNotEmpty()

        override fun certificateFor(endpoint: ClientCertEndpoint): ClientCertificate? = certificates[endpoint]
    }

    override suspend fun getClientCertProvider(): ClientCertProvider {
        if (!isLoaded) {
            mutex.withLock {
                if (!isLoaded) {
                    loadCertificates(loadAliases())
                }
            }
        }
        return provider
    }

    override suspend fun getAlias(endpoint: ClientCertEndpoint): String? = mutex.withLock {
        loadAliases()[endpoint]
    }

    override suspend fun select(endpoint: ClientCertEndpoint, alias: String): ClientCertificate? = mutex.withLock {
        val updatedAliases = loadAliases() + (endpoint to alias)
        saveAliases(updatedAliases)
        // The certificate behind an alias can have been replaced since it was retrieved (e.g. renewed
        // after it expired), an explicit selection always reads it again from the KeyChain.
        certificatesByAlias = certificatesByAlias - alias
        loadCertificates(updatedAliases)
        certificates[endpoint]
    }

    override suspend fun clear(endpoint: ClientCertEndpoint) {
        mutex.withLock {
            val currentAliases = loadAliases()
            if (endpoint !in currentAliases) return
            val updatedAliases = currentAliases - endpoint
            saveAliases(updatedAliases)
            loadCertificates(updatedAliases)
        }
    }

    /** Must be called while holding [mutex]. */
    private suspend fun loadAliases(): Map<ClientCertEndpoint, String> {
        aliases?.let { return it }

        val storedAliases = prefsRepository.getKeyAliases().mapNotNull { (endpoint, alias) ->
            ClientCertEndpoint.parse(endpoint)?.let { it to alias }
        }.toMap()
        return migrateLegacyAlias(storedAliases).also { aliases = it }
    }

    /**
     * Converts the single alias older versions of the app used for every connection into a selection
     * for each endpoint of the servers known at that time, so they keep working as before. A server
     * added afterward gets its own selection.
     *
     * Must be called while holding [mutex].
     */
    private suspend fun migrateLegacyAlias(
        storedAliases: Map<ClientCertEndpoint, String>,
    ): Map<ClientCertEndpoint, String> {
        val legacyAlias = prefsRepository.getKeyAlias()
        if (legacyAlias.isNullOrEmpty()) return storedAliases

        val legacyAliases = serverDao.getAll()
            .flatMap { it.connection.clientCertEndpoints }
            .associateWith { legacyAlias }
        val migratedAliases = legacyAliases + storedAliases
        // The legacy alias is only dropped once the migrated selections are persisted
        prefsRepository.saveKeyAliases(migratedAliases.mapKeys { it.key.toString() })
        prefsRepository.saveKeyAlias("")
        Timber.i("Migrated the client certificate selection to ${legacyAliases.size} server endpoint(s)")
        return migratedAliases
    }

    /** Must be called while holding [mutex]. */
    private suspend fun saveAliases(updatedAliases: Map<ClientCertEndpoint, String>) {
        prefsRepository.saveKeyAliases(updatedAliases.mapKeys { it.key.toString() })
        aliases = updatedAliases
    }

    /**
     * Publishes the certificates of [selectedAliases], retrieving from the [KeyChain] the ones not
     * retrieved yet. A certificate that cannot be retrieved is left out and retried on the next load.
     *
     * Must be called while holding [mutex]; runs the blocking [KeyChain] calls on [Dispatchers.IO].
     */
    private suspend fun loadCertificates(selectedAliases: Map<ClientCertEndpoint, String>) {
        certificatesByAlias = selectedAliases.values.distinct().mapNotNull { alias ->
            (certificatesByAlias[alias] ?: loadCertificate(alias))?.let { alias to it }
        }.toMap()
        certificates = selectedAliases.mapNotNull { (endpoint, alias) ->
            certificatesByAlias[alias]?.let { endpoint to it }
        }.toMap()
        isLoaded = certificates.size == selectedAliases.size
    }

    private suspend fun loadCertificate(alias: String): ClientCertificate? {
        if (alias.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            val chain = getOrLogFailure("Issue getting certificate chain") {
                KeyChain.getCertificateChain(context, alias)
            }
            val key = getOrLogFailure("Issue getting private key") {
                KeyChain.getPrivateKey(context, alias)
            }
            if (key != null && !chain.isNullOrEmpty()) {
                ClientCertificate(key, chain)
            } else {
                null
            }
        }
    }

    /**
     * [KeyChain] can throw [AssertionError] on some devices, so it is handled like an exception.
     */
    private fun <T> getOrLogFailure(message: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        when (t) {
            is AssertionError,
            is Exception,
            -> Timber.e(t, message)
            else -> throw t
        }
        null
    }
}
