package com.example.network

/**
 * Intelligent context-based analyzer that decides whether a user prompt warrants live Tavily web search.
 * Strictly adheres to user criteria:
 * 1. Personal matters (feelings, daily life, casual chat, small talk, emotions, grammar of personal writing) -> NO search, unless explicitly requested.
 * 2. Technical, technological, programming, libraries, architecture, current events, releases, or news -> LIVE search enabled.
 * 3. Explicit directives ("search", "lookup", "latest on", etc.) -> ALWAYS search.
 */
object TavilyContextDecider {

    fun shouldSearch(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase().removeSuffix("?").trim()

        // Pure clock / calendar queries should directly read from device time, NOT execute web searches
        val pureClockQueries = listOf(
            "what day is today", "what date is today", "what is today's date", "what's today's date",
            "today's date", "what is the date", "what day is it", "what time is it", "what's the time"
        )
        if (pureClockQueries.any { lower == it || lower == "$it today" }) {
            return false
        }

        // 1. Explicit search & internet / latest info queries ALWAYS trigger web search
        val explicitTriggers = listOf(
            "search", "lookup", "look up", "google", "web search", "browse",
            "find online", "find on the web", "search internet", "search for",
            "what's the latest on", "what is the latest on", "recent news",
            "latest updates on", "check the web", "look online",
            "internet", "web access", "access to internet", "access to the internet",
            "access the internet", "access the web", "online access", "connected to internet",
            "connected to the internet", "have internet", "have access to internet",
            "do you have internet", "can you access", "browse the web", "browse online",
            "latest model", "newest model", "latest models", "newest models",
            "latest ai", "newest ai", "recent models", "current model",
            "what is the latest", "what's the latest", "what are the latest",
            "latest ones", "newest ones", "the latest ones", "recent ones",
            "search the web", "who is the current", "current status", "right now",
            "real time", "real-time"
        )
        if (explicitTriggers.any { lower.contains(it) }) {
            return true
        }

        // 2. Pure Personal / Casual / Emotional / Small-talk greetings (ONLY when there is no question/directive)
        val pureGreetings = listOf(
            "hello", "hey", "hi", "good morning", "good night", "good evening", "good afternoon",
            "bye", "see you", "thank you", "thanks"
        )
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2 && pureGreetings.any { lower.contains(it) }) {
            return false
        }

        val personalPhrases = listOf(
            "how are you", "my day", "i feel", "i am feeling", "i'm feeling",
            "i am sad", "i'm sad", "i am happy", "i'm happy", "i'm tired", "i am tired",
            "what do you think of me",
            "my girlfriend", "my boyfriend", "my wife", "my husband", "my friend",
            "my mother", "my father", "my mom", "my dad", "my dog", "my cat",
            "what should i eat", "recommend a routine", "my diary", "my thoughts",
            "tell me a joke", "write a poem", "tell me a story",
            "is this sentence correct", "correct my grammar", "say this more politely",
            "how do i pronounce", "translate this to"
        )
        if (personalPhrases.any { lower.startsWith(it) || lower == it }) {
            return false
        }

        // 3. Technical & Engineering Context (Technology grows daily, keep up-to-date)
        val technicalTerms = listOf(
            "kotlin", "jetpack", "compose", "android", "gradle", "ksp", "room", "retrofit",
            "okhttp", "ktor", "python", "javascript", "typescript", "react", "nextjs", "vue",
            "rust", "c++", "golang", "swift", "swiftui", "java", "sql", "postgres", "sqlite",
            "redis", "mongodb", "docker", "kubernetes", "linux", "bash", "git", "github",
            "api", "sdk", "framework", "library", "endpoint", "dependency", "version",
            "architecture", "algorithm", "compiler", "debug", "stacktrace", "exception",
            "crash", "coroutine", "flow", "lifecycle", "viewmodel", "thread", "concurrency",
            "gemini", "openai", "gpt", "claude", "llama", "llm", "ai model", "transformer",
            "model", "models", "deepseek",
            "benchmark", "gpu", "cpu", "chip", "hardware", "spec", "specs", "vram",
            "nvidia", "apple", "google", "microsoft", "meta", "aws", "cloud",
            "documentation", "doc", "rfc", "protocol", "http", "websocket", "oauth",
            "tavily", "rag", "vector", "embedding", "npm", "pip", "package"
        )

        val hasTechnicalTerm = technicalTerms.any { term ->
            val pattern = "\\b${Regex.escape(term)}\\b".toRegex()
            pattern.containsMatchIn(lower)
        }

        // 4. Current Events, Temporal updates, News & Factual Developments
        val temporalAndNewsTerms = listOf(
            "news", "release", "released", "announced", "launch", "launched",
            "update", "updated", "current", "today", "yesterday", "this week",
            "this month", "recent", "latest", "newest", "price", "stock", "earnings",
            "election", "winner", "score", "game", "match", "status", "who is the current",
            "2024", "2025", "2026", "2027"
        )

        val hasNewsOrTemporalTerm = temporalAndNewsTerms.any { term ->
            val pattern = "\\b${Regex.escape(term)}\\b".toRegex()
            pattern.containsMatchIn(lower)
        }

        return hasTechnicalTerm || hasNewsOrTemporalTerm
    }

    /**
     * Cleans conversational directives (e.g. "search the web about", "look up", "google")
     * from queries before submitting to the web search API to get crisp, accurate search results.
     */
    fun extractEffectiveSearchQuery(query: String, recentContext: String? = null): String {
        val trimmed = query.trim()
        val lower = trimmed.lowercase().removeSuffix("?").trim()

        // Handle vague contextual follow-up queries like "what is the latest ones?"
        val vagueLatestPatterns = listOf(
            "what is the latest ones", "what's the latest ones", "what are the latest ones",
            "the latest ones", "latest ones", "newest ones", "what are the newest ones",
            "what is the latest", "what's the latest", "what are the latest"
        )
        if (vagueLatestPatterns.any { lower == it || lower.startsWith(it) }) {
            val ctxLower = recentContext?.lowercase().orEmpty()
            return when {
                ctxLower.contains("model") || ctxLower.contains("gemini") || ctxLower.contains("ai") || ctxLower.contains("llm") ->
                    "latest AI models releases"
                ctxLower.contains("phone") || ctxLower.contains("android") ->
                    "latest Android devices"
                recentContext?.isNotBlank() == true -> {
                    val keyWord = recentContext.split(Regex("\\s+")).filter { it.length > 4 }.takeLast(3).joinToString(" ")
                    if (keyWord.isNotBlank()) "latest $keyWord" else "latest AI models"
                }
                else -> "latest AI models"
            }
        }

        val prefixesToStrip = listOf(
            "search the web about",
            "search the web for",
            "search the web",
            "search the internet about",
            "search the internet for",
            "search the internet",
            "search online about",
            "search online for",
            "search online",
            "search for",
            "search",
            "please search about",
            "please search for",
            "please search",
            "can you search the web for",
            "can you search the web about",
            "can you search online for",
            "can you search for",
            "can you search",
            "look up",
            "lookup",
            "google",
            "find online",
            "find on the web"
        )

        for (prefix in prefixesToStrip) {
            if (lower.startsWith(prefix)) {
                val candidate = trimmed.substring(prefix.length).trim()
                    .removePrefix(":")
                    .removePrefix("-")
                    .removeSuffix("?")
                    .trim()
                if (candidate.isNotBlank() && candidate.length > 2) {
                    return candidate
                }
            }
        }
        return trimmed.removeSuffix("?").trim()
    }
}
