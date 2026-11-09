package norikae.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripEngineTest {
    private val tt = loadRealTimetable()
    private val planner = RoutePlanner(tt)

    private val kita = LatLon(34.7400, 135.1400)       // テスト用の座標（実際の駅位置ではない）
    private val yamanomachi = LatLon(34.7500, 135.1500)
    private val nishiguchi = LatLon(34.7300, 135.1300)
    private val stations = StationLocations(
        mapOf(
            StationId.KITA_SUZURANDAI to kita,
            StationId.YAMANOMACHI to yamanomachi,
            StationId.SUZURANDAI_NISHIGUCHI to nishiguchi,
        ),
    )
    private val engine = TripEngine(planner, stations)

    /** 新開地 22:20着 → 22:27 三田 → 北鈴蘭台 22:45着 */
    private fun route(umeda: Boolean = false): Route {
        val train = tt.shintetsu.first { it.dep == t("22:27") }
        val sanyo = SanyoTrain(t("21:30"), if (umeda) TrainType.DIRECT_LIMITED else TrainType.LOCAL, "x", t("22:20"), null)
        return Route(sanyo, t("22:20"), false, train, train.kitaSuzurandaiArr!!)
    }

    private fun started(umeda: Boolean = false) = engine.start(route(umeda), "2026-11-09")

    @Test
    fun startSchedulesBothStations() {
        val tr = started()
        val schedule = tr.actions.filterIsInstance<TripAction.Schedule>().single()
        assertEquals(
            listOf("22:15", "22:19", "22:22", "22:40", "22:44", "22:47"),
            schedule.alarms.map { it.at.toString() },
        )
    }

    @Test
    fun umedaRiskMakesShinkaichiPreAlarmEarlier() {
        val schedule = started(umeda = true).actions.filterIsInstance<TripAction.Schedule>().single()
        assertEquals("22:12", schedule.alarms.first { it.kind == AlarmKind.SHINKAICHI_PRE }.at.toString())
    }

    @Test
    fun happyPath() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        assertEquals(Phase.TRANSFERRING, s.phase)
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:28"))).state
        assertEquals(Phase.RIDING_SHINTETSU, s.phase)
        s = engine.reduce(s, TripEvent.LocationChanged(kita, t("22:45"))).state
        val tr = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:46")))
        assertEquals(Phase.DONE, tr.state.phase)
        assertTrue(TripAction.End in tr.actions)
    }

    @Test
    fun overshootShinkaichiRingsRepeatedly() {
        var s = started().state
        var tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:22")))
        assertEquals(Phase.OVERSHOT_SHINKAICHI, tr.state.phase)
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.OVERSHOT_SHINKAICHI })
        assertTrue(tr.actions.any { it is TripAction.Schedule }, "1分後にもう一度確認する")
        // 5回鳴らしたら止める
        s = tr.state
        repeat(10) { i -> s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:23") + i)).state }
        assertEquals(5, s.shinkaichiRings)
        // 乗り過ごした先で降りたら、戻ってくる前提で北鈴蘭台アラームを入れ直す
        tr = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:30")))
        assertEquals(Phase.TRANSFERRING, tr.state.phase)
        val kitaAlarms = tr.actions.filterIsInstance<TripAction.Schedule>().single().alarms
        assertEquals("22:57", ServiceTime(tr.state.shintetsuDep).toString()) // 22:30+10分=22:40着 → 22:42は間に合わず22:57
        assertEquals(3, kitaAlarms.size)
    }

    @Test
    fun noOvershootIfAlreadyTransferred() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:19"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:22")))
        assertTrue(tr.actions.isEmpty())
    }

    @Test
    fun walkingLongBeforeShinkaichiIsIgnored() {
        val s = started().state
        val tr = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("21:50")))
        assertEquals(Phase.RIDING_SANYO, tr.state.phase)
    }

    @Test
    fun boardingDifferentTrainReschedulesKitaAlarms() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        val tr = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:43")))
        assertEquals("22:42", ServiceTime(tr.state.shintetsuDep).toString())
        assertEquals("23:00", ServiceTime(tr.state.kitaArr).toString())
        assertTrue(tr.actions.any { it is TripAction.Schedule })
    }

    @Test
    fun boardingAwaLineTrainWarns() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        val tr = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:33"))) // 22:32 小野
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.WRONG_LINE })
    }

    @Test
    fun locationOnAwaLineWarnsOnce() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:28"))).state
        val tr = engine.reduce(s, TripEvent.LocationChanged(nishiguchi, t("22:44")))
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.WRONG_LINE })
        val again = engine.reduce(tr.state, TripEvent.LocationChanged(nishiguchi, t("22:45")))
        assertTrue(again.actions.isEmpty())
    }

    @Test
    fun overshootKitaWithReturnGuidance() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:28"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.KITA_CHECK, t("22:47")))
        assertEquals(Phase.OVERSHOT_KITA, tr.state.phase)
        val ring = tr.actions.filterIsInstance<TripAction.Ring>().single()
        assertTrue(ring.message.contains("23:04"), ring.message) // 22:47+5分以降で最初の北鈴蘭台発
    }

    @Test
    fun reachingYamanomachiTriggersOvershoot() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:28"))).state
        val tr = engine.reduce(s, TripEvent.LocationChanged(yamanomachi, t("22:47")))
        assertEquals(Phase.OVERSHOT_KITA, tr.state.phase)
    }

    @Test
    fun kitaCheckPassesWhenAtStationAndNotInVehicle() {
        var s = started().state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:20"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:28"))).state
        s = engine.reduce(s, TripEvent.LocationChanged(kita, t("22:45"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.STILL, t("22:46"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.KITA_CHECK, t("22:47")))
        assertEquals(Phase.DONE, tr.state.phase)
    }

    @Test
    fun stateSurvivesJsonRoundTrip() {
        val s = started().state.copy(lastMotion = Motion.WALKING, lastLat = 1.0, lastLon = 2.0)
        assertEquals(s, TripState.fromJson(s.toJson()))
    }

    @Test
    fun autoStartOnlyAfter21() {
        val rule = AutoStartRule()
        assertTrue(rule.shouldStart(t("21:10"), Motion.IN_VEHICLE, nearArai = true, leftAraiAt = null))
        assertFalse(rule.shouldStart(t("20:50"), Motion.IN_VEHICLE, nearArai = true, leftAraiAt = null))
        assertTrue(rule.shouldStart(t("22:10"), Motion.IN_VEHICLE, nearArai = null, leftAraiAt = t("22:00")))
        assertFalse(rule.shouldStart(t("22:30"), Motion.IN_VEHICLE, nearArai = null, leftAraiAt = t("22:00")))
        assertFalse(rule.shouldStart(t("22:05"), Motion.WALKING, nearArai = true, leftAraiAt = null))
    }

    @Test
    fun taxiRouteWakesAtSuzurandaiTerminal() {
        val train = tt.shintetsu.first { it.dep == t("24:03") }
        val sanyo = SanyoTrain(t("22:47"), TrainType.LOCAL, "新開地", t("24:00"), null)
        val route = Route(sanyo, t("24:00"), false, train, train.finalArr!!)
        val start = engine.start(route, "2026-11-09")
        assertTrue(start.state.taxi)
        val kitaAlarms = start.actions.filterIsInstance<TripAction.Schedule>().single().alarms.filter { !it.kind.isShinkaichi }
        assertEquals(listOf("24:12", "24:16", "24:19"), kitaAlarms.map { it.at.toString() })
        var s = start.state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("24:00"))).state
        // 鈴蘭台止まりに乗っても「電車違い」にはしない
        val boarded = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("24:04")))
        assertTrue(boarded.actions.none { it is TripAction.Ring && it.kind == RingKind.WRONG_LINE })
        s = boarded.state
        // 終点を過ぎても乗ったまま → 乗り過ごしではなく「終点です、起きて」
        val check = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.KITA_CHECK, t("24:19")))
        assertEquals(Phase.RIDING_SHINTETSU, check.state.phase)
        val ring = check.actions.filterIsInstance<TripAction.Ring>().single()
        assertEquals(RingKind.WAKE, ring.kind)
        assertTrue(ring.message.contains("終点の鈴蘭台"), ring.message)
        // 歩いたら完了
        val walked = engine.reduce(check.state, TripEvent.MotionChanged(Motion.WALKING, t("24:20")))
        assertEquals(Phase.DONE, walked.state.phase)
    }

    @Test
    fun missedLastTrainToKitaSwitchesToTaxi() {
        // 予定は 23:50 道場南口行きだったが、実際は 24:03 鈴蘭台止まりに乗った
        val train = tt.shintetsu.first { it.dep == t("23:50") }
        val sanyo = SanyoTrain(t("22:19"), TrainType.LOCAL, "x", t("23:37"), null)
        var s = engine.start(Route(sanyo, t("23:37"), false, train, train.finalArr!!), "2026-11-09").state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("23:37"))).state
        val tr = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("24:04")))
        assertTrue(tr.state.taxi)
        assertEquals("24:17", ServiceTime(tr.state.kitaArr).toString())
        assertTrue(tr.actions.any { it is TripAction.Notify && it.message.contains("タクシー") })
    }
}
