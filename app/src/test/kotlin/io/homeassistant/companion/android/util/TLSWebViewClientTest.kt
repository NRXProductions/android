package io.homeassistant.companion.android.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.webkit.ClientCertRequest
import android.webkit.WebView
import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint
import io.homeassistant.companion.android.common.data.keychain.ClientCertProvider
import io.homeassistant.companion.android.common.data.keychain.ClientCertificate
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TLSWebViewClientTest {

    private val serverA = ClientCertEndpoint("a.example.com", 443)
    private val serverB = ClientCertEndpoint("b.example.com", 443)

    private val certificates = mutableMapOf<ClientCertEndpoint, ClientCertificate>()
    private val clientCertProvider = object : ClientCertProvider {
        override val hasCertificate: Boolean
            get() = certificates.isNotEmpty()

        override fun certificateFor(endpoint: ClientCertEndpoint): ClientCertificate? = certificates[endpoint]
    }
    private val keyChainRepository: KeyChainRepository = mockk()
    private val activity: Activity = mockk()
    private val webView: WebView = mockk { every { context } returns activity }
    private val aliasCallback = slot<KeyChainAliasCallback>()

    private val webViewClient = TLSWebViewClient(keyChainRepository, clientCertProvider)

    @BeforeEach
    fun setUp() {
        mockkStatic(KeyChain::class)
        every {
            KeyChain.choosePrivateKeyAlias(any(), capture(aliasCallback), any(), any(), any<String>(), any(), any())
        } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(KeyChain::class)
    }

    private fun certificate(): ClientCertificate {
        val chain = arrayOf(mockk<X509Certificate> { every { checkValidity() } just runs })
        return ClientCertificate(mockk<PrivateKey>(), chain)
    }

    private fun requestFrom(endpoint: ClientCertEndpoint): ClientCertRequest = mockk(relaxed = true) {
        every { host } returns endpoint.host
        every { port } returns endpoint.port
    }

    @Test
    fun `Given a certificate selected for the host when a certificate is requested then it is presented without asking the user`() {
        val certificate = certificate().also { certificates[serverA] = it }
        val request = requestFrom(serverA)

        webViewClient.onReceivedClientCertRequest(webView, request)

        verify { request.proceed(certificate.privateKey, certificate.chain) }
        verify(exactly = 0) { KeyChain.choosePrivateKeyAlias(any(), any(), any(), any(), any<String>(), any(), any()) }
        assertTrue(webViewClient.isTLSClientAuthNeeded)
        assertTrue(webViewClient.isCertificateChainValid)
    }

    @Test
    fun `Given a certificate selected for another server when a certificate is requested then the user is asked for this host`() {
        val certificateA = certificate().also { certificates[serverA] = it }
        val request = requestFrom(serverB)

        webViewClient.onReceivedClientCertRequest(webView, request)

        verify(exactly = 0) { request.proceed(certificateA.privateKey, any()) }
        verify {
            KeyChain.choosePrivateKeyAlias(activity, any(), any(), any(), serverB.host, serverB.port, null)
        }
    }

    @Test
    fun `Given the user picks a certificate when a certificate is requested then it is selected for the requesting host only`() {
        val certificateB = certificate()
        coEvery { keyChainRepository.select(serverB, "alias-b") } returns certificateB
        val request = requestFrom(serverB)
        webViewClient.onReceivedClientCertRequest(webView, request)

        aliasCallback.captured.alias("alias-b")

        verify(timeout = TIMEOUT_MS) { request.proceed(certificateB.privateKey, certificateB.chain) }
        coVerify(exactly = 1) { keyChainRepository.select(serverB, "alias-b") }
        coVerify(exactly = 0) { keyChainRepository.select(serverA, any()) }
    }

    @Test
    fun `Given the user dismisses the chooser when a certificate is requested then the request is ignored`() {
        val request = requestFrom(serverA)
        webViewClient.onReceivedClientCertRequest(webView, request)

        aliasCallback.captured.alias(null)

        verify { request.ignore() }
        assertTrue(webViewClient.hasUserDeniedAccess)
        coVerify(exactly = 0) { keyChainRepository.select(any(), any()) }
    }

    @Test
    fun `Given no certificate chooser on the device when a certificate is requested then the request is ignored`() {
        every {
            KeyChain.choosePrivateKeyAlias(any(), any(), any(), any(), any<String>(), any(), any())
        } throws ActivityNotFoundException()
        val request = requestFrom(serverA)

        webViewClient.onReceivedClientCertRequest(webView, request)

        verify { request.ignore() }
        assertTrue(webViewClient.hasUserDeniedAccess)
    }

    @Test
    fun `Given a WebView without activity when a certificate is requested then the request is ignored`() {
        certificates[serverA] = certificate()
        val request = requestFrom(serverA)
        val detachedWebView = mockk<WebView> { every { context } returns null }

        webViewClient.onReceivedClientCertRequest(detachedWebView, request)

        verify { request.ignore() }
        assertFalse(webViewClient.hasUserDeniedAccess)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
