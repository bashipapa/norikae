package norikae.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** テスト用の架空の山陽ダイヤ（実際の時刻ではない） */
private fun sampleTimetable(): Timetable {
    val real = loadRealTimetable()
    val sanyo = listOf(
        SanyoTrain(t("21:00"), TrainType.LOCAL, "神戸三宮", t("22:10"), null),
        SanyoTrain(t("21:05"), TrainType.DIRECT_LIMITED, "大阪梅田", t("21:50"), null),
        SanyoTrain(t("21:30"), TrainType.LOCAL, "新開地", t("22:40"), null),
        SanyoTrain(t("23:00"), TrainType.LOCAL, "東二見", null, null),
    )
    return real.copy(sanyo = sanyo)
}

class RoutePlannerTest {
    private val planner = RoutePlanner(sampleTimetable())

    @Test
    fun connectsWithTransferMargin() {
        // 22:10 着 + 乗り換え3分 → 22:12 は間に合わない → 22:27
        val train = assertNotNull(planner.connectAtShinkaichi(t("22:10")))
        assertEquals("22:27", train.dep.toString())
    }

    @Test
    fun skipsAwaLineTrains() {
        // 21:14 着 → 21:17 志染（粟生線）は飛ばして 21:26 三田
        val train = assertNotNull(planner.connectAtShinkaichi(t("21:14")))
        assertEquals("21:26", train.dep.toString())
    }

    @Test
    fun noConnectionAfterLastTrain() {
        assertNull(planner.connectAtShinkaichi(t("23:50")))
    }

    @Test
    fun plansRoutesAndDropsTrainsNotReachingShinkaichi() {
        val routes = planner.plan(t("21:00"))
        assertEquals(listOf("21:00", "21:05", "21:30"), routes.map { it.sanyo!!.dep.toString() })
        assertTrue(routes[1].umedaRisk)
    }

    @Test
    fun recommendsLocalWithinTolerance() {
        val routes = planner.plan(t("21:00"))
        // 最速は 21:05 直通特急（北鈴蘭台 22:14着）。21:00 普通は 22:45着で31分遅い → 許容15分を超えるので直特
        assertEquals("21:05", planner.recommend(routes, 15)!!.sanyo!!.dep.toString())
        // 許容を40分にすれば普通を選ぶ
        assertEquals("21:00", planner.recommend(routes, 40)!!.sanyo!!.dep.toString())
    }

    @Test
    fun routeFromActualBoardingTime() {
        // 21:07 に乗車を検知 → 21:05 の直通特急に乗ったとみなす
        val route = assertNotNull(planner.routeFromBoarding(t("21:07")))
        assertEquals("21:05", route.sanyo!!.dep.toString())
        assertEquals("21:56", route.shintetsu.dep.toString())
    }

    @Test
    fun fallbackWithoutSanyoTimetable() {
        val real = loadRealTimetable().copy(sanyo = emptyList(), sanyoDefaultRunMinutes = 60)
        val p = RoutePlanner(real)
        val route = assertNotNull(p.routeFromBoarding(t("21:20")))
        // 21:20 + 60分 = 22:20 新開地着 → +3分 → 22:27 三田
        assertEquals("22:20", route.shinkaichiArr.toString())
        assertEquals("22:27", route.shintetsu.dep.toString())
        // 所要分も未設定なら、新開地発の一覧だけ返す
        val list = RoutePlanner(loadRealTimetable().copy(sanyo = emptyList())).plan(t("23:00"))
        assertEquals(listOf("23:17", "23:30", "23:34", "23:50"), list.map { it.shintetsu.dep.toString() })
    }

    @Test
    fun guessesShintetsuTrainFromBoardingTime() {
        assertEquals("21:17", planner.shintetsuFromBoarding(t("21:18"))!!.dep.toString())
        assertEquals("21:26", planner.shintetsuFromBoarding(t("21:27"))!!.dep.toString())
    }

    @Test
    fun returnTrainForOvershoot() {
        assertEquals("24:27", planner.returnTrain(t("24:15"))!!.dep.toString())
        assertNull(planner.returnTrain(t("24:30")))
    }
}
