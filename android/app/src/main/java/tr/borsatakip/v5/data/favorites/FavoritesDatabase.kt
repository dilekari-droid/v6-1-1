package tr.borsatakip.v5.data.favorites

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FavoriteStock::class], version = 3, exportSchema = false)
abstract class FavoritesDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var INSTANCE: FavoritesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_stocks_new (
                        symbol TEXT NOT NULL,
                        displayName TEXT,
                        market TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(symbol, market)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO favorite_stocks_new(symbol, displayName, market, createdAt)
                    SELECT symbol, displayName,
                           CASE WHEN UPPER(market) IN ('VIOP', 'VİOP') THEN 'VIOP' ELSE 'BIST' END,
                           createdAt
                    FROM favorite_stocks
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE favorite_stocks")
                db.execSQL("ALTER TABLE favorite_stocks_new RENAME TO favorite_stocks")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_stocks ADD COLUMN analysisIntervalMinutes INTEGER")
            }
        }

        fun get(context: Context): FavoritesDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                FavoritesDatabase::class.java,
                "borsa_favorites.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
