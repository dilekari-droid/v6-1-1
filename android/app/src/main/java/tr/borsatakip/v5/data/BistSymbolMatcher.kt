package tr.borsatakip.v5.data

object BistSymbolMatcher {
    fun matches(
        symbols: Collection<String>,
        names: Map<String, String>,
        rawQuery: String,
        selected: Set<String> = emptySet(),
        limit: Int = 8
    ): List<String> {
        val q = rawQuery.trim().uppercase()
        if (q.isBlank()) return emptyList()
        return symbols.asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it !in selected }
            .distinct()
            .mapNotNull { symbol ->
                val name = names[symbol].orEmpty().uppercase()
                val score = when {
                    symbol == q -> 0
                    symbol.startsWith(q) -> 1
                    name.contains(q) -> 2
                    symbol.contains(q) -> 3
                    q.length >= 3 && levenshtein(symbol, q) <= 2 -> 4
                    else -> null
                }
                score?.let { Triple(it, kotlin.math.abs(symbol.length - q.length), symbol) }
            }
            .sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second }.thenBy { it.third })
            .take(limit)
            .map { it.third }
            .toList()
    }

    fun closest(symbols: Collection<String>, raw: String, limit: Int = 2): List<String> {
        val q = raw.trim().uppercase()
        if (q.length < 3) return emptyList()
        return symbols.asSequence().map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
            .map { it to levenshtein(it, q) }
            .filter { it.second <= 2 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit).map { it.first }.toList()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
