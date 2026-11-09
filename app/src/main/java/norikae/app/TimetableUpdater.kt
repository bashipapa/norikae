package norikae.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import norikae.core.Timetable
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 時刻表の自動更新。
 * GitHub の data/timetable.json（GitHub Actions が毎週 Yahoo!路線情報から更新している）を週1回ダウンロードし、
 * 端末に保存する。APK を入れ直さなくても新しい時刻表で動く。
 */
object TimetableUpdater {
    const val URL_TIMETABLE = "https://raw.githubusercontent.com/bashipapa/norikae/main/data/timetable.json"
    private const val WORK_PERIODIC = "timetable-weekly"
    private const val WORK_NOW = "timetable-now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 週1回の自動更新を登録する（登録済みならそのまま） */
    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<TimetableUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 設定画面の「今すぐ更新」 */
    fun updateNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<TimetableUpdateWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** ダウンロードして保存する。戻り値: 時刻表が変わったか */
    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(URL_TIMETABLE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            useCaches = false
        }
        val text = try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        // 読めない・中身が空の時刻表は保存しない
        val parsed = Timetable.parse(text)
        check(parsed.shintetsu.isNotEmpty() && parsed.sanyo.isNotEmpty()) { "時刻表が空です" }
        Store.saveCheckedAt(ctx, LocalDate.now())
        Store.saveDownloadedTimetable(ctx, text)
    }
}

class TimetableUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val changed = TimetableUpdater.download(applicationContext)
        if (changed) {
            Notifier.info(applicationContext, "時刻表を更新しました（${Store.timetable(applicationContext).retrievedAt ?: ""} 取得分）")
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
