package com.novelworkspace.compatibility

import com.novelworkspace.core.security.VaultConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ProxyHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val text: String
)

class NativeNetworkProxy(
    private val configStore: VaultConfigStore
) {
    suspend fun executeRequest(
        pageId: String,
        urlString: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): ProxyHttpResponse = withContext(Dispatchers.IO) {
        if (!configStore.isPageNetworkAllowed(pageId)) {
            throw SecurityException("Network access is disabled for custom page '$pageId'. Enable it in Page Studio or Settings.")
        }
        val resolvedUrl = substituteSecretUrl(urlString)
        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = 20_000
            readTimeout = 45_000
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, substituteSecretHeader(k, v)) }
            if (body != null && requestMethod in setOf("POST", "PUT", "PATCH")) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            val respHeaders = linkedMapOf<String, String>()
            conn.headerFields.forEach { (k, vals) ->
                if (k != null && vals.isNotEmpty()) respHeaders[k] = vals.joinToString(", ")
            }
            ProxyHttpResponse(code, respHeaders, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun substituteSecretUrl(url: String): String {
        var out = url
        if (out.contains("generativelanguage.googleapis.com") && out.contains("YOUR_")) {
            configStore.getApiKey("gemini")?.let { key ->
                out = out.replace(Regex("key=[^&]+"), "key=$key")
            }
        }
        configStore.getApiKey("gemini")?.let { out = out.replace("{{GEMINI_API_KEY}}", it) }
        return out
    }

    private fun substituteSecretHeader(key: String, value: String): String {
        var out = value
        configStore.getApiKey("groq")?.let { out = out.replace("{{GROQ_API_KEY}}", it) }
        configStore.getApiKey("openrouter")?.let { out = out.replace("{{OPENROUTER_API_KEY}}", it) }
        if (key.equals("Authorization", true) && out.contains("YOUR_")) {
            val replacement = configStore.getApiKey("groq") ?: configStore.getApiKey("openrouter")
            if (replacement != null) out = "Bearer $replacement"
        }
        return out
    }
}
