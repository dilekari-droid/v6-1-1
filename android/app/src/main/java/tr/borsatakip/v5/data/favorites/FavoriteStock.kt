package tr.borsatakip.v5.data.favorites

import androidx.room.Entity

@Entity(
    tableName = "favorite_stocks",
    primaryKeys = ["symbol", "market"]
)
data class FavoriteStock(
    val symbol: String,
    val displayName: String? = null,
    val market: String = "BIST",
    /** Favoriye eklenirken ilişkili analiz hangi dakikalık periyotta üretildiyse onu korur. */
    val analysisIntervalMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
