package tr.borsatakip.v5.data.favorites

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_stocks ORDER BY createdAt DESC")
    suspend fun getAll(): List<FavoriteStock>

    @Query("SELECT * FROM favorite_stocks WHERE market = :market ORDER BY createdAt DESC")
    suspend fun getByMarket(market: String): List<FavoriteStock>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_stocks WHERE symbol = :symbol AND market = :market)")
    suspend fun contains(symbol: String, market: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteStock)

    @Query("DELETE FROM favorite_stocks WHERE symbol = :symbol AND market = :market")
    suspend fun delete(symbol: String, market: String)
}
