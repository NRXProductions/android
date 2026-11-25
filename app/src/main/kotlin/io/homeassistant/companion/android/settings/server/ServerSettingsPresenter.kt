package io.homeassistant.companion.android.settings.server

import androidx.preference.PreferenceDataStore
import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint

interface ServerSettingsPresenter {
    fun init(view: ServerSettingsView, serverId: Int)
    fun getPreferenceDataStore(): PreferenceDataStore
    suspend fun deleteServer()
    fun onFinish()

    suspend fun hasMultipleServers(): Boolean
    fun updateServerName()
    fun updateUrlStatus()
    fun hasWifi(): Boolean
    fun clearSsids()

    fun setAppActive(active: Boolean)

    suspend fun serverURL(): String?

    suspend fun getAllowInsecureConnection(): Boolean?

    /**
     * Returns the endpoint to show the user when selecting the TLS client certificate of the
     * server, or `null` when the server has no HTTPS URL and so cannot use one.
     */
    suspend fun getClientCertificateEndpoint(): ClientCertEndpoint?

    /** Returns the KeyChain aliases of the TLS client certificates selected for the server. */
    suspend fun getClientCertificateAliases(): List<String>

    /**
     * Selects the TLS client certificate behind the KeyChain [alias] for the server.
     *
     * @return `false` when the certificate could not be retrieved from the KeyChain.
     */
    suspend fun selectClientCertificate(alias: String): Boolean

    /** Stops presenting a TLS client certificate to the server. */
    suspend fun removeClientCertificate()
}
