package com.emusite.api

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object WebViewHelper {
    private var webView: WebView? = null

    suspend fun loadPage(context: Context, url: String): String = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "(function(){return document.documentElement.outerHTML})();"
                        ) { result ->
                            val html = result?.removeSurrounding("\"")?.replace("\\u003C", "<")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                            cont.resume(html)
                        }
                    }
                }
            }
            webView = wv
            wv.loadUrl(url)
        }.also { webView?.destroy(); webView = null }
    }
}
