package kr.eodiga.wayfinder.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.PlaceKind
import javax.inject.Singleton

/**
 * 즐겨찾기 목적지.
 *
 * 어르신이 직접 등록하는 일은 거의 없다. 보호자가 설정 화면에서 등록하거나,
 * 자주 간 곳이 자동으로 승격된다. [pinnedOrder] 가 있으면 홈 화면 큰 버튼이 된다.
 */
@Entity(tableName = "saved_place")
data class SavedPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val kind: String,
    val pinnedOrder: Int?,
    val visitCount: Int,
    val lastVisitedAt: Long,
) {
    fun toDomain() = Place(
        id = id,
        name = name,
        address = address,
        location = LatLng(lat, lng),
        kind = runCatching { PlaceKind.valueOf(kind) }.getOrDefault(PlaceKind.OTHER),
    )

    companion object {
        fun from(place: Place, pinnedOrder: Int? = null, visitCount: Int = 0, lastVisitedAt: Long = 0) =
            SavedPlaceEntity(
                id = place.id,
                name = place.name,
                address = place.address,
                lat = place.location.lat,
                lng = place.location.lng,
                kind = place.kind.name,
                pinnedOrder = pinnedOrder,
                visitCount = visitCount,
                lastVisitedAt = lastVisitedAt,
            )
    }
}

/** 보호자 연락처. 길을 잃었을 때 전화 + 위치 문자를 보낼 대상. */
@Entity(tableName = "guardian")
data class GuardianEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    /** 여러 명이면 낮은 값부터 시도한다. */
    val priority: Int,
)

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_place WHERE pinnedOrder IS NOT NULL ORDER BY pinnedOrder ASC")
    fun pinned(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_place ORDER BY lastVisitedAt DESC LIMIT :limit")
    fun recent(limit: Int = 8): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_place WHERE id = :id")
    suspend fun byId(id: String): SavedPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: SavedPlaceEntity)

    @Query("UPDATE saved_place SET visitCount = visitCount + 1, lastVisitedAt = :now WHERE id = :id")
    suspend fun markVisited(id: String, now: Long)

    @Query("DELETE FROM saved_place WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GuardianDao {
    @Query("SELECT * FROM guardian ORDER BY priority ASC")
    fun all(): Flow<List<GuardianEntity>>

    @Query("SELECT * FROM guardian ORDER BY priority ASC LIMIT 1")
    suspend fun primary(): GuardianEntity?

    /**
     * 다음 우선순위. 화면이 들고 있는 목록 크기로 매기면 저장이 반영되기 전에
     * 연달아 추가할 때 같은 값이 붙고, 그러면 [primary] 가 누구를 고를지
     * 알 수 없게 된다. 급할 때 전화가 갈 대상이라 흔들리면 안 된다.
     */
    @Query("SELECT COALESCE(MAX(priority), -1) + 1 FROM guardian")
    suspend fun nextPriority(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(guardian: GuardianEntity)

    @Query("DELETE FROM guardian WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [SavedPlaceEntity::class, GuardianEntity::class],
    version = 2,
    // 스키마를 파일로 남겨야 다음 마이그레이션을 검증할 수 있다.
    // app/schemas/ 에 쌓이며 커밋 대상이다.
    exportSchema = true,
)
abstract class EodigaDatabase : RoomDatabase() {
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun guardianDao(): GuardianDao
}

/**
 * 1 → 2: `saved_place` 에서 쓰지 않게 된 `phone` 컬럼을 걷어낸다.
 *
 * 예전에는 마이그레이션 없이 [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]
 * 로 넘겼는데, 그러면 스키마가 바뀔 때마다 **보호자 연락처가 통째로 사라진다.**
 * 어르신은 이것을 다시 등록할 수 없고(설정 화면의 대상은 보호자다),
 * 연락처가 빈 채로 "길을 잃었어요" 만 동작하지 않는 상태가 된다.
 * 앱이 조용히 무력해지는 가장 나쁜 경로라 마이그레이션을 직접 쓴다.
 *
 * SQLite 의 DROP COLUMN 은 3.35(안드로이드 14) 부터라 쓸 수 없다.
 * 새 표를 만들어 옮기고 바꿔 끼우는, 어느 버전에서나 되는 방식을 쓴다.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_place_new` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `address` TEXT,
                `lat` REAL NOT NULL,
                `lng` REAL NOT NULL,
                `kind` TEXT NOT NULL,
                `pinnedOrder` INTEGER,
                `visitCount` INTEGER NOT NULL,
                `lastVisitedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `saved_place_new`
                (`id`, `name`, `address`, `lat`, `lng`, `kind`, `pinnedOrder`, `visitCount`, `lastVisitedAt`)
            SELECT `id`, `name`, `address`, `lat`, `lng`, `kind`, `pinnedOrder`, `visitCount`, `lastVisitedAt`
            FROM `saved_place`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `saved_place`")
        db.execSQL("ALTER TABLE `saved_place_new` RENAME TO `saved_place`")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EodigaDatabase =
        Room.databaseBuilder(context, EodigaDatabase::class.java, "eodiga.db")
            .addMigrations(MIGRATION_1_2)
            // 다운그레이드(옛 APK 재설치)는 되돌릴 경로 자체가 없다. 이때만 새로 만든다.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun savedPlaceDao(db: EodigaDatabase): SavedPlaceDao = db.savedPlaceDao()

    @Provides fun guardianDao(db: EodigaDatabase): GuardianDao = db.guardianDao()
}
