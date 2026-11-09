package norikae.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** data/timetable.json の実データ（平日）で、経路が想定どおり組めるか */
class RealDataTest {
    private val tt = loadRealTimetable()
    private val planner = RoutePlanner(tt)

    @Test
    fun sanyoLoaded() {
        assertEquals(13, tt.sanyo.size)
        assertEquals("21:00", tt.sanyo.first().dep.toString())
        assertEquals("24:14", tt.sanyo.last().dep.toString())
    }

    @Test
    fun routesFrom21() {
        val routes = planner.plan(t("21:00"))
        assertEquals(
            listOf("21:00", "21:15", "21:32", "21:47", "22:04", "22:19", "22:33"),
            routes.map { it.sanyo!!.dep.toString() },
        )
        assertEquals(
            listOf("23:00", "22:45", "23:15", "23:35", "24:08", "24:08", "23:50"),
            routes.map { it.kitaArr.toString() },
        )
    }

    @Test
    fun lastDepartureFromArai() {
        val last = assertNotNull(planner.lastDepartureRoute())
        assertEquals("22:33", last.sanyo!!.dep.toString())
        assertEquals("23:30", last.shintetsu.dep.toString())
        assertEquals("23:50", last.kitaArr.toString())
        assertTrue(last.umedaRisk, "東二見で直通特急に乗り換えるので梅田注意")
    }

    @Test
    fun after2247NoWayHome() {
        // 22:47 は新開地 24:00着 → 北鈴蘭台行きはもう無い
        assertTrue(planner.plan(t("22:40")).isEmpty())
        assertEquals(null, planner.routeFromBoarding(t("22:48")))
    }

    @Test
    fun directLimitedTransferIsNotLocal() {
        val t2115 = tt.sanyo.first { it.dep == t("21:15") }
        assertEquals(TrainType.DIRECT_LIMITED, t2115.transferTrainType)
        assertFalse(t2115.isLocalOnly)
        assertTrue(t2115.goesToUmeda)
        val t2100 = tt.sanyo.first { it.dep == t("21:00") }
        assertTrue(t2100.isLocalOnly)
        assertFalse(t2100.goesToUmeda)
    }

    @Test
    fun recommendationPrefersLocal() {
        // 21:00 から探すと、最速は 21:15（直特乗換、22:45着）。21:00 普通は 23:00着で差15分 → 普通を選ぶ
        val routes = planner.plan(t("21:00"))
        assertEquals("21:00", planner.recommend(routes, 15)!!.sanyo!!.dep.toString())
    }
}
