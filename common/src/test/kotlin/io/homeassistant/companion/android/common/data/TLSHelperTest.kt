package io.homeassistant.companion.android.common.data

import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint
import io.homeassistant.companion.android.common.data.keychain.ClientCertProvider
import io.homeassistant.companion.android.common.data.keychain.ClientCertificate
import io.homeassistant.companion.android.common.data.keychain.ClientCertificateManager
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.common.data.keychain.KeyStoreRepository
import io.mockk.every
import io.mockk.mockk
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull

class TLSHelperTest {

    @Test
    fun `Given a client cert selected on a previous run when creating the mTLS key manager then the cert is loaded before being returned`() = runTest {
        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        val keyChain = FakeKeyChainRepository(toLoad = mapOf(SERVER_A to certificate))
        val tlsHelper = tlsHelperOf(keyChain = keyChain)

        val keyManager = tlsHelper.getMTLSKeyManagerForOKHTTP()

        val alias = keyManager.chooseClientAlias(null, null, socketTo(SERVER_A))
        assertEquals(certificate.privateKey, keyManager.getPrivateKey(alias))
        assertArrayEquals(certificate.chain, keyManager.getCertificateChain(alias))
    }

    @Test
    fun `Given no cert in the keychain when creating the mTLS key manager then it falls back to the keystore`() = runTest {
        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        val keyStore = FakeKeyStoreRepository(toLoad = certificate)
        val tlsHelper = tlsHelperOf(keyStore = keyStore)

        val keyManager = tlsHelper.getMTLSKeyManagerForOKHTTP()

        val alias = keyManager.chooseClientAlias(null, null, socketTo(SERVER_A))
        assertEquals(certificate.privateKey, keyManager.getPrivateKey(alias))
        assertArrayEquals(certificate.chain, keyManager.getCertificateChain(alias))
    }

    @Test
    fun `Given a client cert selected after the key manager creation when getting the key then it is returned`() = runTest {
        val keyChain = FakeKeyChainRepository()
        val tlsHelper = tlsHelperOf(keyChain = keyChain)
        val keyManager = tlsHelper.getMTLSKeyManagerForOKHTTP()
        assertNull(keyManager.chooseClientAlias(null, null, socketTo(SERVER_A)))

        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        keyChain.certificates[SERVER_A] = certificate

        val alias = keyManager.chooseClientAlias(null, null, socketTo(SERVER_A))
        assertEquals(certificate.privateKey, keyManager.getPrivateKey(alias))
        assertArrayEquals(certificate.chain, keyManager.getCertificateChain(alias))
    }

    @Test
    fun `Given client certs selected for two servers when connecting then each server is presented its own cert`() = runTest {
        val certificateA = ClientCertificate(mockk(), arrayOf(USER_CA))
        val certificateB = ClientCertificate(mockk(), arrayOf(SYSTEM_CA))
        val keyChain = FakeKeyChainRepository(toLoad = mapOf(SERVER_A to certificateA, SERVER_B to certificateB))
        val keyManager = tlsHelperOf(keyChain = keyChain).getMTLSKeyManagerForOKHTTP()

        val aliasA = keyManager.chooseClientAlias(null, null, socketTo(SERVER_A))
        val aliasB = keyManager.chooseClientAlias(null, null, socketTo(SERVER_B))

        assertEquals(certificateA.privateKey, keyManager.getPrivateKey(aliasA))
        assertArrayEquals(certificateA.chain, keyManager.getCertificateChain(aliasA))
        assertEquals(certificateB.privateKey, keyManager.getPrivateKey(aliasB))
        assertArrayEquals(certificateB.chain, keyManager.getCertificateChain(aliasB))
    }

    @Test
    fun `Given a client cert selected for a server when connecting to another host then no cert is presented`() = runTest {
        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        val keyChain = FakeKeyChainRepository(toLoad = mapOf(SERVER_A to certificate))
        val keyManager = tlsHelperOf(keyChain = keyChain).getMTLSKeyManagerForOKHTTP()

        assertNull(keyManager.chooseClientAlias(null, null, socketTo(SERVER_B)))
        assertNull(keyManager.chooseClientAlias(null, null, socketTo(ClientCertEndpoint(SERVER_A.host, 8443))))
        assertNull(keyManager.getPrivateKey(null))
        assertNull(keyManager.getCertificateChain(null))
        assertNull(keyManager.getPrivateKey(SERVER_B.toString()))
        assertNull(keyManager.getCertificateChain(SERVER_B.toString()))
    }

    @Test
    fun `Given a socket which is not in a handshake when choosing the client alias then no cert is presented`() = runTest {
        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        val keyChain = FakeKeyChainRepository(toLoad = mapOf(SERVER_A to certificate))
        val keyManager = tlsHelperOf(keyChain = keyChain).getMTLSKeyManagerForOKHTTP()
        val socket = mockk<SSLSocket> { every { handshakeSession } returns null }

        assertNull(keyManager.chooseClientAlias(null, null, socket))
        assertNull(keyManager.chooseClientAlias(null, null, null))
    }

    @Test
    fun `Given a client cert selected for a server when an engine connects to it then the cert is presented`() = runTest {
        val certificate = ClientCertificate(mockk(), arrayOf(USER_CA))
        val keyChain = FakeKeyChainRepository(toLoad = mapOf(SERVER_A to certificate))
        val keyManager = tlsHelperOf(keyChain = keyChain).getMTLSKeyManagerForOKHTTP()

        val alias = keyManager.chooseEngineClientAlias(null, null, engineTo(SERVER_A))

        assertEquals(certificate.privateKey, keyManager.getPrivateKey(alias))
        assertNull(keyManager.chooseEngineClientAlias(null, null, engineTo(SERVER_B)))
    }

    @Test
    fun `Given user and system CAs when filtering then only the user CA is kept`() {
        val store = keyStoreOf(
            "user:1a2b.0" to USER_CA,
            "system:3c4d.0" to SYSTEM_CA,
        )

        val userCaStore = userInstalledCaKeyStore(store)

        assertNotNull(userCaStore)
        assertEquals(1, userCaStore.size())
        assertNotNull(userCaStore.getCertificateAlias(USER_CA))
        assertNull(userCaStore.getCertificateAlias(SYSTEM_CA))
    }

    @Test
    fun `Given several user CAs when filtering then all of them are kept`() {
        val store = keyStoreOf(
            "user:1a2b.0" to USER_CA,
            "user:5e6f.0" to USER_CA,
            "system:3c4d.0" to SYSTEM_CA,
        )

        val userCaStore = userInstalledCaKeyStore(store)

        assertNotNull(userCaStore)
        assertEquals(2, userCaStore.size())
    }

    @Test
    fun `Given only system CAs when filtering then it returns null`() {
        val store = keyStoreOf("system:3c4d.0" to SYSTEM_CA)

        assertNull(userInstalledCaKeyStore(store))
    }

    @Test
    fun `Given an empty store when filtering then it returns null`() {
        assertNull(userInstalledCaKeyStore(keyStoreOf()))
    }

    private fun tlsHelperOf(
        keyChain: KeyChainRepository = FakeKeyChainRepository(),
        keyStore: KeyStoreRepository = FakeKeyStoreRepository(),
    ): TLSHelper = TLSHelper(ClientCertificateManager(keyChain, keyStore))

    private fun socketTo(endpoint: ClientCertEndpoint): SSLSocket = mockk {
        every { handshakeSession } returns mockk {
            every { peerHost } returns endpoint.host
            every { peerPort } returns endpoint.port
        }
    }

    private fun engineTo(endpoint: ClientCertEndpoint): SSLEngine = mockk {
        every { peerHost } returns endpoint.host
        every { peerPort } returns endpoint.port
    }

    private fun keyStoreOf(vararg entries: Pair<String, X509Certificate>): KeyStore {
        return loadKeyStore().apply {
            entries.forEach { (alias, certificate) -> setCertificateEntry(alias, certificate) }
        }
    }

    companion object {
        private val SERVER_A = ClientCertEndpoint("a.example.com", 443)
        private val SERVER_B = ClientCertEndpoint("b.example.com", 443)

        private val USER_CA = parseCertificate(
            """
            -----BEGIN CERTIFICATE-----
            MIIBgjCCASmgAwIBAgIUK3+GgehOO2LIwFaGYCqBPSfr9gAwCgYIKoZIzj0EAwIw
            FzEVMBMGA1UEAwwMVGVzdCBVc2VyIENBMB4XDTI2MDYxNTA5MTYxMloXDTM2MDYx
            MjA5MTYxMlowFzEVMBMGA1UEAwwMVGVzdCBVc2VyIENBMFkwEwYHKoZIzj0CAQYI
            KoZIzj0DAQcDQgAEuWKQFJagdiv0NG8ROZUjviXdxMAYZVIPiBRtPKZ4xEdZ3TSy
            7gFe5R6ZRGD65P/554JCVv99Z5UiUdrn3A0Dt6NTMFEwHQYDVR0OBBYEFNTSQ/2z
            oR4mJW8BfJjWudET1UR+MB8GA1UdIwQYMBaAFNTSQ/2zoR4mJW8BfJjWudET1UR+
            MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgWRDzptriiK+L11Em
            KRPW95Y/AAevho3810p++8sAiK8CIBL/aktDSszIPhvngN/YObx9Rsa1X0GhbCur
            WttX27wB
            -----END CERTIFICATE-----
            """.trimIndent(),
        )

        private val SYSTEM_CA = parseCertificate(
            """
            -----BEGIN CERTIFICATE-----
            MIIBhjCCAS2gAwIBAgIULM9Sv5gifek7RA04cDsow+5DUC8wCgYIKoZIzj0EAwIw
            GTEXMBUGA1UEAwwOVGVzdCBTeXN0ZW0gQ0EwHhcNMjYwNjE1MDkxNjEyWhcNMzYw
            NjEyMDkxNjEyWjAZMRcwFQYDVQQDDA5UZXN0IFN5c3RlbSBDQTBZMBMGByqGSM49
            AgEGCCqGSM49AwEHA0IABIoDRdoHkgePxBTI/FrxNtwmlmw2ASjf0nDsXrGzvaCe
            Hmopa+Kk+XidaLBFM7uxs01pYfMwfsVM8EFuK4OOsxajUzBRMB0GA1UdDgQWBBR5
            9mR1Ojk9OqBRX1pZSuaNsSnkuTAfBgNVHSMEGDAWgBR59mR1Ojk9OqBRX1pZSuaN
            sSnkuTAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIFWTMdMCTVkn
            0sVpgRzAofWK821vZgz9M2c9KOWLYrQNAiAAlkVuqpbn6Z+KXNvkMteeAyr0sTwn
            8x92kn3oJNr6WA==
            -----END CERTIFICATE-----
            """.trimIndent(),
        )

        private fun parseCertificate(pem: String): X509Certificate {
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.byteInputStream()) as X509Certificate
        }
    }
}

/**
 * Fake that only exposes its cert after [getClientCertProvider] was called, mimicking the real
 * repositories where the cert is unavailable until loaded
 * (https://github.com/home-assistant/android/issues/6119).
 */
private class FakeKeyChainRepository(private val toLoad: Map<ClientCertEndpoint, ClientCertificate> = emptyMap()) :
    KeyChainRepository,
    ClientCertProvider {

    val certificates = mutableMapOf<ClientCertEndpoint, ClientCertificate>()

    override val hasCertificate: Boolean
        get() = certificates.isNotEmpty()

    override fun certificateFor(endpoint: ClientCertEndpoint): ClientCertificate? = certificates[endpoint]

    override suspend fun getClientCertProvider(): ClientCertProvider {
        if (certificates.isEmpty()) {
            certificates += toLoad
        }
        return this
    }

    override suspend fun getAlias(endpoint: ClientCertEndpoint): String? = null

    override suspend fun select(endpoint: ClientCertEndpoint, alias: String): ClientCertificate? = certificates[endpoint]

    override suspend fun clear(endpoint: ClientCertEndpoint) {
        certificates -= endpoint
    }
}

/** Same load-gated behavior as [FakeKeyChainRepository] for the keystore contract. */
private class FakeKeyStoreRepository(private val toLoad: ClientCertificate? = null) :
    KeyStoreRepository,
    ClientCertProvider {

    var certificate: ClientCertificate? = null

    override val hasCertificate: Boolean
        get() = certificate != null

    override fun certificateFor(endpoint: ClientCertEndpoint): ClientCertificate? = certificate

    override suspend fun getClientCertProvider(): ClientCertProvider {
        if (certificate == null) {
            certificate = toLoad
        }
        return this
    }

    override suspend fun store(privateKey: PrivateKey, chain: Array<X509Certificate>): ClientCertificate = ClientCertificate(privateKey, chain).also { certificate = it }
}
