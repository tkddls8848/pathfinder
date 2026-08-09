package kr.eodiga.wayfinder.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.eodiga.wayfinder.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 기기에 저장된 노래 목록.
 *
 * 스트리밍 서비스를 붙이지 않는다. 계정·결제·네트워크가 한꺼번에 따라오는데
 * 셋 다 어르신이 스스로 복구할 수 없는 실패 지점이다. 기기에 이미 들어 있는
 * 음원만 읽으면 그 셋이 전부 사라지고, 버스 안에서 데이터가 끊겨도 계속 나온다.
 *
 * 목록을 넣어주는 것은 대개 보호자다. 그래서 이 클래스는 "고르는" 기능을 두지 않고
 * 기기에 있는 것을 그대로 제목순으로 돌려준다.
 */
@Singleton
class MusicLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 읽기 권한. API 33 에서 저장소 권한이 매체별로 쪼개졌다.
     * 낮은 기기에 새 권한을 물으면 시스템이 그냥 거부하므로 버전으로 갈라야 한다.
     */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    val hasPermission: Boolean
        get() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** 제목순 노래 목록. 권한이 없거나 조회가 실패하면 빈 목록이다. */
    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext emptyList()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
        )
        // IS_MUSIC 만으로는 부족하다. 짧은 파일을 걸러내지 않으면 알림음·벨소리가
        // 목록에 섞여 어르신이 "노래" 를 찾지 못한다.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(MIN_DURATION_MS.toString())
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, args, order)
                .use { cursor ->
                    if (cursor == null) return@runCatching emptyList()
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                                ?: continue
                            add(
                                Track(
                                    id = id,
                                    title = title,
                                    // MediaStore 는 가수를 모를 때 이 문자열을 그대로 넣는다.
                                    // 화면에 "<unknown>" 이 뜨면 고장으로 보인다.
                                    artist = cursor.getString(artistCol)
                                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING },
                                    uri = ContentUris.withAppendedId(collection, id),
                                ),
                            )
                        }
                    }
                }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** 이보다 짧으면 노래가 아니라 알림음으로 본다. */
        const val MIN_DURATION_MS = 30_000
    }
}
