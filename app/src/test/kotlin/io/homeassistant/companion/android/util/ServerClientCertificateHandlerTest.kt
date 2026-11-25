package io.homeassistant.companion.android.util

import android.webkit.WebView
import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint
import io.homeassistant.companion.android.common.data.keychain.ClientCertificate
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherJUnit5Extension::class)
class ServerClientCertificateHandlerTest {

    private val external = ClientCertEndpoint("external.example.com", 443)
    private val internal = ClientCertEndpoint("internal.example.com", 8123)

    private val serverManager: ServerManager = mockk()
    private val keyChainRepository: KeyChainRepository = mockk(relaxUnitFun = true)
    private val connectionPool: ConnectionPool = mockk(relaxUnitFun = true)
    private val okHttpClient: OkHttpClient = mockk { every { connectionPool } returns this@ServerClientCertificateHandlerTest.connectionPool }

    private val handler = ServerClientCertificateHandler(
        serverManager = serverManager,
        keyChainRepository = keyChainRepository,
        okHttpClientProvider = { okHttpClient },
        backgroundDispatcher = UnconfinedTestDispatcher(),
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(WebView::class)
        every { WebView.clearClientCertPreferences(any()) } just runs
        coEvery { serverManager.getServer(SERVER_ID) } returns server(
            externalUrl = "https://external.example.com",
            internalUrl = "https://internal.example.com:8123",
        )
        coEvery { keyChainRepository.getAlias(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(WebView::class)
    }

    private fun server(externalUrl: String, internalUrl: String? = null) = Server(
        id = SERVER_ID,
        _name = "Server",
        connection = ServerConnectionInfo(externalUrl = externalUrl, internalUrl = internalUrl),
        session = ServerSessionInfo(),
        user = ServerUserInfo(),
    )

    @Test
    fun `Given a server with HTTPS URLs when getting its endpoints then they are returned`() = runTest {
        assertEquals(listOf(external, internal), handler.getEndpoints(SERVER_ID))
    }

    @Test
    fun `Given an unknown server when getting its endpoints then there is none`() = runTest {
        coEvery { serverManager.getServer(OTHER_SERVER_ID) } returns null

        assertEquals(emptyList<ClientCertEndpoint>(), handler.getEndpoints(OTHER_SERVER_ID))
        assertEquals(emptyList<String>(), handler.getAliases(OTHER_SERVER_ID))
    }

    @Test
    fun `Given the same certificate on every endpoint when getting the aliases then it is returned once`() = runTest {
        coEvery { keyChainRepository.getAlias(external) } returns "alias"
        coEvery { keyChainRepository.getAlias(internal) } returns "alias"

        assertEquals(listOf("alias"), handler.getAliases(SERVER_ID))
    }

    @Test
    fun `Given a certificate on one endpoint only when getting the aliases then it is returned`() = runTest {
        coEvery { keyChainRepository.getAlias(internal) } returns "alias"

        assertEquals(listOf("alias"), handler.getAliases(SERVER_ID))
    }

    @Test
    fun `Given a server when selecting a certificate then it is selected for every endpoint of this server`() = runTest {
        coEvery { keyChainRepository.select(any(), "alias") } returns ClientCertificate(mockk(), emptyArray())

        val isSelected = handler.select(SERVER_ID, "alias")

        assertTrue(isSelected)
        coVerify(exactly = 1) { keyChainRepository.select(external, "alias") }
        coVerify(exactly = 1) { keyChainRepository.select(internal, "alias") }
        coVerify(exactly = 2) { keyChainRepository.select(any(), any()) }
    }

    @Test
    fun `Given a certificate which cannot be loaded when selecting it then false is returned`() = runTest {
        coEvery { keyChainRepository.select(any(), "alias") } returns null

        assertFalse(handler.select(SERVER_ID, "alias"))
    }

    @Test
    fun `Given a server without HTTPS URL when selecting a certificate then false is returned`() = runTest {
        coEvery { serverManager.getServer(SERVER_ID) } returns server(externalUrl = "http://homeassistant.local:8123")

        assertFalse(handler.select(SERVER_ID, "alias"))
        coVerify(exactly = 0) { keyChainRepository.select(any(), any()) }
    }

    @Test
    fun `Given a server when clearing its certificate then every endpoint of this server is cleared`() = runTest {
        handler.clear(SERVER_ID)

        coVerify(exactly = 1) { keyChainRepository.clear(external) }
        coVerify(exactly = 1) { keyChainRepository.clear(internal) }
        coVerify(exactly = 2) { keyChainRepository.clear(any()) }
    }

    @Test
    fun `Given a server when changing its certificate then the selections remembered by the WebView and OkHttp are dropped`() = runTest {
        coEvery { keyChainRepository.select(any(), "alias") } returns ClientCertificate(mockk(), emptyArray())

        handler.select(SERVER_ID, "alias")
        handler.clear(SERVER_ID)

        verify(exactly = 2) { WebView.clearClientCertPreferences(null) }
        verify(exactly = 2) { connectionPool.evictAll() }
    }

    @Test
    fun `Given a device without usable WebView when clearing the certificate then it does not fail`() = runTest {
        every { WebView.clearClientCertPreferences(any()) } throws IllegalStateException("No WebView installed")

        handler.clear(SERVER_ID)

        coVerify(exactly = 1) { keyChainRepository.clear(external) }
        verify(exactly = 1) { connectionPool.evictAll() }
    }

    private companion object {
        const val SERVER_ID = 1
        const val OTHER_SERVER_ID = 2
    }
}
