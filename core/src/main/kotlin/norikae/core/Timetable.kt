package norikae.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 種別。普通以外は「速い代わりに乗り過ごすと遠くまで行く」可能性がある */
enum class TrainType(val label: String, val isLocal: Boolean) {
    LOCAL("普通", true),
    SEMI_EXPRESS("準急", false),
    EXPRESS("急行", false),
    SPECIAL_RAPID("特快速", false),
    S_LIMITED("S特急", false),
    DIRECT_LIMITED("直通特急", false),
    OTHER("その他", false);

    companion object {
        fun fromLabel(label: String): TrainType =
            entries.firstOrNull { it.label == label.replace("Ｓ", "S") } ?: OTHER
    }
}

/** 山陽電車（荒井発）の1本 */
data class SanyoTrain(
    val dep: ServiceTime,
    val type: TrainType,
    val dest: String,
    /** 新開地着。途中で乗り換えが必要な列車は、乗り換え後の新開地着 */
    val shinkaichiArr: ServiceTime?,
    /** 途中で乗り換える駅（例: 山陽明石）。直通なら null */
    val transferAt: String?,
) {
    /** 乗り過ごすと梅田（阪神）まで行ってしまう列車か */
    val goesToUmeda: Boolean get() = type == TrainType.DIRECT_LIMITED || dest.contains("梅田")
}

/** 神戸電鉄（新開地発）の1本 */
data class ShintetsuTrain(
    val dep: ServiceTime,
    val type: TrainType,
    val dest: String,
    /** 北鈴蘭台着。北鈴蘭台に行かない列車（粟生線・鈴蘭台止まり）は null */
    val kitaSuzurandaiArr: ServiceTime?,
    /** 鈴蘭台で三田行きに乗り換えが必要か */
    val transferAtSuzurandai: Boolean,
    val note: String?,
) {
    val reachesKitaSuzurandai: Boolean get() = kitaSuzurandaiArr != null
}

data class UpTrain(val dep: ServiceTime, val dest: String)

data class DayTimetable(
    val sanyo: List<SanyoTrain>,
    val shintetsu: List<ShintetsuTrain>,
    val kitaSuzurandaiUp: List<UpTrain>,
)

data class Timetable(
    val transferMinutesAtShinkaichi: Int,
    /** 山陽の時刻表が未入力のときに使う、荒井→新開地の所要分 */
    val sanyoDefaultRunMinutes: Int?,
    val weekday: DayTimetable,
    val holiday: DayTimetable,
    val shintetsuRevision: String?,
) {
    fun forDay(dayType: DayType): DayTimetable = when (dayType) {
        DayType.WEEKDAY -> weekday
        DayType.HOLIDAY -> holiday
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Timetable = json.decodeFromString<TimetableJson>(text).toModel()
    }
}

// ---- JSON の形（data/timetable.json）----

@Serializable
private data class TimetableJson(
    val meta: MetaJson? = null,
    val transferMinutesAtShinkaichi: Int = 3,
    val sanyoDefaultRunMinutes: Int? = null,
    val sanyo: ByDay<SanyoJson> = ByDay(),
    val shintetsu: ByDay<ShintetsuJson> = ByDay(),
    val kitaSuzurandaiUp: ByDay<UpJson> = ByDay(),
) {
    fun toModel(): Timetable {
        fun build(weekday: Boolean): DayTimetable = DayTimetable(
            sanyo = (if (weekday) sanyo.weekday else sanyo.holiday).map { it.toModel() }.sortedBy { it.dep },
            shintetsu = (if (weekday) shintetsu.weekday else shintetsu.holiday).map { it.toModel() }.sortedBy { it.dep },
            kitaSuzurandaiUp = (if (weekday) kitaSuzurandaiUp.weekday else kitaSuzurandaiUp.holiday)
                .map { UpTrain(ServiceTime.parse(it.dep), it.dest) }.sortedBy { it.dep },
        )
        return Timetable(
            transferMinutesAtShinkaichi = transferMinutesAtShinkaichi,
            sanyoDefaultRunMinutes = sanyoDefaultRunMinutes,
            weekday = build(true),
            holiday = build(false),
            shintetsuRevision = meta?.shintetsuRevision,
        )
    }
}

@Serializable
private data class MetaJson(val shintetsuRevision: String? = null)

@Serializable
private data class ByDay<T>(val weekday: List<T> = emptyList(), val holiday: List<T> = emptyList())

@Serializable
private data class SanyoJson(
    val dep: String,
    val type: String,
    val dest: String,
    val shinkaichi: String? = null,
    val transferAt: String? = null,
) {
    fun toModel() = SanyoTrain(
        dep = ServiceTime.parse(dep),
        type = TrainType.fromLabel(type),
        dest = dest,
        shinkaichiArr = shinkaichi?.let(ServiceTime::parse),
        transferAt = transferAt,
    )
}

@Serializable
private data class ShintetsuJson(
    val dep: String,
    val type: String,
    val dest: String,
    @SerialName("kitaSuzurandai") val kitaSuzurandai: String? = null,
    val transferAtSuzurandai: Boolean = false,
    val note: String? = null,
) {
    fun toModel() = ShintetsuTrain(
        dep = ServiceTime.parse(dep),
        type = TrainType.fromLabel(type),
        dest = dest,
        kitaSuzurandaiArr = kitaSuzurandai?.let(ServiceTime::parse),
        transferAtSuzurandai = transferAtSuzurandai,
        note = note,
    )
}

@Serializable
private data class UpJson(val dep: String, val dest: String)
