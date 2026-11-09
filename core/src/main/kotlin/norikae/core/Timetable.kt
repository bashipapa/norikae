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
    LIMITED("特急", false),
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
    /** 乗り換え駅の着・発時刻 */
    val transferArr: ServiceTime? = null,
    val transferDep: ServiceTime? = null,
    /** 乗り換え先の列車の種別・行先（乗り換えがあるとき） */
    val transferTrainType: TrainType? = null,
    val transferTrainDest: String? = null,
    /** 乗り換えの説明（例: 東二見 22:48着 → 22:53発 直通特急 大阪梅田行きに乗り換え） */
    val transferNote: String? = null,
) {
    /** 新開地に着く列車（乗り換えがあれば乗り換え後の列車）の種別 */
    val arrivingType: TrainType get() = transferTrainType ?: type

    /** 新開地に着く列車の行先 */
    val arrivingDest: String get() = transferTrainDest ?: dest

    /** 乗り過ごすと梅田（阪神）まで行ってしまうか */
    val goesToUmeda: Boolean
        get() = arrivingType == TrainType.DIRECT_LIMITED || arrivingDest.contains("梅田")

    /** 普通だけで新開地まで行けるか */
    val isLocalOnly: Boolean get() = type.isLocal && (transferTrainType?.isLocal ?: true)
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

/** 平日の帰り道の時刻表（21時以降〜終電） */
data class Timetable(
    val transferMinutesAtShinkaichi: Int,
    /** 山陽の時刻表が空のときに使う、荒井→新開地の所要分 */
    val sanyoDefaultRunMinutes: Int?,
    val sanyo: List<SanyoTrain>,
    val shintetsu: List<ShintetsuTrain>,
    val kitaSuzurandaiUp: List<UpTrain>,
    val shintetsuRevision: String?,
    /** 駅の位置の初期値（アプリで「今いる場所を登録」すると上書きされる） */
    val stations: Map<StationId, LatLon> = emptyMap(),
) {
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
    val stations: Map<String, StationJson> = emptyMap(),
    val sanyo: WeekdayOnly<SanyoJson> = WeekdayOnly(),
    val shintetsu: WeekdayOnly<ShintetsuJson> = WeekdayOnly(),
    val kitaSuzurandaiUp: WeekdayOnly<UpJson> = WeekdayOnly(),
) {
    fun toModel() = Timetable(
        transferMinutesAtShinkaichi = transferMinutesAtShinkaichi,
        sanyoDefaultRunMinutes = sanyoDefaultRunMinutes,
        sanyo = sanyo.weekday.map { it.toModel() }.sortedBy { it.dep },
        shintetsu = shintetsu.weekday.map { it.toModel() }.sortedBy { it.dep },
        kitaSuzurandaiUp = kitaSuzurandaiUp.weekday.map { UpTrain(ServiceTime.parse(it.dep), it.dest) }.sortedBy { it.dep },
        shintetsuRevision = meta?.shintetsuRevision,
        stations = stations.mapNotNull { (key, v) ->
            StationId.entries.firstOrNull { it.name == key }?.let { it to LatLon(v.lat, v.lon) }
        }.toMap(),
    )
}

@Serializable
private data class StationJson(val name: String? = null, val lat: Double, val lon: Double)

@Serializable
private data class MetaJson(val shintetsuRevision: String? = null)

/** JSON では "weekday" の下に並べる（平日専用） */
@Serializable
private data class WeekdayOnly<T>(val weekday: List<T> = emptyList())

@Serializable
private data class SanyoJson(
    val dep: String,
    val type: String,
    val dest: String,
    val shinkaichi: String? = null,
    val transferAt: String? = null,
    val transferArr: String? = null,
    val transferDep: String? = null,
    val transferTrainType: String? = null,
    val transferTrainDest: String? = null,
    val transferNote: String? = null,
) {
    fun toModel() = SanyoTrain(
        dep = ServiceTime.parse(dep),
        type = TrainType.fromLabel(type),
        dest = dest,
        shinkaichiArr = shinkaichi?.let(ServiceTime::parse),
        transferAt = transferAt,
        transferArr = transferArr?.let(ServiceTime::parse),
        transferDep = transferDep?.let(ServiceTime::parse),
        transferTrainType = transferTrainType?.let(TrainType::fromLabel),
        transferTrainDest = transferTrainDest,
        transferNote = transferNote,
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
