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
import norikae.core.StationId

/**
 * 常時登録しておく判定（電池消費が少ないもの）。
 * - 乗り物/歩行/停止 の切り替わり
 * - 荒井駅の範囲から出たこと（ジオフェンス）
 */
object Sensing {
    private const val ARAI_GEOFENCE_ID = "arai"
    private const val ARAI_RADIUS_M = 300f

    fun has(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun registerAll(ctx: Context) {
        registerMotion(ctx)
        registerAraiGeofence(ctx)
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

    @SuppressLint("MissingPermission")
    fun registerAraiGeofence(ctx: Context) {
        val arai = Store.stations(ctx)[StationId.ARAI] ?: return
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (!has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        val geofence = Geofence.Builder()
            .setRequestId(ARAI_GEOFENCE_ID)
            .setCircularRegion(arai.lat, arai.lon, ARAI_RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        val client = LocationServices.getGeofencingClient(ctx)
        client.removeGeofences(listOf(ARAI_GEOFENCE_ID)).addOnCompleteListener {
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
