package com.parallelspace.parallel_space

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Link 盾短链会先返回 HTML 挑战页；用 WebView 跟随到最终地址后抽取 JSON 正文。
 */
object ManifestWebFetcher {
    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(activity: Activity, url: String, result: MethodChannel.Result) {
        val done = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun finish(body: String?) {
            if (!done.compareAndSet(false, true)) return
            main.post {
                try {
                    web?.stopLoading()
                    web?.destroy()
                } catch (_: Exception) {
                }
                web = null
                result.success(body)
            }
        }
        main.post {
            try {
                val wv = WebView(activity.applicationContext)
                web = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onText(t: String?) {
                        val s = t?.trim().orEmpty()
                        if (looksLikeJson(s)) finish(extractJson(s))
                    }
                }, "PS")
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                    override fun onPageFinished(view: WebView?, u: String?) {
                        // 最终页可能是 .txt；用 JS 取 body 文本
                        view?.evaluateJavascript(
                            "(function(){try{return document.body?document.body.innerText:document.documentElement.innerText;}catch(e){return '';}})();",
                        ) { raw ->
                            val text = unescapeJsString(raw)
                            if (looksLikeJson(text)) {
                                finish(extractJson(text))
                            } else if (u != null && (u.contains(".txt") || u.contains("json") || u.contains("/f/"))) {
                                // 再试一次直接读（有时 innerText 仍带盾脚本）
                                view.evaluateJavascript(
                                    "(function(){return document.documentElement.outerHTML;})();",
                                ) { htmlRaw ->
                                    val html = unescapeJsString(htmlRaw)
                                    val extracted = extractJson(html)
                                    if (extracted != null) finish(extracted)
                                }
                            }
                        }
                    }
                }
                wv.loadUrl(url)
                main.postDelayed({ finish(null) }, 45_000)
            } catch (e: Exception) {
                finish(null)
            }
        }
    }

    @Volatile
    private var web: WebView? = null

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("{") && t.contains("versionCode") && t.contains("apkUrl")
    }

    private fun extractJson(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = s.substring(start, end + 1)
        return if (slice.contains("versionCode") && slice.contains("apkUrl")) slice else null
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\").replace("\\u003C", "<")
    }
}
