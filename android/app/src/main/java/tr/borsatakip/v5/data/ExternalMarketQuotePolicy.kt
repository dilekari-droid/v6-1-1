package tr.borsatakip.v5.data

object ExternalMarketQuotePolicy {
    enum class SourceChoice { FALLBACK, CACHE, NONE }

    fun capabilityMarket(symbol: String): String? = when (symbol.trim().uppercase()) {
        "USDTRY" -> "FX"
        "XAUUSD" -> "COMMODITY"
        else -> null
    }

    fun fallbackAllowed(experimentalEnabled: Boolean, yahooEnabled: Boolean): Boolean =
        experimentalEnabled && yahooEnabled

    fun chooseSource(fallbackHasValidStock: Boolean, cacheHasValidStock: Boolean): SourceChoice = when {
        fallbackHasValidStock -> SourceChoice.FALLBACK
        cacheHasValidStock -> SourceChoice.CACHE
        else -> SourceChoice.NONE
    }

    fun classifyProviderError(message: String?): String {
        val text = message.orEmpty().uppercase()
        return when {
            "HTTP 401" in text || "HTTP 403" in text || "API KEY" in text || "AUTH" in text -> "API_AUTH_ERROR"
            "HTTP 429" in text || "RATE" in text && "LIMIT" in text -> "RATE_LIMIT"
            "UNKNOWNHOST" in text || "CONNECT" in text || "SOCKET" in text || "TIMEOUT" in text || "AĞ" in text -> "NETWORK_ERROR"
            "GEÇERSİZ SEMBOL" in text || "SEMBOL" in text && "BULUN" in text -> "SYMBOL_NOT_FOUND"
            "CEVAP YAPISI" in text || "QUOTE DİZİSİ" in text || "KAPANIŞ DİZİSİ" in text || "PARSE" in text -> "PARSE_ERROR"
            text.isBlank() -> "UNKNOWN_ERROR"
            else -> "PROVIDER_ERROR"
        }
    }
}
