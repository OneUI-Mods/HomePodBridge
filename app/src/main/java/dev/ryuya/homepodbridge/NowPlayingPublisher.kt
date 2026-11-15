package dev.ryuya.homepodbridge

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** A connection-owned reader. Bitmap work and all Binder reads run away from audio and UI. */
internal class NowPlayingPublisher(
    context: Context,
    private val lifetime: IBinder,
    private val instance: Long,
) : AutoCloseable {
    private val context = context.applicationContext
    private val thread = HandlerThread("HomePod-now-playing").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private var controller: MediaController? = null
    private var title = ""
    private var artist = ""
    private var album = ""
    private var cover = byteArrayOf()
    private var duration = -1L
    private var published = false
    private var pendingMetadata: MediaMetadata? = null
    private val refreshMetadata = Runnable { updateMetadata(pendingMetadata) }
    private var callback: MediaController.Callback? = null
    private val poll =
        object : Runnable {
            override fun run() {
                if (closed.get()) return
                try {
                    bind(RootConnector.readMediaSession(lifetime).token)
                    if (controller != null) publish(controller?.playbackState)
                } catch (_: RuntimeException) {
                    bind(null)
                }
                if (!closed.get()) handler.postDelayed(this, 2000)
            }
        }

    init {
        handler.post(poll)
    }

    private fun bind(token: MediaSession.Token?) {
        if (closed.get() || token == controller?.sessionToken) return
        callback?.let { cb -> runCatching { controller?.unregisterCallback(cb) } }
        callback = null
        controller = null
        handler.removeCallbacks(refreshMetadata)
        pendingMetadata = null
        if (token != null) {
            val next = MediaController(context, token)
            controller = next
            val cb =
                object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (controller === next && !closed.get()) {
                            pendingMetadata = metadata
                            handler.removeCallbacks(refreshMetadata)
                            handler.postDelayed(refreshMetadata, 250)
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controller === next) publish(state)
                    }

                    override fun onSessionDestroyed() {
                        if (controller === next) bind(null)
                    }
                }
            callback = cb
            next.registerCallback(cb, handler)
            updateMetadata(next.metadata)
        } else updateMetadata(null)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (closed.get()) return
        fun text(vararg keys: String): String =
            keys
                .firstNotNullOfOrNull { key ->
                    metadata?.getText(key)?.toString()?.takeIf { it.isNotBlank() }
                }
                ?.take(1024) ?: ""
        title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        artist =
            text(
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            )
        album = text(MediaMetadata.METADATA_KEY_ALBUM)
        duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1L
        cover = artwork(metadata)
        publish(controller?.playbackState)
    }

    private fun artwork(metadata: MediaMetadata?): ByteArray {
        if (metadata == null) return byteArrayOf()
        try {
            val original =
                sequenceOf(
                        MediaMetadata.METADATA_KEY_ART,
                        MediaMetadata.METADATA_KEY_ALBUM_ART,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
                    )
                    .mapNotNull { metadata.getBitmap(it) }
                    .firstOrNull() ?: return byteArrayOf()
            if (original.isRecycled || original.width <= 0 || original.height <= 0)
                return byteArrayOf()
            val ratio = minOf(1.0, 512.0 / maxOf(original.width, original.height))
            val scaled =
                Bitmap.createScaledBitmap(
                    original,
                    maxOf(1, (original.width * ratio).roundToInt()),
                    maxOf(1, (original.height * ratio).roundToInt()),
                    true,
                )
            try {
                val bytes = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) return byteArrayOf()
                return bytes.toByteArray().takeIf { it.size <= 256 * 1024 } ?: byteArrayOf()
            } finally {
                if (scaled !== original) scaled.recycle()
            }
        } catch (_: RuntimeException) {
            return byteArrayOf()
        }
    }

    private fun publish(state: PlaybackState?) {
        if (closed.get()) return
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty() && cover.isEmpty() && !published)
            return
        val position =
            NowPlayingPosition.estimate(
                state?.position ?: -1L,
                state?.lastPositionUpdateTime ?: 0L,
                SystemClock.elapsedRealtime(),
                state?.playbackSpeed ?: 0f,
                state?.state == PlaybackState.STATE_PLAYING,
                duration,
            )
        NativeSender.nowPlaying(
            instance,
            title.toByteArray(Charsets.UTF_8),
            artist.toByteArray(Charsets.UTF_8),
            album.toByteArray(Charsets.UTF_8),
            cover,
            position,
            duration,
        )
        published = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                callback?.let { controller?.unregisterCallback(it) }
            } finally {
                controller = null
                cover = byteArrayOf()
                thread.quitSafely()
            }
        }
    }
}
