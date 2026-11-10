package norikae.app

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 乗車ログ。アプリが何を検知し、何を判定し、何を鳴らしたかを端末内のテキストファイルに残す。
 * 「昨日ちゃんと鳴った？」を後から確認するため、判定のしきい値を調整するため。
 * 端末の外には送らない（共有ボタンで自分から送るときだけ）。
 */
object TripLog {
    private const val FILE_NAME = "triplog.txt"
    /** これを超えたら古い行から消す */
    private const val MAX_LINES = 3000
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    enum class Category(val label: String) {
        START("開始"),
        AUTO("自動開始"),
        MOTION("動き"),
        PLACE("位置"),
        STATE("状態"),
        ALARM("アラーム"),
        RING("鳴動"),
        USER("操作"),
        NOTICE("通知"),
        SYSTEM("システム"),
        TIMETABLE("時刻表"),
    }

    data class Entry(val time: String, val category: String, val message: String) {
        val date: String get() = time.take(10)
        val clock: String get() = time.drop(11)
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun add(ctx: Context, category: Category, message: String) {
        val line = listOf(
            LocalDateTime.now().format(TS),
            category.label,
            message.replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")
        runCatching {
            val f = file(ctx)
            f.appendText(line + "\n")
            // たまに古い行を削る（毎回読むと重いので、ファイルが大きいときだけ）
            if (f.length() > MAX_LINES * 120L) {
                val lines = f.readLines()
                if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** 新しい順 */
    @Synchronized
    fun read(ctx: Context): List<Entry> = runCatching {
        file(ctx).readLines().mapNotNull { line ->
            val p = line.split("\t", limit = 3)
            if (p.size == 3) Entry(p[0], p[1], p[2]) else null
        }.asReversed()
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /** 共有用のテキスト（[days] 日分、古い順） */
    fun shareText(ctx: Context, days: Int = 7): String {
        val since = LocalDateTime.now().minusDays(days.toLong()).format(TS)
        return read(ctx).asReversed()
            .filter { it.time >= since }
            .joinToString("\n") { "${it.time} [${it.category}] ${it.message}" }
    }
}
