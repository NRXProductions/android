package io.homeassistant.companion.android.common.data.keychain

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * A complete mTLS client identity.
 */
class ClientCertificate(val privateKey: PrivateKey, val chain: Array<X509Certificate>)

/**
 * A TLS endpoint a client certificate is bound to.
 *
 * A client certificate is requested by a server during the TLS handshake, which only knows the
 * host and port being connected to. Binding the user's selection to that pair keeps the
 * certificate of one server from being presented to another one.
 *
 * The [host] is normalized (lowercase, IPv6 literal without brackets) so the same endpoint compares
 * equal whatever component reports it (WebView, OkHttp, a stored URL).
 */
class ClientCertEndpoint(host: String, val port: Int) {
    val host: String = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)

    override fun equals(other: Any?): Boolean = other is ClientCertEndpoint && host == other.host && port == other.port

    override fun hashCode(): Int = host.hashCode() * HASH_MULTIPLIER + port

    /** Returns the `host:port` form, which [parse] reads back. */
    override fun toString(): String = "$host$SEPARATOR$port"

    companion object {
        private const val SEPARATOR = ':'
        private const val HASH_MULTIPLIER = 31

        /**
         * Parses the `host:port` form produced by [toString].
         *
         * @return the endpoint, or `null` when [value] is not in that form.
         */
        fun parse(value: String): ClientCertEndpoint? {
            // An IPv6 host contains the separator, the port is always what follows the last one.
            val host = value.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
            val port = value.substringAfterLast(SEPARATOR, missingDelimiterValue = "").toIntOrNull()
            return if (host.isNotEmpty() && port != null) ClientCertEndpoint(host, port) else null
        }
    }
}

/**
 * Live read-only view of the loaded client certificates, reflecting a certificate selected,
 * stored, or cleared after this view was obtained.
 */
interface ClientCertProvider {

    /** Whether at least one client certificate is loaded, whatever the endpoint it is meant for. */
    val hasCertificate: Boolean

    /**
     * Returns the certificate to present to [endpoint], or `null` when none must be presented to it.
     */
    fun certificateFor(endpoint: ClientCertEndpoint): ClientCertificate?
}
