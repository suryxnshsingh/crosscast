package dev.suryansh.airspeaker.core.meta

import android.os.SystemClock
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.NowPlayingState

/** Turns SET_PARAMETER bodies into [NowPlayingState] updates; track progress goes to [onProgress]. */
class MetadataHandler(
    private val update: ((NowPlayingState) -> NowPlayingState) -> Unit,
    private val onProgress: (Progress) -> Unit,
) {
    private var lastArtworkAtMs = 0L

    fun handle(contentType: String?, body: ByteArray) {
        when {
            contentType == "application/x-dmap-tagged" -> onDmap(body)
            contentType == "text/parameters" -> onTextParameters(String(body, Charsets.UTF_8))
            contentType == "image/none" -> setArtwork(null, "none")
            contentType?.startsWith("image/") == true -> onImage(contentType, body)
        }
    }

    private fun onDmap(body: ByteArray) {
        val track = TrackInfo.from(Dmap.parse(body))
        if (track.title == null && track.artist == null && track.album == null) return
        Logs.meta.i("track \"${track.title}\" by ${track.artist} on ${track.album}")
        // Artwork for a new track can arrive just before its text; keep it if it's that fresh.
        val keepArtwork = SystemClock.elapsedRealtime() - lastArtworkAtMs < ARTWORK_GRACE_MS
        update { state ->
            val changed = state.title != track.title || state.artist != track.artist || state.album != track.album
            state.copy(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs ?: state.durationMs,
                artwork = if (changed && !keepArtwork) null else state.artwork,
            )
        }
    }

    private fun onTextParameters(text: String) {
        for (line in text.lineSequence()) {
            if (line.substringBefore(':').trim() != "progress") continue
            val progress = Progress.parse(line.substringAfter(':')) ?: continue
            Logs.meta.i("progress ${clock(progress.positionMs)} / ${clock(progress.durationMs)}")
            onProgress(progress)
        }
    }

    private fun onImage(contentType: String, body: ByteArray) {
        if (body.isEmpty()) return setArtwork(null, "empty")
        val bitmap = Artwork.decode(body)
        if (bitmap == null) {
            Logs.meta.w("could not decode $contentType artwork (${body.size} bytes)")
            return
        }
        setArtwork(bitmap, "${bitmap.width}×${bitmap.height} from ${body.size / 1024} KB $contentType")
    }

    private fun setArtwork(bitmap: android.graphics.Bitmap?, description: String) {
        lastArtworkAtMs = SystemClock.elapsedRealtime()
        Logs.meta.i("artwork $description")
        update { it.copy(artwork = bitmap) }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val ARTWORK_GRACE_MS = 1_000L
    }
}
