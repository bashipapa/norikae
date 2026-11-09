package norikae.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import norikae.core.LatLon
import norikae.core.TripEvent

/**
 * 乗車中だけ動く常駐処理。位置を定期的に取り、TripEngine に渡す（乗り過ごし・粟生線の判定用）。
 * 地下（新開地付近）では位置が取れないことがあるが、その場合は時刻と乗り物/歩行の判定で動く。
 */
class TripService : Service() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val (_, time) = TripController.now()
            TripController.handle(this@TripService, TripEvent.LocationChanged(LatLon(loc.latitude, loc.longitude), time))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifier.ONGOING_ID,
                Notifier.ongoing(this, Store.trip(this)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }
        if (started.isFailure) {
            // 位置の権限が「使用中のみ」でバックグラウンドから起動した場合など。アラームは時刻どおり鳴る
            stopSelf()
            return START_NOT_STICKY
        }
        if (Sensing.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .build()
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    companion object {
        /**
         * 乗車中の位置取得を始める。
         * Android 14 以降、アプリが裏にいるときに位置の常駐処理を始めるには「常に許可」が必要
         * （無いと SecurityException）。その場合は位置なしで続ける（アラーム・乗り物/歩行の判定は動く）。
         */
        fun start(ctx: Context, fromForeground: Boolean) {
            if (!Sensing.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return
            if (!fromForeground && !Sensing.has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Notifier.info(ctx, "位置が「使用中のみ許可」のため、位置を使った乗り過ごし判定はお休みします（アラームは時刻どおり鳴ります）")
                return
            }
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java)) }
                .onFailure { Notifier.info(ctx, "位置の取得を開始できませんでした（アラームは時刻どおり鳴ります）") }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripService::class.java))
        }
    }
}
