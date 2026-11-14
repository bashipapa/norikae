package norikae.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import norikae.core.RingKind

/**
 * アラームを鳴らしている間だけ動く常駐処理。音・振動（AlarmPlayer）を止められないようにする。
 * 止めるまで鳴り続けるが、念のため5分で自動的に止める（1分おきに鳴らし直すときは、そのたびに5分延びる）。
 */
class AlarmSoundService : Service() {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LEVEL = "level"
        private const val AUTO_STOP_MS = 5 * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { k -> RingKind.entries.firstOrNull { it.name == k } } ?: RingKind.WAKE
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: ""
        val level = intent?.getIntExtra(EXTRA_LEVEL, 1) ?: 1
        val notification = Ringer.buildNotification(this, kind, message)
        val started = runCatching {
            ServiceCompat.startForeground(this, Ringer.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
        if (started.isFailure) {
            TripLog.add(this, TripLog.Category.SYSTEM, "鳴動用の常駐処理を開始できなかった：${started.exceptionOrNull()?.javaClass?.simpleName}")
        }
        AlarmPlayer.start(this, level)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        AlarmPlayer.stop(this)
        super.onDestroy()
    }
}
