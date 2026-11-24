package com.novelworkspace.dashboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.novelworkspace.compatibility.DataviewQueryEngine
import com.novelworkspace.compatibility.NativeNetworkProxy
import com.novelworkspace.core.markdown.MarkdownEngine
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.sync.VaultSyncEngine
import com.novelworkspace.core.vault.VaultGateway
import com.novelworkspace.core.vault.VaultPathValidator
import com.novelworkspace.core.vault.WriteResult
import com.novelworkspace.data.models.CustomPageManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream

data class ConsoleEntry(
    val level: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IsolatedPageWebView(
    manifest: CustomPageManifest,
    jsCode: String,
    cssCode: String,
    vaultGateway: VaultGateway,
    queryEngine: DataviewQueryEngine,
    syncEngine: VaultSyncEngine,
    networkProxy: NativeNetworkProxy,
    config: VaultConfiguration,
    allowWrite: Boolean,
    reloadToken: Int,
    onConsoleLog: (ConsoleEntry) -> Unit,
    onNotice: (String) -> Unit,
    onOpenVaultFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val pagePrefs = remember(manifest.id) {
        context.getSharedPreferences("nw_page_ls_${manifest.id}", Context.MODE_PRIVATE)
    }

    val assetLoader = remember(vaultGateway, config) {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/vault/") { path ->
                runCatching {
                    val norm = VaultPathValidator.normalize(Uri.decode(path))
                    val resolved = runBlocking(Dispatchers.IO) {
                        if (vaultGateway.exists(norm)) norm
                        else queryEngine.resolveAliasedPath(vaultGateway, norm, config, manifest.folderPath)
                    } ?: return@runCatching null
                    val bytes = runBlocking(Dispatchers.IO) { vaultGateway.readBytes(resolved) }
                        ?: return@runCatching null
                    val ext = resolved.substringAfterLast('.', "").lowercase()
                    val mime = when (ext) {
                        "css" -> "text/css"
                        "js" -> "application/javascript"
                        "json" -> "application/json"
                        "svg" -> "image/svg+xml"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    }
                    WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
                }.getOrNull()
            }
            .build()
    }

    fun sendRpcResponse(webView: WebView?, id: Int, ok: Boolean, payloadJson: String) {
        val escaped = JSONObject.quote(payloadJson)
        val js = "window.__nwResolveRpc && window.__nwResolveRpc($id, $ok, $escaped);"
        webView?.post { webView.evaluateJavascript(js, null) }
    }

    fun handleBridgeMessage(raw: String, webView: WebView?) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "console" -> {
                onConsoleLog(
                    ConsoleEntry(
                        level = msg.optString("level", "log"),
                        message = msg.optString("message", "")
                    )
                )
            }
            "rpc" -> {
                val id = msg.optInt("id", -1)
                val method = msg.optString("method")
                val params = msg.optJSONObject("params") ?: JSONObject()
                scope.launch(Dispatchers.IO) {
                    try {
                        val resultJson = dispatchRpc(
                            method = method,
                            params = params,
                            manifest = manifest,
                            vaultGateway = vaultGateway,
                            queryEngine = queryEngine,
                            syncEngine = syncEngine,
                            networkProxy = networkProxy,
                            config = config,
                            allowWrite = allowWrite,
                            pagePrefs = pagePrefs,
                            context = context,
                            onNotice = onNotice,
                            onOpenVaultFile = onOpenVaultFile
                        )
                        sendRpcResponse(webView, id, true, resultJson)
                    } catch (e: Exception) {
                        val err = JSONObject().put("error", e.message ?: "Bridge error").toString()
                        onConsoleLog(ConsoleEntry("error", "[$method] ${e.message}"))
                        sendRpcResponse(webView, id, false, err)
                    }
                }
            }
        }
    }

    suspend fun loadPageInto(webView: WebView) {
        val storageMap = pagePrefs.all.entries.associate { it.key to (it.value?.toString() ?: "") }
        val payload = queryEngine.buildHydrationPayload(manifest, config, storageMap)
        val html = buildIsolatedHtml(
            title = manifest.title,
            cssCode = cssCode,
            jsCode = jsCode,
            hydrationJson = payload.toString()
        )
        webView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/page/${manifest.id}/index.html",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    LaunchedEffect(manifest.id, jsCode, cssCode, reloadToken) {
        webViewRef?.let { wv -> loadPageInto(wv) }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest
                    ): Boolean {
                        val host = request.url.host
                        return host != "appassets.androidplatform.net"
                    }
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        "__nwNativeBridge",
                        setOf("https://appassets.androidplatform.net")
                    ) { _, message, _, _, _ ->
                        handleBridgeMessage(message.data ?: "", this)
                    }
                } else {
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(rawJson: String) {
                                handleBridgeMessage(rawJson, this@apply)
                            }
                        },
                        "__nwNativeBridge"
                    )
                }

                scope.launch { loadPageInto(this@apply) }
            }
        }
    )
}

private suspend fun dispatchRpc(
    method: String,
    params: JSONObject,
    manifest: CustomPageManifest,
    vaultGateway: VaultGateway,
    queryEngine: DataviewQueryEngine,
    syncEngine: VaultSyncEngine,
    networkProxy: NativeNetworkProxy,
    config: VaultConfiguration,
    allowWrite: Boolean,
    pagePrefs: android.content.SharedPreferences,
    context: Context,
    onNotice: (String) -> Unit,
    onOpenVaultFile: (String) -> Unit
): String {
    return when (method) {
        "notice" -> {
            onNotice(params.optString("message", ""))
            "{}"
        }
        "openFile" -> {
            val p = VaultPathValidator.normalize(params.optString("path", ""))
            onOpenVaultFile(p)
            "{}"
        }
        "clipboardWrite" -> {
            val txt = params.optString("text", "")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Novel Workspace", txt))
            "{}"
        }
        "storageSet" -> {
            pagePrefs.edit().putString(params.optString("key"), params.optString("value")).apply()
            "{}"
        }
        "storageRemove" -> {
            pagePrefs.edit().remove(params.optString("key")).apply()
            "{}"
        }
        "storageClear" -> {
            pagePrefs.edit().clear().apply()
            "{}"
        }
        "vaultExists" -> {
            val raw = params.optString("path", "")
            val resolved = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath)
            JSONObject().put("exists", resolved != null).toString()
        }
        "vaultRead" -> {
            val raw = params.optString("path", "")
            val resolved = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath)
                ?: throw IllegalArgumentException("File not found in vault: $raw")
            val read = vaultGateway.readText(resolved)
                ?: throw IllegalArgumentException("Unreadable vault file: $resolved")
            JSONObject().put("path", resolved).put("text", read.text).toString()
        }
        "vaultWrite" -> {
            if (!allowWrite) throw SecurityException("Vault write access is disabled for page '${manifest.title}'.")
            val raw = VaultPathValidator.normalize(params.optString("path", ""))
            val targetPath = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath) ?: raw
            val content = params.optString("content", "")
            when (val res = vaultGateway.writeTextForce(targetPath, content)) {
                is WriteResult.Success -> {
                    syncEngine.reindexSingleFile(vaultGateway, targetPath)
                    JSONObject().put("path", targetPath).toString()
                }
                is WriteResult.Error -> throw IllegalStateException(res.message)
                is WriteResult.Conflict -> throw IllegalStateException("Conflict on $targetPath")
            }
        }
        "vaultCreateFolder" -> {
            if (!allowWrite) throw SecurityException("Vault write access is disabled for page '${manifest.title}'.")
            val path = VaultPathValidator.normalize(params.optString("path", ""))
            vaultGateway.createFolder(path)
            "{}"
        }
        "vaultCreateBinary" -> {
            if (!allowWrite) throw SecurityException("Vault write access is disabled for page '${manifest.title}'.")
            val path = VaultPathValidator.normalize(params.optString("path", ""))
            val bytes = Base64.decode(params.optString("base64", ""), Base64.DEFAULT)
            vaultGateway.writeBytes(path, bytes)
            "{}"
        }
        "vaultDelete" -> {
            if (!allowWrite) throw SecurityException("Vault write access is disabled for page '${manifest.title}'.")
            val raw = VaultPathValidator.normalize(params.optString("path", ""))
            val target = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath) ?: raw
            vaultGateway.deleteFile(target)
            syncEngine.reindexSingleFile(vaultGateway, target)
            "{}"
        }
        "readFrontmatter" -> {
            val raw = params.optString("path", "")
            val resolved = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath)
                ?: throw IllegalArgumentException("Note not found: $raw")
            val read = vaultGateway.readText(resolved)
                ?: throw IllegalArgumentException("Unreadable note: $resolved")
            val parsed = MarkdownEngine.parse(read.text, resolved)
            JSONObject().put("frontmatter", JSONObject(parsed.frontmatter)).toString()
        }
        "writeFrontmatter" -> {
            if (!allowWrite) throw SecurityException("Vault write access is disabled for page '${manifest.title}'.")
            val raw = params.optString("path", "")
            val resolved = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath)
                ?: throw IllegalArgumentException("Note not found: $raw")
            val fmObj = params.optJSONObject("frontmatter") ?: JSONObject()
            val live = vaultGateway.readText(resolved)
                ?: throw IllegalArgumentException("Note vanished: $resolved")
            val updated = MarkdownEngine.updateFrontmatterPreservingBody(live.text) { map ->
                fmObj.keys().forEach { k ->
                    val v = fmObj.get(k)
                    map[k] = if (v == JSONObject.NULL) null else v
                }
            }
            vaultGateway.writeTextForce(resolved, updated)
            syncEngine.reindexSingleFile(vaultGateway, resolved)
            "{}"
        }
        "resolveModule" -> {
            val raw = params.optString("path", "")
            val resolved = queryEngine.resolveAliasedPath(vaultGateway, raw, config, manifest.folderPath)
                ?: throw IllegalArgumentException("Module not found: $raw (check folder aliases in Settings)")
            val code = vaultGateway.readText(resolved)?.text
                ?: throw IllegalArgumentException("Failed reading module: $resolved")
            JSONObject().put("path", resolved).put("code", code).toString()
        }
        "requestUrl" -> {
            val hdrObj = params.optJSONObject("headers") ?: JSONObject()
            val hdrMap = linkedMapOf<String, String>()
            hdrObj.keys().forEach { k -> hdrMap[k] = hdrObj.optString(k) }
            val resp = networkProxy.executeRequest(
                pageId = manifest.id,
                urlString = params.optString("url"),
                method = params.optString("method", "GET"),
                headers = hdrMap,
                body = if (params.isNull("body")) null else params.optString("body")
            )
            JSONObject()
                .put("status", resp.status)
                .put("headers", JSONObject(resp.headers))
                .put("text", resp.text)
                .toString()
        }
        else -> throw UnsupportedOperationException("Unsupported compatibility API method: $method")
    }
}

private fun buildIsolatedHtml(
    title: String,
    cssCode: String,
    jsCode: String,
    hydrationJson: String
): String {
    val safeHydration = JSONObject.quote(hydrationJson)
    val safeJs = JSONObject.quote(jsCode)
    return """
<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
<title>${title.replace("<", "&lt;")}</title>
<style>
  html, body { margin: 0; padding: 8px; box-sizing: border-box; font-family: system-ui, -apple-system, sans-serif; }
  $cssCode
</style>
</head>
<body>
<div id="nw-page-root"></div>
<script src="https://appassets.androidplatform.net/assets/runtime/obsidian_dataview_shim.js"></script>
<script>
(async function() {
  try {
    window.__nwHydrateContext($safeHydration);
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    const fn = new AsyncFunction('dv', 'app', 'Notice', 'requestUrl', 'moment', 'localStorage', $safeJs);
    await fn(window.dv, window.app, window.Notice, window.requestUrl, window.moment, window.localStorage);
  } catch (err) {
    console.error('[Page Execution Error] ' + (err && err.stack ? err.stack : err));
    const root = document.getElementById('nw-page-root');
    if (root) {
      const box = document.createElement('pre');
      box.style.cssText = 'background:#2b1111;color:#ffb4b4;padding:12px;border-radius:8px;white-space:pre-wrap;font-size:12px;';
      box.textContent = 'Dashboard Runtime Error:\n' + (err && err.stack ? err.stack : err);
      root.prepend(box);
    }
  }
})();
</script>
</body>
</html>
""".trimIndent()
}
