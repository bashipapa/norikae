package norikae.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import norikae.core.AlarmKind
import norikae.core.Motion
import norikae.core.TripEvent

/** 予約したアラームの時刻になった */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_RINGING = "norikae.app.STOP_RINGING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_RINGING) {
            Ringer.stop(context)
            return
        }
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND)?.let { AlarmKind.valueOf(it) } ?: return
        val (_, time) = TripController.now()
        TripController.handle(context, TripEvent.AlarmFired(kind, time))
    }
}

/** 乗り物/歩行/停止 の切り替わり（Activity Recognition Transition API） */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .mapNotNull {
                when (it.activityType) {
                    DetectedActivity.IN_VEHICLE -> Motion.IN_VEHICLE
                    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Motion.WALKING
                    DetectedActivity.STILL -> Motion.STILL
                    else -> null
                }
            }
            .forEach { TripController.onMotion(context, it) }
    }
}

/** 荒井駅の範囲を出た（自動開始用） */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val (date, time) = TripController.now()
        Store.saveLeftAraiAt(context, date, time)
    }
}

/** 再起動・アプリ更新のあとに、判定の登録とアラームを戻す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sensing.registerAll(context)
        TripController.restore(context)
    }
}
