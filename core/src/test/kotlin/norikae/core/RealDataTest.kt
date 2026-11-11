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
        // 荒井発は16時台から（夕方の直通特急 16:57発〜を含む）
        assertEquals("16:03", tt.sanyo.first().dep.toString())
        assertEquals("24:14", tt.sanyo.last().dep.toString())
        assertEquals(13, tt.sanyo.count { it.dep >= t("21:00") })
    }

    @Test
    fun routesFrom21() {
        val routes = planner.plan(t("21:00"))
        assertEquals(
            listOf("21:00", "21:15", "21:32", "21:47", "22:04", "22:19", "22:33", "22:47"),
            routes.map { it.sanyo!!.dep.toString() },
        )
        assertEquals(
            listOf("23:00", "22:45", "23:15", "23:35", "24:08", "24:08", "23:50", "24:17"),
            routes.map { it.kitaArr.toString() },
        )
        // 最後の 22:47 だけが鈴蘭台止まり（タクシー）
        assertEquals(listOf(false, false, false, false, false, false, false, true), routes.map { it.taxiFromSuzurandai })
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
    fun after2247OnlyTaxiFromSuzurandai() {
        // 22:47 は新開地 24:00着 → 北鈴蘭台行きはもう無いが、24:03 鈴蘭台止まりに乗れる（鈴蘭台 24:17着 → タクシー）
        val routes = planner.plan(t("22:40"))
        assertEquals(1, routes.size)
        val r = routes.single()
        assertTrue(r.taxiFromSuzurandai)
        assertEquals("鈴蘭台", r.alightStation)
        assertEquals("24:03", r.shintetsu.dep.toString())
        assertEquals("24:17", r.kitaArr.toString())
        assertEquals("24:03", planner.routeFromBoarding(t("22:48"))!!.shintetsu.dep.toString())
        // 23:03 以降は新開地に行けないので候補なし
        assertTrue(planner.plan(t("23:00")).isEmpty())
        assertEquals("22:47", planner.lastTaxiRoute()!!.sanyo!!.dep.toString())
        // 電車で北鈴蘭台まで帰れる荒井発の最終は 22:33 のまま
        assertEquals("22:33", planner.lastDepartureRoute()!!.sanyo!!.dep.toString())
    }

    @Test
    fun taxiRouteIsNotRecommendedWhenTrainRouteExists() {
        val routes = planner.plan(t("22:30"))
        assertEquals(listOf("22:33", "22:47"), routes.map { it.sanyo!!.dep.toString() })
        assertEquals("22:33", planner.recommend(routes)!!.sanyo!!.dep.toString())
        // タクシー経路しか無ければそれを出す
        assertEquals("22:47", planner.recommend(planner.plan(t("22:40")))!!.sanyo!!.dep.toString())
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

class StationDataTest {
    private val tt = loadRealTimetable()

    @Test
    fun allStationsHaveDefaultLocations() {
        assertEquals(StationId.entries.toSet(), tt.stations.keys)
    }

    @Test
    fun neighbouringStationRadiiDoNotOverlap() {
        val kita = tt.stations.getValue(StationId.KITA_SUZURANDAI)
        val yama = tt.stations.getValue(StationId.YAMANOMACHI)
        val suzu = tt.stations.getValue(StationId.SUZURANDAI)
        val nishi = tt.stations.getValue(StationId.SUZURANDAI_NISHIGUCHI)
        // 北鈴蘭台の半径400m と 山の街の半径300m が重ならない
        assertTrue(kita.distanceTo(yama) > 700, "北鈴蘭台〜山の街 ${kita.distanceTo(yama)}m")
        // 鈴蘭台（正しい路線）にいるときに 鈴蘭台西口の半径300m に入らない
        assertTrue(suzu.distanceTo(nishi) > 300, "鈴蘭台〜鈴蘭台西口 ${suzu.distanceTo(nishi)}m")
    }

    @Test
    fun transferTimesParsed() {
        val t2233 = tt.sanyo.first { it.dep == t("22:33") }
        assertEquals("東二見", t2233.transferAt)
        assertEquals("22:48", t2233.transferArr.toString())
        assertEquals("22:53", t2233.transferDep.toString())
    }
}

/** 山陽明石発（明石（特急）・明石（普通）タブ） */
class AkashiDataTest {
    private val tt = loadRealTimetable()
    private val planner = RoutePlanner(tt)
    private val limited: (SanyoTrain) -> Boolean = { it.type.isLimited }
    private val local: (SanyoTrain) -> Boolean = { it.type == TrainType.LOCAL }

    @Test
    fun akashiLoaded() {
        assertTrue(tt.sanyoAkashi.isNotEmpty())
        assertTrue(tt.sanyoAkashi.any { it.type == TrainType.DIRECT_LIMITED })
        assertTrue(tt.sanyoAkashi.any { it.type == TrainType.LOCAL })
        assertTrue(StationId.AKASHI in tt.stations)
    }

    @Test
    fun limitedTabRoutes() {
        val routes = planner.plan(t("21:00"), Origin.AKASHI, limited)
        assertTrue(routes.all { it.sanyo!!.type.isLimited && it.origin == Origin.AKASHI })
        assertEquals("21:13", routes.first().sanyo!!.dep.toString())
        // 北鈴蘭台まで電車で帰れる最終は 23:01（新開地 23:24 → 23:30 → 北鈴蘭台 23:50）
        val last = planner.lastDepartureRoute(Origin.AKASHI, limited)!!
        assertEquals("23:01", last.sanyo!!.dep.toString())
        assertEquals("23:50", last.kitaArr.toString())
        // 23:33 直通特急（御影行き）は鈴蘭台止まり → タクシー
        assertEquals("23:33", planner.lastTaxiRoute(Origin.AKASHI, limited)!!.sanyo!!.dep.toString())
    }

    @Test
    fun localTabRoutes() {
        val routes = planner.plan(t("21:00"), Origin.AKASHI, local)
        assertTrue(routes.all { it.sanyo!!.type == TrainType.LOCAL })
        val last = planner.lastDepartureRoute(Origin.AKASHI, local)!!
        assertEquals("23:04", last.sanyo!!.dep.toString())
        assertEquals("24:08", last.kitaArr.toString())
        assertEquals("23:22", planner.lastTaxiRoute(Origin.AKASHI, local)!!.sanyo!!.dep.toString())
    }

    @Test
    fun boardingAtAkashiPicksByTab() {
        // 21:14 に明石で乗車：特急タブなら 21:13 直通特急、普通タブなら 21:00 普通（21:15 はまだ発車前）
        assertEquals("21:13", planner.routeFromBoarding(t("21:14"), Origin.AKASHI, limited)!!.sanyo!!.dep.toString())
        assertEquals("21:15", planner.routeFromBoarding(t("21:16"), Origin.AKASHI, local)!!.sanyo!!.dep.toString())
    }
}

/** 荒井（特急）・荒井（普通）タブ（夕方） */
class AraiEveningTest {
    private val tt = loadRealTimetable()
    private val planner = RoutePlanner(tt)
    private val limited: (SanyoTrain) -> Boolean = { it.type.isLimited }
    private val local: (SanyoTrain) -> Boolean = { it.type == TrainType.LOCAL }

    @Test
    fun limitedTrainsStopAtAraiInEvening() {
        val lim = tt.sanyo.filter { it.type.isLimited }
        assertEquals("16:57", lim.first().dep.toString())
        assertEquals("20:24", lim.last().dep.toString())
        assertTrue(lim.any { it.type == TrainType.S_LIMITED })
    }

    @Test
    fun limitedTabRoutes() {
        val routes = planner.plan(t("17:00"), Origin.ARAI, limited)
        assertTrue(routes.isNotEmpty() && routes.all { it.sanyo!!.type.isLimited })
        assertEquals("17:04", routes.first().sanyo!!.dep.toString())
        // 特急で北鈴蘭台まで帰れる最終：20:24 直通特急 → 新開地 21:08 → 21:11 三田行き → 北鈴蘭台 21:29
        val last = planner.lastDepartureRoute(Origin.ARAI, limited)!!
        assertEquals("20:24", last.sanyo!!.dep.toString())
        assertEquals("21:11", last.shintetsu.dep.toString())
        assertEquals("21:29", last.kitaArr.toString())
        assertTrue(last.umedaRisk)
    }

    @Test
    fun localTabIncludesEveningAndNight() {
        val routes = planner.plan(t("16:00"), Origin.ARAI, local)
        assertTrue(routes.all { it.sanyo!!.type == TrainType.LOCAL })
        assertEquals("16:03", routes.first().sanyo!!.dep.toString())
        assertEquals("22:47", routes.last().sanyo!!.dep.toString()) // 最後は鈴蘭台止まり（タクシー）
    }

    @Test
    fun boardingLimitedAtAraiInEvening() {
        // 18:28 に荒井で特急に乗った → 18:27 直通特急
        val r = planner.routeFromBoarding(t("18:28"), Origin.ARAI, limited)!!
        assertEquals("18:27", r.sanyo!!.dep.toString())
        assertEquals("19:10", r.shinkaichiArr.toString())
    }
}
