package norikae.core

enum class AlarmKind {
    /** 新開地の手前の予告 */
    SHINKAICHI_PRE,
    /** 新開地 到着直前 */
    SHINKAICHI_MAIN,
    /** 新開地で降りたかの確認（鳴らさず判定だけ） */
    SHINKAICHI_CHECK,
    /** 北鈴蘭台の手前の予告 */
    KITA_PRE,
    /** 北鈴蘭台 到着直前 */
    KITA_MAIN,
    /** 北鈴蘭台で降りたかの確認（鳴らさず判定だけ） */
    KITA_CHECK;

    val isCheck: Boolean get() = this == SHINKAICHI_CHECK || this == KITA_CHECK
    val isShinkaichi: Boolean get() = this == SHINKAICHI_PRE || this == SHINKAICHI_MAIN || this == SHINKAICHI_CHECK
}

data class PlannedAlarm(val kind: AlarmKind, val at: ServiceTime, val message: String)

data class AlarmSettings(
    /** 新開地の予告: 到着の何分前か */
    val shinkaichiPreMinutes: Int = 5,
    /** 直通特急など梅田まで行く列車のときの新開地予告 */
    val shinkaichiPreMinutesUmedaRisk: Int = 8,
    /** 北鈴蘭台の予告（鈴蘭台の手前あたり） */
    val kitaPreMinutes: Int = 5,
    /** 本アラーム: 到着の何分前か */
    val mainMinutes: Int = 1,
    /** 到着から何分後に「降りたか」を確認するか */
    val checkAfterMinutes: Int = 2,
    /** 乗り過ごし警報を何回まで繰り返すか（1分おき） */
    val maxOvershootRings: Int = 5,
    /** 普通優先の許容差（分） */
    val localPreferenceToleranceMinutes: Int = 15,
)

object AlarmPlanner {

    fun plan(route: Route, settings: AlarmSettings = AlarmSettings()): List<PlannedAlarm> {
        val alarms = mutableListOf<PlannedAlarm>()
        route.shinkaichiArr?.let { arr ->
            val pre = if (route.umedaRisk) settings.shinkaichiPreMinutesUmedaRisk else settings.shinkaichiPreMinutes
            val warn = if (route.umedaRisk) "（乗り過ごすと梅田まで行きます）" else ""
            alarms += PlannedAlarm(AlarmKind.SHINKAICHI_PRE, arr - pre, "まもなく新開地。起きてください$warn")
            alarms += PlannedAlarm(AlarmKind.SHINKAICHI_MAIN, arr - settings.mainMinutes, "次は新開地。降りて神戸電鉄 ${route.shintetsu.dep} ${route.shintetsu.dest}行きに乗り換え")
            alarms += PlannedAlarm(AlarmKind.SHINKAICHI_CHECK, arr + settings.checkAfterMinutes, "")
        }
        alarms += kitaAlarms(route.shintetsu, settings)
        return alarms.sortedBy { it.at }
    }

    fun kitaAlarms(train: ShintetsuTrain, settings: AlarmSettings = AlarmSettings()): List<PlannedAlarm> {
        val arr = train.kitaSuzurandaiArr ?: return emptyList()
        val transfer = if (train.transferAtSuzurandai) "鈴蘭台で三田行きに乗り換え。" else ""
        return listOf(
            PlannedAlarm(AlarmKind.KITA_PRE, arr - settings.kitaPreMinutes, "${transfer}まもなく北鈴蘭台。起きてください"),
            PlannedAlarm(AlarmKind.KITA_MAIN, arr - settings.mainMinutes, "次は北鈴蘭台。降りてください"),
            PlannedAlarm(AlarmKind.KITA_CHECK, arr + settings.checkAfterMinutes, ""),
        )
    }
}
