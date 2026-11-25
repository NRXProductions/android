package io.homeassistant.companion.android.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.webkit.ClientCertRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import io.homeassistant.companion.android.common.data.keychain.ClientCertEndpoint
import io.homeassistant.companion.android.common.data.keychain.ClientCertProvider
import io.homeassistant.companion.android.common.data.keychain.ClientCertificate
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import java.lang.ref.WeakReference
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

@SuppressLint("MissingOnRenderProcessGone")
open class TLSWebViewClient(
    private val keyChainRepository: KeyChainRepository,
    private val clientCertProvider: ClientCertProvider,
) : WebViewClient() {
    var isTLSClientAuthNeeded = false
        @VisibleForTesting set

    var hasUserDeniedAccess = false
        private set

    var isCertificateChainValid = false
        @VisibleForTesting set

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return context as? Activity ?: getActivity(context.baseContext)
        }
        return null
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        Timber.d("onReceivedClientCertRequest invoked looking for cert in local storage or ask the user for it")
        // Let the WebViewClient know the endpoint requires TLS Client Auth
        isTLSClientAuthNeeded = true

        val activity = getActivity(view.context)
        if (activity == null) {
            request.ignore()
            return
        }

        // The certificate is selected per endpoint: the one of another server must never be presented
        // to this host, and a host without a selection yet gets its own prompt.
        val endpoint = ClientCertEndpoint(request.host, request.port)
        val certificate = clientCertProvider.certificateFor(endpoint)
        if (certificate != null) {
            checkChainValidity(certificate.chain)
            request.proceed(certificate.privateKey, certificate.chain)
        } else {
            // If no certificate is available for this endpoint, then the user must be prompted for one
            selectClientCert(activity, request, endpoint)
        }
    }

    private fun selectClientCert(activity: Activity, request: ClientCertRequest, endpoint: ClientCertEndpoint) {
        // prompt the user for a key
        try {
            KeyChain.choosePrivateKeyAlias(
                activity,
                SafeKeyChainAliasCallback(keyChainRepository, endpoint) { certificate ->
                    if (certificate == null) {
                        hasUserDeniedAccess = true
                        request.ignore()
                    } else {
                        checkChainValidity(certificate.chain)
                        request.proceed(certificate.privateKey, certificate.chain)
                    }
                },
                request.keyTypes,
                request.principals,
                request.host,
                request.port,
                null,
            )
        } catch (e: ActivityNotFoundException) {
            // some cut-down ROMs don't have a client TLS certificate chooser activity (com.android.keychain.CHOOSER)
            // cancel the request so the WebView proceeds without presenting a cert
            Timber.w(e, "Client certificate chooser activity not available, proceeding without cert")
            hasUserDeniedAccess = true
            request.ignore()
        }
    }

    private fun checkChainValidity(chain: Array<X509Certificate>) {
        // Ensure the whole certificate chain is valid
        isCertificateChainValid = try {
            chain.forEach { it.checkValidity() }
            true
        } catch (ex: CertificateException) {
            false
        }
    }
}

/**
 * Addresses a potential memory leak with [KeyChain.choosePrivateKeyAlias].
 *
 * [KeyChain.choosePrivateKeyAlias] holds a strong reference to its callback even after
 * invocation. To prevent this callback from leaking its capturing context (e.g., a WebView),
 * this wrapper stores the actual result consumer ([onResult]) in a [WeakReference].
 *
 * If the consumer (e.g., WebView) is destroyed before the user selects a key,
 * the [WeakReference] will allow it to be garbage collected, and the result will not be
 * delivered to the (now-gone) consumer. The user's selection is still persisted through
 * [KeyChainRepository.select] within this callback's coroutine scope, ensuring the choice
 * survives even if the initial UI component is gone.
 */
private class SafeKeyChainAliasCallback(
    private val keyChainRepository: KeyChainRepository,
    private val endpoint: ClientCertEndpoint,
    onResult: (certificate: ClientCertificate?) -> Unit,
) : KeyChainAliasCallback {
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private val onResult = WeakReference(onResult)

    override fun alias(alias: String?) {
        if (alias != null) {
            ioScope.launch {
                val certificate = keyChainRepository.select(endpoint, alias)
                onResult.get()?.invoke(certificate)
            }
        } else {
            onResult.get()?.invoke(null)
        }
    }
}
