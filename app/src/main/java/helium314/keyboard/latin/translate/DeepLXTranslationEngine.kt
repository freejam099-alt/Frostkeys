// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import helium314.keyboard.latin.cloud.CloudManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Parsed body of a DLX / official DeepL response. Public for unit tests. */
data class ParsedTranslationBody(
    val translatedText: String?,
    val detectedSourceLanguage: String?,
    val errorMessage: String?,
    val code: Int?,
)

/**
 * Normalizes the configured endpoint:
 * - trims whitespace
 * - appends `/translate` when only a base URL is configured (self hosted DLX default port 1188)
 * - keeps explicit paths (e.g. `/translate` or official `https://api-free.deepl.com/v2/translate`)
 *
 * Public for unit tests.
 */
fun normalizeEndpoint(endpoint: String): String {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return ""
    val withoutTrailingSlash = trimmed.trimEnd('/')
    val afterScheme = withoutTrailingSlash.substringAfter("://", withoutTrailingSlash)
    return if (afterScheme.contains('/')) withoutTrailingSlash else "$withoutTrailingSlash/translate"
}

/**
 * Parses both DLX and official DeepL response shapes. Tolerant: never throws.
 * Only server provided error *messages* are extracted – never the user text.
 *
 * Public for unit tests.
 */
fun parseTranslationBody(body: String?): ParsedTranslationBody {
    if (body.isNullOrBlank()) return ParsedTranslationBody(null, null, null, null)
    return try {
        val json = JSONObject(body)
        val code = if (json.has("code")) json.optInt("code", 0).takeIf { it > 0 } else null
        val translations = json.optJSONArray("translations")
        val firstTranslation = if (translations != null && translations.length() > 0)
            translations.optJSONObject(0) else null
        val translated = json.optString("data").takeIf { it.isNotEmpty() }
            ?: firstTranslation?.optString("text")?.takeIf { it.isNotEmpty() }
        val detected = json.optString("source_lang").takeIf { it.isNotEmpty() }
            ?: firstTranslation?.optString("detected_source_language")?.takeIf { it.isNotEmpty() }
        val message = json.optString("message").takeIf { it.isNotEmpty() }
            ?: json.optString("title").takeIf { it.isNotEmpty() }
        ParsedTranslationBody(translated, detected, message, code)
    } catch (parseError: Exception) {
        // malformed JSON → handled by the caller as invalid response
        ParsedTranslationBody(null, null, null, null)
    }
}

/**
 * Translation engine for DeepLX (renamed DLX) and DeepL compatible HTTP APIs.
 *
 * Request (DLX, POST /translate):
 * ```json
 * { "text": "Hello", "source_lang": "EN", "target_lang": "ID" }
 * ```
 * `source_lang` is omitted for auto-detection.
 *
 * Responses supported:
 * - DLX:      `{ "code": 200, "id": ..., "data": "<translation>", "source_lang": "EN" }`
 * - error:    `{ "code": 429, "message": "..." }`
 * - official DeepL v2: `{ "translations": [ { "detected_source_language": "EN", "text": "..." } ] }`
 *
 * The endpoint and an optional API key are provided by lambdas so the backend can be replaced
 * at runtime (settings) without recreating the engine. No secret is hardcoded.
 *
 * The shared [CloudManager] HTTP client is reused (connection pool + dispatcher); a derived
 * client only adds timeouts. The client is never recreated per request.
 */
class DeepLXTranslationEngine(
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String = { "" },
    private val cloudEnabledProvider: () -> Boolean = { true },
    private val client: OkHttpClient = defaultHttpClient(),
    private val retryDelayMs: Long = 350L,
) : TranslationEngine {

    companion object {
        private const val MAX_ATTEMPTS = 2
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Derives a client with sane timeouts from the shared singleton – keeps the connection pool. */
        fun defaultHttpClient(): OkHttpClient = CloudManager.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult {
        if (text.isEmpty()) return TranslationResult.Success("")
        if (!cloudEnabledProvider()) {
            return TranslationResult.Failure(
                TranslationFailureKind.CLOUD_DISABLED,
                "Cloud & AI features are disabled in settings",
            )
        }
        val url = normalizeEndpoint(endpointProvider())
        if (url.isEmpty()) {
            return TranslationResult.Failure(
                TranslationFailureKind.NOT_CONFIGURED,
                "No translation endpoint configured",
            )
        }

        val request = try {
            val payload = JSONObject().apply {
                put("text", text)
                put("target_lang", targetLanguage.uppercase())
                if (sourceLanguage.isNotBlank() &&
                    !sourceLanguage.equals(TranslationLanguages.AUTO_DETECT, ignoreCase = true)
                ) {
                    put("source_lang", sourceLanguage.uppercase())
                }
            }
            val builder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            val apiKey = apiKeyProvider()
            if (apiKey.isNotBlank()) {
                // accepted by the official DeepL API, ignored by DLX
                builder.header("Authorization", "DeepL-Auth-Key $apiKey")
            }
            builder.build()
        } catch (invalidUrl: IllegalArgumentException) {
            return TranslationResult.Failure(
                TranslationFailureKind.NOT_CONFIGURED,
                "Invalid translation endpoint",
            )
        }

        // one bounded retry for transient network errors – never an unbounded retry loop
        var attempt = 0
        while (true) {
            val result = executeRequest(request)
            val retryable = result is TranslationResult.Failure &&
                result.kind == TranslationFailureKind.NETWORK &&
                attempt < MAX_ATTEMPTS - 1
            if (!retryable) return result
            attempt++
            delay(retryDelayMs)
        }
    }

    private suspend fun executeRequest(request: Request): TranslationResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val result = if (e is SocketTimeoutException || e is InterruptedIOException) {
                        TranslationResult.Failure(TranslationFailureKind.TIMEOUT, "Translation request timed out")
                    } else {
                        TranslationResult.Failure(TranslationFailureKind.NETWORK, "Network error")
                    }
                    continuation.resume(result)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = runCatching { resp.body?.string() }.getOrNull()
                        if (!continuation.isActive) return
                        continuation.resume(mapResponse(resp, parseTranslationBody(body)))
                    }
                }
            })
        }

    private fun mapResponse(response: Response, parsed: ParsedTranslationBody): TranslationResult = when {
        response.isSuccessful && parsed.translatedText != null ->
            TranslationResult.Success(parsed.translatedText, parsed.detectedSourceLanguage?.uppercase())

        response.code == 429 -> TranslationResult.Failure(
            TranslationFailureKind.RATE_LIMITED,
            parsed.errorMessage ?: "Rate limit reached, try again later",
        )

        response.code == 403 -> TranslationResult.Failure(
            TranslationFailureKind.FORBIDDEN,
            parsed.errorMessage ?: "Request blocked by the translation server",
        )

        response.code == 413 -> TranslationResult.Failure(
            TranslationFailureKind.TOO_LONG,
            parsed.errorMessage ?: "Text chunk is too long for the server",
        )

        response.code == 504 -> TranslationResult.Failure(
            TranslationFailureKind.TIMEOUT,
            parsed.errorMessage ?: "Translation server timed out",
        )

        response.isSuccessful -> TranslationResult.Failure(
            TranslationFailureKind.INVALID_RESPONSE,
            "Unexpected response from translation server",
        )

        else -> TranslationResult.Failure(
            TranslationFailureKind.SERVER,
            parsed.errorMessage ?: "Translation server error (${response.code})",
        )
    }
}
