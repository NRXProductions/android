package io.homeassistant.companion.android.common.data.keychain

import android.content.Context
import android.security.KeyChain
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerDao
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KeyChainRepositoryImplTest {

    private val prefsRepository = mockk<PrefsRepository>(relaxUnitFun = true)
    private val serverDao = mockk<ServerDao>()
    private val context = mockk<Context>()
    private val repository = KeyChainRepositoryImpl(context, prefsRepository, serverDao)

    private val serverA = ClientCertEndpoint("a.example.com", 443)
    private val serverB = ClientCertEndpoint("b.example.com", 443)

    @BeforeEach
    fun setUp() {
        mockkStatic(KeyChain::class)
        coEvery { prefsRepository.getKeyAlias() } returns null
        coEvery { prefsRepository.getKeyAliases() } returns emptyMap()
        coEvery { serverDao.getAll() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(KeyChain::class)
    }

    private fun givenKeyChainCert(alias: String): Pair<PrivateKey, Array<X509Certificate>> {
        val key = mockk<PrivateKey>()
        val chain = arrayOf(mockk<X509Certificate>())
        every { KeyChain.getCertificateChain(context, alias) } returns chain
        every { KeyChain.getPrivateKey(context, alias) } returns key
        return key to chain
    }

    private fun givenPersistedAliases(vararg aliases: Pair<ClientCertEndpoint, String>) {
        coEvery { prefsRepository.getKeyAliases() } returns aliases.associate { (endpoint, alias) ->
            endpoint.toString() to alias
        }
    }

    private fun server(id: Int, externalUrl: String, internalUrl: String? = null) = Server(
        id = id,
        _name = "Server $id",
        connection = ServerConnectionInfo(externalUrl = externalUrl, internalUrl = internalUrl),
        session = ServerSessionInfo(),
        user = ServerUserInfo(),
    )

    @Test
    fun `Given a persisted alias when loading then the certificate is available for its endpoint`() = runTest {
        givenPersistedAliases(serverA to "alias")
        val (key, chain) = givenKeyChainCert("alias")

        val provider = repository.getClientCertProvider()

        assertEquals(key, provider.certificateFor(serverA)?.privateKey)
        assertEquals(chain, provider.certificateFor(serverA)?.chain)
        assertTrue(provider.hasCertificate)
    }

    @Test
    fun `Given a persisted alias when loading then the certificate is not available for another endpoint`() = runTest {
        givenPersistedAliases(serverA to "alias")
        givenKeyChainCert("alias")

        val provider = repository.getClientCertProvider()

        assertNull(provider.certificateFor(serverB))
        assertNull(provider.certificateFor(ClientCertEndpoint(serverA.host, 8443)))
    }

    @Test
    fun `Given no persisted alias when loading then the provider has no certificate`() = runTest {
        val provider = repository.getClientCertProvider()

        assertNull(provider.certificateFor(serverA))
        assertFalse(provider.hasCertificate)
    }

    @Test
    fun `Given a certificate selected for a server when selecting one for a second server then each server keeps its own`() = runTest {
        val (keyA, _) = givenKeyChainCert("alias-a")
        val (keyB, _) = givenKeyChainCert("alias-b")
        val provider = repository.getClientCertProvider()

        repository.select(serverA, "alias-a")
        val selected = repository.select(serverB, "alias-b")

        assertEquals(keyB, selected?.privateKey)
        assertEquals(keyA, provider.certificateFor(serverA)?.privateKey)
        assertEquals(keyB, provider.certificateFor(serverB)?.privateKey)
        assertEquals("alias-a", repository.getAlias(serverA))
        assertEquals("alias-b", repository.getAlias(serverB))
        coVerify {
            prefsRepository.saveKeyAliases(mapOf(serverA.toString() to "alias-a", serverB.toString() to "alias-b"))
        }
    }

    @Test
    fun `Given a loaded certificate when selecting another alias for its endpoint then the new certificate replaces the old one`() = runTest {
        givenPersistedAliases(serverA to "old")
        givenKeyChainCert("old")
        val provider = repository.getClientCertProvider()
        val (newKey, _) = givenKeyChainCert("new")

        val selected = repository.select(serverA, "new")

        assertEquals(newKey, selected?.privateKey)
        assertEquals(newKey, provider.certificateFor(serverA)?.privateKey)
        coVerify { prefsRepository.saveKeyAliases(mapOf(serverA.toString() to "new")) }
    }

    @Test
    fun `Given a loaded certificate when selecting the same alias again then the certificate is read again from the KeyChain`() = runTest {
        givenPersistedAliases(serverA to "alias")
        givenKeyChainCert("alias")
        val provider = repository.getClientCertProvider()
        val (renewedKey, _) = givenKeyChainCert("alias")

        repository.select(serverA, "alias")

        assertEquals(renewedKey, provider.certificateFor(serverA)?.privateKey)
    }

    @Test
    fun `Given two endpoints sharing an alias when loading then the KeyChain is read once`() = runTest {
        givenPersistedAliases(serverA to "alias", serverB to "alias")
        val (key, _) = givenKeyChainCert("alias")

        val provider = repository.getClientCertProvider()

        assertEquals(key, provider.certificateFor(serverA)?.privateKey)
        assertEquals(key, provider.certificateFor(serverB)?.privateKey)
        verify(exactly = 1) { KeyChain.getPrivateKey(context, "alias") }
    }

    @Test
    fun `Given certificates for two servers when clearing one then the other is kept`() = runTest {
        givenPersistedAliases(serverA to "alias-a", serverB to "alias-b")
        givenKeyChainCert("alias-a")
        val (keyB, _) = givenKeyChainCert("alias-b")
        val provider = repository.getClientCertProvider()

        repository.clear(serverA)

        assertNull(provider.certificateFor(serverA))
        assertNull(repository.getAlias(serverA))
        assertEquals(keyB, provider.certificateFor(serverB)?.privateKey)
        coVerify { prefsRepository.saveKeyAliases(mapOf(serverB.toString() to "alias-b")) }
    }

    @Test
    fun `Given an endpoint without selection when clearing it then nothing is persisted`() = runTest {
        givenPersistedAliases(serverA to "alias-a")
        givenKeyChainCert("alias-a")

        repository.clear(serverB)

        coVerify(exactly = 0) { prefsRepository.saveKeyAliases(any()) }
    }

    @Test
    fun `Given the KeyChain throws when loading then the provider has no certificate`() = runTest {
        givenPersistedAliases(serverA to "alias")
        every { KeyChain.getCertificateChain(context, "alias") } throws IllegalStateException("KeyChain unavailable")
        every { KeyChain.getPrivateKey(context, "alias") } throws IllegalStateException("KeyChain unavailable")

        val provider = repository.getClientCertProvider()

        assertNull(provider.certificateFor(serverA))
    }

    @Test
    fun `Given a certificate which could not be loaded when getting the provider again then loading is retried`() = runTest {
        givenPersistedAliases(serverA to "alias")
        every { KeyChain.getCertificateChain(context, "alias") } throws IllegalStateException("KeyChain unavailable")
        every { KeyChain.getPrivateKey(context, "alias") } throws IllegalStateException("KeyChain unavailable")
        repository.getClientCertProvider()
        val (key, _) = givenKeyChainCert("alias")

        val provider = repository.getClientCertProvider()

        assertEquals(key, provider.certificateFor(serverA)?.privateKey)
    }

    @Nested
    inner class LegacyAliasMigration {

        @Test
        fun `Given a legacy alias when loading then it is applied to the HTTPS endpoints of the existing servers`() = runTest {
            coEvery { prefsRepository.getKeyAlias() } returns "legacy"
            coEvery { serverDao.getAll() } returns listOf(
                server(id = 1, externalUrl = "https://a.example.com", internalUrl = "https://a.local:8123"),
                server(id = 2, externalUrl = "https://b.example.com", internalUrl = "http://192.168.1.2:8123"),
            )
            val (key, _) = givenKeyChainCert("legacy")

            val provider = repository.getClientCertProvider()

            assertEquals(key, provider.certificateFor(serverA)?.privateKey)
            assertEquals(key, provider.certificateFor(serverB)?.privateKey)
            assertEquals(key, provider.certificateFor(ClientCertEndpoint("a.local", 8123))?.privateKey)
            assertNull(provider.certificateFor(ClientCertEndpoint("192.168.1.2", 8123)))
            assertNull(provider.certificateFor(ClientCertEndpoint("c.example.com", 443)))
        }

        @Test
        fun `Given a legacy alias when loading then the migrated aliases are persisted before the legacy one is dropped`() = runTest {
            coEvery { prefsRepository.getKeyAlias() } returns "legacy"
            coEvery { serverDao.getAll() } returns listOf(server(id = 1, externalUrl = "https://a.example.com"))
            givenKeyChainCert("legacy")

            repository.getClientCertProvider()

            coVerifyOrder {
                prefsRepository.saveKeyAliases(mapOf(serverA.toString() to "legacy"))
                prefsRepository.saveKeyAlias("")
            }
        }

        @Test
        fun `Given a legacy alias and an endpoint with its own alias when loading then the endpoint alias is kept`() = runTest {
            coEvery { prefsRepository.getKeyAlias() } returns "legacy"
            givenPersistedAliases(serverA to "alias-a")
            coEvery { serverDao.getAll() } returns listOf(
                server(id = 1, externalUrl = "https://a.example.com"),
                server(id = 2, externalUrl = "https://b.example.com"),
            )
            val (keyA, _) = givenKeyChainCert("alias-a")
            val (legacyKey, _) = givenKeyChainCert("legacy")

            val provider = repository.getClientCertProvider()

            assertEquals(keyA, provider.certificateFor(serverA)?.privateKey)
            assertEquals(legacyKey, provider.certificateFor(serverB)?.privateKey)
        }

        @Test
        fun `Given no legacy alias when loading then nothing is migrated`() = runTest {
            coEvery { prefsRepository.getKeyAlias() } returns ""
            givenPersistedAliases(serverA to "alias-a")
            givenKeyChainCert("alias-a")

            repository.getClientCertProvider()

            coVerify(exactly = 0) { serverDao.getAll() }
            coVerify(exactly = 0) { prefsRepository.saveKeyAliases(any()) }
            coVerify(exactly = 0) { prefsRepository.saveKeyAlias(any()) }
        }
    }
}
