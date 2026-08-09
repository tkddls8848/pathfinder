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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(guardian: GuardianEntity)

    @Query("DELETE FROM guardian WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [SavedPlaceEntity::class, GuardianEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class EodigaDatabase : RoomDatabase() {
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun guardianDao(): GuardianDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EodigaDatabase =
        Room.databaseBuilder(context, EodigaDatabase::class.java, "eodiga.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun savedPlaceDao(db: EodigaDatabase): SavedPlaceDao = db.savedPlaceDao()

    @Provides fun guardianDao(db: EodigaDatabase): GuardianDao = db.guardianDao()
}
