package io.homeassistant.companion.android.util

import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Manages the TLS client certificate (mTLS) of a server.
 *
 * A certificate is bound to an endpoint, this handler applies the user's choice to every endpoint
 * of the server where a certificate can be requested (see
 * [io.homeassistant.companion.android.database.server.ServerConnectionInfo.clientCertEndpoints]).
 */
class ServerClientCertificateHandler @VisibleForTesting constructor(
    private val serverManager: ServerManager,
    private val keyChainRepository: KeyChainRepository,
    private val okHttpClientProvider: SuspendProvider<OkHttpClient>,
    private val backgroundDispatcher: CoroutineDispatcher,
) {

    @Inject
    constructor(
        serverManager: ServerManager,
        keyChainRepository: KeyChainRepository,
        okHttpClientProvider: SuspendProvider<OkHttpClient>,
    ) : this(serverManager, keyChainRepository, okHttpClientProvider, Dispatchers.IO)

    /**
     * Returns the endpoints of the server where a client certificate can be requested. Empty when
     * the server is unknown or has no HTTPS URL.
     */
    suspend fun getEndpoints(serverId: Int): List<ClientCertEndpoint> =
        serverManager.getServer(serverId)?.connection?.clientCertEndpoints.orEmpty()

    /**
     * Returns the KeyChain aliases of the certificates selected for the server. Usually a single
     * one, unless the user picked different certificates for its internal and external URLs.
     */
    suspend fun getAliases(serverId: Int): List<String> =
        getEndpoints(serverId).mapNotNull { keyChainRepository.getAlias(it) }.distinct()

    /**
     * Selects the certificate behind the KeyChain [alias] for every endpoint of the server,
     * replacing the previous selection.
     *
     * @return `false` when the certificate could not be retrieved from the KeyChain.
     */
    suspend fun select(serverId: Int, alias: String): Boolean {
        val certificates = getEndpoints(serverId).map { keyChainRepository.select(it, alias) }
        dropEstablishedSelections()
        return certificates.isNotEmpty() && certificates.all { it != null }
    }

    /**
     * Forgets the certificate selected for every endpoint of the server. The user is asked again
     * the next time one of them requests a certificate.
     */
    suspend fun clear(serverId: Int) {
        getEndpoints(serverId).forEach { keyChainRepository.clear(it) }
        dropEstablishedSelections()
    }

    /**
     * Makes the change effective without restarting the app. The WebView remembers for the whole
     * process which certificate was given to a host and never asks again, and both the WebView and
     * OkHttp keep reusing the connections negotiated with the previous certificate.
     */
    private suspend fun dropEstablishedSelections() {
        withContext(Dispatchers.Main) {
            try {
                WebView.clearClientCertPreferences(null)
            } catch (e: RuntimeException) {
                // No usable WebView on the device, so there is nothing to clear either
                Timber.w(e, "Unable to clear the WebView client certificate preferences")
            }
        }
        withContext(backgroundDispatcher) {
            okHttpClientProvider().connectionPool.evictAll()
        }
    }
}
