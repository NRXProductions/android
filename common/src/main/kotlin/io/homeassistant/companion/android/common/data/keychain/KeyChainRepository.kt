package io.homeassistant.companion.android.common.data.keychain

/**
 * Client certificates the user picked from the Android system KeyChain. A selection is made per
 * [ClientCertEndpoint], so each server gets its own certificate. The selected aliases are persisted
 * and restored across app starts; the certificates and private keys never leave the KeyChain.
 */
interface KeyChainRepository {

    /**
     * Returns the live view of the certificates, loading the persisted selections when they are
     * not loaded yet.
     */
    suspend fun getClientCertProvider(): ClientCertProvider

    /** Returns the KeyChain alias selected for [endpoint], or `null` when there is none. */
    suspend fun getAlias(endpoint: ClientCertEndpoint): String?

    /**
     * Persists [alias] as the user's selection for [endpoint] and loads its certificate, replacing
     * the one previously selected for this endpoint. Other endpoints are left untouched.
     *
     * @return the loaded certificate, or `null` when it could not be retrieved from the KeyChain.
     */
    suspend fun select(endpoint: ClientCertEndpoint, alias: String): ClientCertificate?

    /** Forgets the persisted selection of [endpoint] and stops presenting a certificate to it. */
    suspend fun clear(endpoint: ClientCertEndpoint)
}
