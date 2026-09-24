package sa.gheras.edutrack.ui.teacher.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMillis: Long = 0

    val isRecording: Boolean
        get() = recorder != null

    fun startRecording(outputFile: File): Boolean {
        return try {
            stopRecording()
            currentFile = outputFile

            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            recorder = r
            startTimeMillis = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            false
        }
    }

    fun stopRecording(): Int {
        val durationSeconds = if (startTimeMillis > 0) {
            ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt().coerceAtLeast(1)
        } else 0

        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            recorder?.release()
            recorder = null
            startTimeMillis = 0
        }
        return durationSeconds
    }

    fun release() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        } finally {
            recorder = null
        }
    }
}

class AudioPlayerHelper {
    private var player: MediaPlayer? = null
    private var currentUri: String? = null

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    fun play(uri: String, onCompletion: () -> Unit = {}): Boolean {
        return try {
            if (currentUri == uri && player != null) {
                player?.start()
                return true
            }

            stop()
            currentUri = uri
            val p = MediaPlayer()
            p.setDataSource(uri)
            p.prepare()
            p.setOnCompletionListener {
                onCompletion()
            }
            p.start()
            player = p
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun pause() {
        try {
            if (player?.isPlaying == true) {
                player?.pause()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        } finally {
            player = null
            currentUri = null
        }
    }

    fun getDuration(): Int {
        return try {
            player?.duration ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            player?.currentPosition ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun release() {
        stop()
    }
}
