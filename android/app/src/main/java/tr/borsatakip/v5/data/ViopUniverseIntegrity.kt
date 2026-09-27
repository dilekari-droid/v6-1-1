package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.ViopUniverseCompleteness

/** Pure, fail-closed universe accounting used by BackendProvider and unit tests. */
object ViopUniverseIntegrity {
    data class Assessment(
        val completeness: ViopUniverseCompleteness,
        val reason: String
    )

    fun assess(providerTotal: Int?, fetchedUniqueCount: Int, hasMore: Boolean): Assessment = when {
        fetchedUniqueCount < 0 -> Assessment(ViopUniverseCompleteness.INCOMPLETE, "Alınan benzersiz kontrat sayısı geçersiz.")
        hasMore -> Assessment(ViopUniverseCompleteness.INCOMPLETE, "Sağlayıcı daha fazla sayfa olduğunu bildiriyor.")
        providerTotal == null -> Assessment(ViopUniverseCompleteness.UNVERIFIED, "Sağlayıcı totalCount vermedi; tam evren doğrulanamıyor.")
        providerTotal < 0 -> Assessment(ViopUniverseCompleteness.INCOMPLETE, "Sağlayıcı totalCount değeri geçersiz.")
        providerTotal != fetchedUniqueCount -> Assessment(
            ViopUniverseCompleteness.INCOMPLETE,
            "Provider toplamı $providerTotal fakat alınan benzersiz kayıt $fetchedUniqueCount."
        )
        else -> Assessment(ViopUniverseCompleteness.COMPLETE, "Provider toplamı ile benzersiz alınan kontrat sayısı eşleşti.")
    }
}
