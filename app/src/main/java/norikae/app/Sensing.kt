package norikae.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import norikae.core.Origin

/**
 * 常時登録しておく判定（電池消費が少ないもの）。
 * - 乗り物/歩行/停止 の切り替わり
 * - 乗る駅（荒井・山陽明石）の範囲から出たこと（ジオフェンス）
 */
object Sensing {
    /** 以前の版で使っていた荒井のジオフェンスの ID（消すため） */
    private const val OLD_ARAI_ID = "arai"
    private const val ORIGIN_RADIUS_M = 300f

    fun has(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun registerAll(ctx: Context) {
        registerMotion(ctx)
        registerOriginGeofences(ctx)
    }

    @SuppressLint("MissingPermission")
    fun registerMotion(ctx: Context) {
        if (!has(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) return
        val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.WALKING, DetectedActivity.STILL)
        val transitions = types.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), motionIntent(ctx))
    }

    /** 乗る駅（荒井・山陽明石）の範囲を出たことを検知するジオフェンス。requestId は Origin の名前 */
    @SuppressLint("MissingPermission")
    fun registerOriginGeofences(ctx: Context) {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (!has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        val stations = Store.stations(ctx)
        val geofences = Origin.entries.mapNotNull { origin ->
            val loc = stations[origin.station] ?: return@mapNotNull null
            Geofence.Builder()
                .setRequestId(origin.name)
                .setCircularRegion(loc.lat, loc.lon, ORIGIN_RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        if (geofences.isEmpty()) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()
        val client = LocationServices.getGeofencingClient(ctx)
        val ids = Origin.entries.map { it.name } + OLD_ARAI_ID
        client.removeGeofences(ids).addOnCompleteListener {
            client.addGeofences(request, geofenceIntent(ctx))
        }
    }

    // ジオフェンスと Activity Recognition は OS が結果を書き込むので MUTABLE が必要
    private fun motionIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 200, Intent(ctx, MotionReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun geofenceIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 201, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
