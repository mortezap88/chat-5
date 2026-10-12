package com.example.network

import android.util.Log
import com.example.data.ApiKeyItem
import com.example.data.FailoverEvent
import com.example.data.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service managing Tavily Web Search API with automated key pooling and failover.
 * Executes live search queries, rotates keys on rate-limits, and returns structured search results.
 */
class TavilyPoolService(
    private var tavilyKeys: List<ApiKeyItem>,
    private var onKeyStatusChanged: (List<ApiKeyItem>) -> Unit = {},
    private var onFailover: (FailoverEvent) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeKeyIndex = 0
    var lastErrorReason: String? = null
        private set

    fun getLastErrorMessage(): String? = lastErrorReason

    init {
        val firstUsable = tavilyKeys.indexOfFirst { it.status != KeyStatus.FAILED }
        if (firstUsable >= 0) {
            activeKeyIndex = firstUsable
        }
    }

    fun updateKeys(newKeys: List<ApiKeyItem>) {
        tavilyKeys = newKeys
        if (activeKeyIndex >= tavilyKeys.size) {
            activeKeyIndex = (tavilyKeys.size - 1).coerceAtLeast(0)
        }
    }

    fun hasAvailableKeys(): Boolean {
        return tavilyKeys.any { it.status != KeyStatus.FAILED && it.key.isNotBlank() }
    }

    suspend fun search(
        query: String,
        thoroughness: String = "Balanced",
        maxResults: Int = 5
    ): TavilySearchResult? = withContext(Dispatchers.IO) {
        lastErrorReason = null
        if (query.isBlank()) return@withContext null

        val usableKeys = tavilyKeys.filter { it.key.isNotBlank() }
        val onlyPlaceholders = usableKeys.isEmpty() || usableKeys.all {
            it.key.contains("production-search-pool") ||
            it.key.contains("failover-backup-search") ||
            it.key == "MY_TAVILY_API_KEY"
        }

        val searchDepth = when (thoroughness.lowercase()) {
            "fast" -> "basic"
            "comprehensive" -> "advanced"
            else -> "basic"
        }

        val effectiveMaxResults = when (thoroughness.lowercase()) {
            "fast" -> maxResults.coerceIn(1, 3)
            "comprehensive" -> maxResults.coerceIn(5, 8)
            else -> maxResults.coerceIn(3, 5)
        }

        // If no custom Tavily key is entered, immediately execute live web search via built-in engine
        if (onlyPlaceholders) {
            val fallbackRes = executeLiveWebFallbackSearch(query, effectiveMaxResults)
            if (fallbackRes != null && fallbackRes.results.isNotEmpty()) {
                return@withContext fallbackRes
            }
            lastErrorReason = "Live web search encountered a connection issue."
            return@withContext null
        }

        val totalKeys = tavilyKeys.size
        var attempts = 0

        while (attempts < totalKeys) {
            val keyItem = tavilyKeys.getOrNull(activeKeyIndex) ?: break

            try {
                val jsonPayload = JSONObject().apply {
                    put("api_key", keyItem.key)
                    put("query", query)
                    put("search_depth", searchDepth)
                    put("max_results", effectiveMaxResults)
                    put("include_answer", true)
                    put("include_raw_content", false)
                }

                val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val root = JSONObject(responseBody)
                    val answer = if (root.has("answer") && !root.isNull("answer")) root.getString("answer") else null
                    val resultsList = mutableListOf<TavilySource>()

                    val resultsArray = root.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until resultsArray.length()) {
                        val itemObj = resultsArray.getJSONObject(i)
                        resultsList.add(
                            TavilySource(
                                title = itemObj.optString("title", "Untitled Source"),
                                url = itemObj.optString("url", ""),
                                content = itemObj.optString("content", ""),
                                score = itemObj.optDouble("score", 0.0)
                            )
                        )
                    }

                    // Key succeeded
                    if (keyItem.status != KeyStatus.WORKING) {
                        updateKeyStatus(keyItem.id, KeyStatus.WORKING, null)
                    }

                    return@withContext TavilySearchResult(
                        query = query,
                        answer = answer,
                        results = resultsList
                    )
                } else {
                    val code = response.code
                    val errorMsg = when (code) {
                        401 -> "Tavily API HTTP 401 (Unauthorized: Invalid or expired API key)."
                        429 -> "Tavily API HTTP 429 (Rate limit or monthly quota exceeded)."
                        else -> "Tavily API HTTP $code: ${responseBody.take(120)}"
                    }
                    lastErrorReason = errorMsg
                    Log.w("TavilyPoolService", "Key fail on ${keyItem.label}: $errorMsg")

                    failoverToNextKey(keyItem, errorMsg)
                    attempts++
                }
            } catch (e: Exception) {
                val errorMsg = "Tavily network connection issue: ${e.message ?: "Network error"}"
                lastErrorReason = errorMsg
                Log.w("TavilyPoolService", "Network exception on ${keyItem.label}: ${e.message}")
                failoverToNextKey(keyItem, e.message ?: "Network error")
                attempts++
            }
        }

        // If all Tavily keys failed, gracefully fallback to live web search
        val fallbackRes = executeLiveWebFallbackSearch(query, effectiveMaxResults)
        if (fallbackRes != null && fallbackRes.results.isNotEmpty()) {
            return@withContext fallbackRes
        }

        if (lastErrorReason == null) {
            lastErrorReason = "All web search sources failed to return results."
        }
        null
    }

    private fun executeLiveWebFallbackSearch(query: String, maxResults: Int): TavilySearchResult? {
        try {
            val formBody = okhttp3.FormBody.Builder()
                .add("q", query)
                .build()

            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return null

            val linkRegex = Regex("""<a[^>]*href=["']([^"']*)["'][^>]*class=["']result-link["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""class=["']result-snippet["'][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

            val linkMatches = linkRegex.findAll(html).toList()
            val snippetMatches = snippetRegex.findAll(html).toList()

            val results = mutableListOf<TavilySource>()
            val count = minOf(linkMatches.size, snippetMatches.size, maxResults)

            for (i in 0 until count) {
                val url = linkMatches[i].groupValues[1].trim()
                val rawTitle = linkMatches[i].groupValues[2]
                val cleanTitle = cleanHtmlText(rawTitle)

                val rawSnippet = snippetMatches[i].groupValues[1]
                val cleanSnippet = cleanHtmlText(rawSnippet)

                if (cleanTitle.isNotBlank() && cleanSnippet.isNotBlank()) {
                    results.add(
                        TavilySource(
                            title = cleanTitle,
                            url = url,
                            content = cleanSnippet,
                            score = 0.95 - (i * 0.05)
                        )
                    )
                }
            }

            if (results.isNotEmpty()) {
                return TavilySearchResult(
                    query = query,
                    answer = null,
                    results = results
                )
            }
        } catch (e: Exception) {
            Log.w("TavilyPoolService", "Live web fallback search error: ${e.message}")
        }
        return null
    }

    private fun cleanHtmlText(raw: String): String {
        val withoutTags = raw.replace(Regex("<[^>]+>"), " ")
        return try {
            android.text.Html.fromHtml(withoutTags, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                .replace(Regex("\\s+"), " ")
        } catch (_: Throwable) {
            withoutTags.trim().replace(Regex("\\s+"), " ")
        }
    }

    private fun failoverToNextKey(failedItem: ApiKeyItem, reason: String) {
        val updated = tavilyKeys.toMutableList()
        val idx = updated.indexOfFirst { it.id == failedItem.id }
        if (idx >= 0) {
            val curr = updated[idx]
            updated[idx] = curr.copy(
                status = KeyStatus.FAILED,
                errorMessage = reason,
                failCount = curr.failCount + 1
            )
        }
        tavilyKeys = updated
        onKeyStatusChanged(updated)

        // Select next available key
        val nextUsable = tavilyKeys.indexOfFirst { it.status != KeyStatus.FAILED }
        if (nextUsable >= 0) {
            activeKeyIndex = nextUsable
            val toKey = tavilyKeys[nextUsable]
            onFailover(
                FailoverEvent(
                    fromKeyMasked = failedItem.maskedKey,
                    toKeyMasked = toKey.maskedKey,
                    reason = reason
                )
            )
        } else {
            // Loop round-robin if all marked failed
            activeKeyIndex = (activeKeyIndex + 1) % tavilyKeys.size.coerceAtLeast(1)
        }
    }

    private fun updateKeyStatus(id: String, status: KeyStatus, error: String?) {
        val updated = tavilyKeys.map {
            if (it.id == id) it.copy(status = status, errorMessage = error) else it
        }
        tavilyKeys = updated
        onKeyStatusChanged(updated)
    }
}
