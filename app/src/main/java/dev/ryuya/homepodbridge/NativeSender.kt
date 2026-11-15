package dev.ryuya.homepodbridge

import java.nio.ByteBuffer
import kotlin.jvm.JvmStatic

object NativeSender {
    init {
        System.loadLibrary("homepod_bridge")
    }

    @JvmStatic
    external fun start(
        host: String?,
        port: Int,
        name: String?,
        deviceId: String?,
        identity: String?,
        volumeLimit: Int,
        bufferMs: Int,
        latencyMs: Int,
        independentTiming: Boolean,
        receiverMinimumMs: Int,
        receiverMaximumMs: Int,
        packetFrames: Int,
        compressedAudio: Boolean,
        adaptiveBuffer: Boolean,
        networkPriority: Boolean,
        awaitCapture: Boolean,
    )

    @JvmStatic external fun write(pcm: ByteBuffer?, byteCount: Int): Boolean

    @JvmStatic external fun state(): Int

    @JvmStatic external fun timing(): IntArray?

    @JvmStatic external fun receiverRejection(): IntArray?

    @JvmStatic external fun network(): LongArray?

    @JvmStatic external fun preparedAtNanos(): Long

    @JvmStatic external fun inputReady()

    @JvmStatic external fun pipeline(): LongArray?

    @JvmStatic external fun volume(volume: Int)

    @JvmStatic external fun metadataStatus(): LongArray?

    @JvmStatic external fun instanceId(): Long

    // Text bytes are standard UTF-8, including non-BMP song names; never JNI modified UTF-8.
    @JvmStatic
    external fun nowPlaying(
        instance: Long,
        title: ByteArray,
        artist: ByteArray,
        album: ByteArray,
        artwork: ByteArray,
        positionMs: Long,
        durationMs: Long,
    )

    @JvmStatic external fun stop()

    @JvmStatic external fun diagnostic(): String?

    @JvmStatic external fun openZeroCodecProbe(): Boolean

    @JvmStatic external fun encodeZeroProbe(pcm: ByteBuffer?, frames: Int): LongArray?

    @JvmStatic external fun closeZeroCodecProbe()
}
