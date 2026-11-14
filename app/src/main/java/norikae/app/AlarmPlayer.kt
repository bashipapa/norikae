package norikae.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * アラームの音と振動をアプリ自身で出す（Google の時計アプリと同じ考え方）。
 * - 鳴っている間はアラーム音量を最大にし、止めたら元の音量に戻す
 * - level 0：振動だけ（新開地に着くまでの起こし）、level 1：端末の既定のアラーム音、level 2以上：サイレン（2つの高さの音を交互に）
 * - 最大の強さの振動を途切れず続ける
 * 音も振動も「アラーム」の用途で出すので、通知のクールダウンや通知の音の設定の影響を受けにくい。
 */
object AlarmPlayer {
    private var player: MediaPlayer? = null
    private var siren: AudioTrack? = null
    private var savedVolume: Int? = null
    private var playingLevel = 0

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Synchronized
    fun start(ctx: Context, level: Int) {
        vibrate(ctx)
        // level 0 は振動だけ（新開地に着くまでの起こし）。音量も変えない
        if (level <= 0) return
        maxVolume(ctx)
        // すでに同じ強さ以上で鳴っていれば鳴らし直さない（1分おきの再鳴動で音が途切れないように）
        if (playingLevel >= level && (player != null || siren != null)) return
        stopSound()
        playingLevel = level
        if (level >= 2 || !playAlarmTone(ctx)) playSiren()
    }

    @Synchronized
    fun stop(ctx: Context) {
        stopSound()
        playingLevel = 0
        vibrator(ctx)?.cancel()
        restoreVolume(ctx)
    }

    private fun stopSound() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { siren?.stop() }
        runCatching { siren?.release() }
        siren = null
    }

    // ---- 音量 ----

    private fun maxVolume(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (savedVolume == null) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    private fun restoreVolume(ctx: Context) {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { ctx.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    // ---- 音 ----

    private fun playAlarmTone(ctx: Context): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(alarmAttributes)
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.isSuccess
    }

    /** サイレン：960Hz と 770Hz を 0.4秒ずつ交互に、くり返し鳴らす */
    private fun playSiren() {
        val rate = 44_100
        val half = (rate * 0.4).toInt()
        val samples = ShortArray(half * 2)
        for (i in samples.indices) {
            val freq = if (i < half) 960.0 else 770.0
            // 矩形波に近い形で、耳につきやすい音にする
            val s = sin(2 * PI * freq * i / rate)
            samples[i] = ((if (s >= 0) 0.9 else -0.9) * Short.MAX_VALUE).toInt().toShort()
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(alarmAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setLoopPoints(0, samples.size, -1)
            track.play()
            siren = track
        }
    }

    // ---- 振動 ----

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    /** 最大の強さで 1秒振動・0.2秒休み をくり返す */
    private fun vibrate(ctx: Context) {
        val vib = vibrator(ctx) ?: return
        val timings = longArrayOf(0, 1000, 200)
        val amplitudes = intArrayOf(0, 255, 0)
        val effect = if (vib.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, alarmAttributes)
            }
        }
    }
}
