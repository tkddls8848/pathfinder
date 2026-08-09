package kr.eodiga.wayfinder.domain.model

import android.net.Uri

/**
 * 기기에 들어 있는 노래 한 곡.
 *
 * 스트리밍을 쓰지 않는다. 버스 안에서 데이터가 끊기면 음악도 같이 끊기는데,
 * 그때 어르신은 앱이 고장난 것으로 받아들인다. 기기 음원만 다루면
 * 네트워크·저작권·계정이 한꺼번에 사라진다.
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val uri: Uri,
) {
    /** 화면과 낭독에 함께 쓰는 한 줄. 가수를 모르면 제목만 말한다. */
    val label: String
        get() = if (artist.isNullOrBlank()) title else "$title · $artist"
}
