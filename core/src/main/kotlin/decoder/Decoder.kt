package decoder

import converter.ByteArrayFrameConverter
import extension.suspend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.Media
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import javax.sound.sampled.AudioFormat
import kotlin.time.Duration.Companion.microseconds

interface Decoder : AutoCloseable {

    object Configuration {
        val sampleMode = FrameGrabber.SampleMode.SHORT
        const val audioCodec = avcodec.AV_CODEC_ID_AAC
        const val sampleFormat = avutil.AV_SAMPLE_FMT_S16
        const val sampleRate = 44100

        val imageMode = FrameGrabber.ImageMode.COLOR
        const val videoCodec = avcodec.AV_CODEC_ID_H264
        const val pixelFormat = avutil.AV_PIX_FMT_BGRA
    }

    val isInitialized: Boolean

    val media: Media?

    suspend fun initialize(url: String)

    suspend fun dispose()

    suspend fun snapshot(timestampMicros: Long): DecodedFrame.Video?

    suspend fun nextFrame(): DecodedFrame?

    suspend fun seekTo(timestampMicros: Long): Long?

    companion object {
        fun create(): Decoder = Implementation()

        fun createMedia(url: String) = runCatching {
            FFmpegFrameGrabber(url).apply {
                audioCodec = Configuration.audioCodec
                sampleMode = Configuration.sampleMode
                sampleFormat = Configuration.sampleFormat
                sampleRate = Configuration.sampleRate

                videoCodec = Configuration.videoCodec
                imageMode = Configuration.imageMode
                pixelFormat = Configuration.pixelFormat

                startUnsafe()
            }.use { infoGrabber ->
                with(infoGrabber) {
                    val durationNanos = lengthInTime.microseconds.inWholeNanoseconds

                    val audioFormat = if (hasAudio()) AudioFormat(
                        sampleRate.toFloat(),
                        avutil.av_get_bytes_per_sample(sampleFormat) * 8,
                        audioChannels,
                        true,
                        false
                    ) else null

                    val size = if (imageWidth > 0 && imageHeight > 0) imageWidth to imageHeight else null

                    val frameRate = videoFrameRate.coerceIn(0.0, 60.0)

                    val previewFrame = runCatching {
                        grabImage()?.use { frame ->
                            ByteArrayFrameConverter().use { converter ->
                                converter.convert(frame)?.let { bytes ->
                                    DecodedFrame.Video(frame.timestamp.microseconds.inWholeNanoseconds, bytes)
                                }
                            }
                        }
                    }.getOrNull()

                    Media(
                        url = url,
                        name = File(url).takeIf(File::exists)?.name,
                        durationNanos = durationNanos,
                        frameRate = frameRate,
                        audioFormat = audioFormat,
                        size = size,
                        previewFrame = previewFrame
                    )
                }
            }
        }.onFailure(Throwable::printStackTrace).getOrNull()
    }
}

private class Implementation : Decoder {

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        FFmpegLogCallback.set()
    }

    private val byteArrayFrameConverter by lazy { ByteArrayFrameConverter() }

    private val mediaMutex = Mutex()

    private val snapshotMutex = Mutex()

    private val initializationMutex = Mutex()

    private var mediaGrabber: FFmpegFrameGrabber? = null

    private var snapshotGrabber: FFmpegFrameGrabber? = null

    override var isInitialized = false
        private set

    override var media: Media? = null
        private set

    override suspend fun initialize(url: String) = initializationMutex.withLock {

        if (isInitialized) throw Exception("Decoder is already initialized")

        runCatching {
            mediaMutex.withLock {
                mediaGrabber = FFmpegFrameGrabber(url).apply {
                    audioCodec = Decoder.Configuration.audioCodec
                    sampleMode = Decoder.Configuration.sampleMode
                    sampleFormat = Decoder.Configuration.sampleFormat
                    sampleRate = Decoder.Configuration.sampleRate

                    videoCodec = Decoder.Configuration.videoCodec
                    imageMode = Decoder.Configuration.imageMode
                    pixelFormat = Decoder.Configuration.pixelFormat

                    startUnsafe()
                }.also { mediaGrabber ->

                    media = with(mediaGrabber) {
                        val mediaUrl = formatContext.url().string

                        val durationNanos = lengthInTime.microseconds.inWholeNanoseconds

                        val audioFormat = if (hasAudio()) AudioFormat(
                            sampleRate.toFloat(),
                            avutil.av_get_bytes_per_sample(sampleFormat) * 8,
                            audioChannels,
                            true,
                            false
                        ) else null

                        val size = if (imageWidth > 0 && imageHeight > 0) imageWidth to imageHeight else null

                        val frameRate = videoFrameRate.coerceIn(0.0, 60.0)

                        Media(
                            url = mediaUrl,
                            name = File(mediaUrl).takeIf(java.io.File::exists)?.name,
                            durationNanos = durationNanos,
                            frameRate = frameRate,
                            audioFormat = audioFormat,
                            size = size
                        )
                    }
                }
            }

            snapshotGrabber = snapshotMutex.withLock {
                FFmpegFrameGrabber(url).apply {
                    videoCodec = Decoder.Configuration.videoCodec
                    imageMode = Decoder.Configuration.imageMode
                    pixelFormat = Decoder.Configuration.pixelFormat

                    startUnsafe()
                }
            }

            isInitialized = true
        }
    }.onFailure(Throwable::printStackTrace).suspend()

    override suspend fun dispose() = initializationMutex.withLock {

        if (!isInitialized) throw Exception("Unable to dispose uninitialized decoder")

        runCatching {
            mediaMutex.withLock {
                mediaGrabber?.close()
                mediaGrabber = null
            }

            snapshotMutex.withLock {
                snapshotGrabber?.close()
                snapshotGrabber = null
            }

            media = null

            isInitialized = false
        }
    }.onFailure(Throwable::printStackTrace).suspend()

    override suspend fun snapshot(timestampMicros: Long) = snapshotMutex.withLock {
        snapshotGrabber?.runCatching {
            setTimestamp(timestampMicros, true)
            grabImage()?.use { frame ->
                byteArrayFrameConverter.convert(frame)?.let { bytes ->
                    DecodedFrame.Video(frame.timestamp.microseconds.inWholeNanoseconds, bytes)
                }
            }
        }
    }?.onFailure(Throwable::printStackTrace)?.suspend()

    override suspend fun nextFrame(): DecodedFrame? = mediaMutex.withLock {
        mediaGrabber?.runCatching {
            media?.run {
                when (val grabbedFrame = grabFrame(
                    hasAudio(), hasVideo(), true, false, false
                )) {
                    null -> DecodedFrame.End(durationNanos)
                    else -> grabbedFrame.use { frame ->
                        val timestampNanos = frame.timestamp.microseconds.inWholeNanoseconds
                        when (frame.type) {
                            Frame.Type.AUDIO -> byteArrayFrameConverter.convert(frame)
                                ?.let { bytes -> DecodedFrame.Audio(timestampNanos, bytes) }

                            Frame.Type.VIDEO -> byteArrayFrameConverter.convert(frame)
                                ?.let { bytes -> DecodedFrame.Video(timestampNanos, bytes) }

                            else -> null
                        }
                    }
                }
            }
        }
    }?.onFailure(Throwable::printStackTrace)?.suspend()

    override suspend fun seekTo(timestampMicros: Long) = mediaMutex.withLock {
        mediaGrabber?.runCatching {
            setTimestamp(timestampMicros, true)
            timestamp
        }
    }?.onFailure(Throwable::printStackTrace)?.suspend()

    override fun close() {
        mediaGrabber?.close()
        mediaGrabber = null

        snapshotGrabber?.close()
        snapshotGrabber = null

        byteArrayFrameConverter.close()
    }
}