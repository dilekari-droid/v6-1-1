package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.ViopContract
import java.util.Locale

/** Search/ranking policy shared by VİOP auto suggestions and tests. */
object ViopContractSearch {
    private val aliases = mapOf(
        "AKBNK" to listOf("AKBANK", "AKBANK TAS"),
        "GARAN" to listOf("GARANTI", "GARANTI BANKASI", "TURKIYE GARANTI BANKASI"),
        "ASELS" to listOf("ASELSAN"),
        "THYAO" to listOf("THY", "TURK HAVA YOLLARI"),
        "TUPRS" to listOf("TUPRAS", "TURKIYE PETROL RAFINERILERI"),
        "ISCTR" to listOf("IS BANKASI", "TURKIYE IS BANKASI"),
        "YKBNK" to listOf("YAPI KREDI", "YAPI VE KREDI BANKASI"),
        "KCHOL" to listOf("KOC HOLDING"),
        "SAHOL" to listOf("SABANCI HOLDING"),
        "EREGL" to listOf("EREGLI", "EREGLI DEMIR CELIK"),
        "SISE" to listOf("SISECAM", "TURKIYE SISE CAM"),
        "PGSUS" to listOf("PEGASUS"),
        "BIMAS" to listOf("BIM", "BIM MAGAZALAR"),
        "FROTO" to listOf("FORD OTOSAN"),
        "TOASO" to listOf("TOFAS"),
        "PETKM" to listOf("PETKIM"),
        "USDTRY" to listOf("USD", "DOLAR", "AMERIKAN DOLARI"),
        "EURTRY" to listOf("EUR", "EURO"),
        "GOLD" to listOf("ALTIN", "XAU"),
        "SILVER" to listOf("GUMUS", "XAG"),
        "BRENT" to listOf("PETROL")
    )

    fun search(
        source: List<ViopContract>,
        rawQuery: String,
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis()
    ): List<ViopContract> {
        val q = normalize(rawQuery)
        if (q.isBlank()) return emptyList()
        return source.asSequence()
            .filter { it.expiryAt == null || it.expiryAt >= nowMs }
            .mapNotNull { c -> score(c, q)?.let { it to c } }
            .sortedWith(
                compareBy<Pair<Int, ViopContract>> { it.first }
                    .thenBy { it.second.expiryAt ?: Long.MAX_VALUE }
                    .thenBy { it.second.expiry }
                    .thenBy { it.second.symbol }
            )
            .take(limit)
            .map { it.second }
            .toList()
    }

    fun browse(
        source: List<ViopContract>,
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis()
    ): List<ViopContract> = source.asSequence()
        .filter { it.expiryAt == null || it.expiryAt >= nowMs }
        .sortedWith(
            compareBy<ViopContract> { normalize(it.underlying) }
                .thenBy { it.expiryAt ?: Long.MAX_VALUE }
                .thenBy { it.expiry }
                .thenBy { it.symbol }
        )
        .take(limit)
        .toList()

    fun displayUnderlying(contract: ViopContract): String {
        val code = extractUnderlyingCode(contract)
        val alias = aliases[code]?.firstOrNull()
        return when {
            alias != null && contract.underlying.equals(code, true) -> "$code • $alias"
            else -> contract.underlying.ifBlank { code.ifBlank { "Dayanak bilinmiyor" } }
        }
    }

    private fun score(c: ViopContract, q: String): Int? {
        val symbol = normalize(c.symbol)
        val underlying = normalize(c.underlying)
        val type = normalize(c.contractType.label)
        val code = extractUnderlyingCode(c)

        // A known underlying code is an exact intent, not a fuzzy query.
        // Example: AKBNK must never leak YKBNK merely because the two codes
        // differ by one character. Company-name aliases and genuine typos are
        // still handled by the alias/fuzzy branches below.
        if (q in aliases.keys && code != q) return null
        val aliasValues = aliases[code].orEmpty().map(::normalize)
        val searchable = buildList {
            add(symbol)
            add(underlying)
            add(code)
            add(type)
            addAll(aliasValues)
        }.filter { it.isNotBlank() }

        val vadeliSearch = q == "V" || "VADELI".startsWith(q) || q.startsWith("VADELI")
        val prefixOnly = q.length <= 2 && !vadeliSearch
        return when {
            symbol == q || code == q -> 0
            underlying == q -> 1
            aliasValues.any { it == q } -> 2
            symbol.startsWith(q) -> 3
            underlying.startsWith(q) || code.startsWith(q) -> 4
            aliasValues.any { it.startsWith(q) } -> 5
            vadeliSearch && type.startsWith("VADELI") -> 6
            prefixOnly -> null
            searchable.any { it.contains(q) } -> 7
            q.length >= 4 && searchable.any { fuzzyTokenMatch(it, q) } -> 8
            else -> null
        }
    }

    private fun extractUnderlyingCode(c: ViopContract): String {
        val direct = normalize(c.underlying)
        if (direct in aliases || direct.matches(Regex("[A-Z]{4,6}"))) return direct
        val symbol = normalize(c.symbol)
        return aliases.keys.firstOrNull { symbol.contains(it) }
            ?: Regex("(?:F|FUT)?_?([A-Z]{4,6})(?:_|\\d)").find(symbol)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun fuzzyTokenMatch(value: String, query: String): Boolean {
        if (value.length < 4 || query.length < 4) return false
        val words = value.split(Regex("[^A-Z0-9]+")) + value
        return words.any { token ->
            token.length >= 4 && kotlin.math.abs(token.length - query.length) <= 1 && editDistanceAtMostOne(token, query)
        }
    }

    /** Fast distance<=1 check; enough for mobile search typos such as AKBAK -> AKBANK. */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    fun normalize(value: String): String = value
        .trim()
        .uppercase(Locale.ROOT)
        .replace('İ', 'I')
        .replace('Ş', 'S')
        .replace('Ğ', 'G')
        .replace('Ü', 'U')
        .replace('Ö', 'O')
        .replace('Ç', 'C')
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
}
