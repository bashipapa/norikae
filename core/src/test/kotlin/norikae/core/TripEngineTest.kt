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
        // 上限なし：乗っている限り鳴らし続ける（1回目＋10回）
        assertEquals(11, s.shinkaichiRings)
        val more = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:34")))
        assertTrue(more.actions.any { it is TripAction.Ring } && more.actions.any { it is TripAction.Schedule })
        // 乗り過ごした先で降りたら、戻ってくる前提で北鈴蘭台アラームを入れ直す
        tr = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("22:30")))
        assertEquals(Phase.TRANSFERRING, tr.state.phase)
        val kitaAlarms = tr.actions.filterIsInstance<TripAction.Schedule>().single().alarms
        assertEquals("22:42", ServiceTime(tr.state.shintetsuDep).toString()) // 22:30+10分=22:40着 → 乗り換え1分で22:42
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

/** 新開地の乗り過ごし判定（2026-11-12 の誤判定の再現と、位置を使った判定） */
class ShinkaichiLocationTest {
    private val tt = loadRealTimetable()
    private val stations = StationLocations(tt.stations)
    private val engine = TripEngine(RoutePlanner(tt), stations)

    // 実際のログの位置（新開地から約40m）
    private val atShinkaichi = LatLon(34.67622, 135.16939)
    private val nearKosokuKobe = LatLon(34.67985, 135.17520)
    // 新開地の手前（西代・大開方面に約700m）
    private val beforeShinkaichi = LatLon(34.67300, 135.16300)

    /** 荒井 16:57 直通特急 → 新開地 17:41着 → 17:49 岡場行き → 北鈴蘭台 18:07着（当日の経路） */
    private fun started(): TripState {
        val sanyo = tt.sanyo.first { it.dep == t("16:57") }
        val train = tt.shintetsu.first { it.dep == t("17:49") }
        val route = Route(sanyo, sanyo.shinkaichiArr, false, train, train.finalArr!!)
        return engine.start(route, "2026-11-12").state
    }

    @Test
    fun reproduceLogNoWalkingButAtShinkaichi() {
        var s = started()
        assertEquals("17:41", ServiceTime(s.shinkaichiArr!!).toString())
        // 17:43:27 位置（新開地の近く）、17:43:34 停止。歩行は来ない
        s = engine.reduce(s, TripEvent.LocationChanged(atShinkaichi, t("17:43"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.STILL, t("17:43"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43")))
        assertEquals(Phase.TRANSFERRING, tr.state.phase)
        assertTrue(tr.actions.none { it is TripAction.Ring })
    }

    @Test
    fun approachingKosokuKobeIsOvershoot() {
        var s = started()
        val tr = engine.reduce(s, TripEvent.LocationChanged(nearKosokuKobe, t("17:43")))
        assertEquals(Phase.OVERSHOT_SHINKAICHI, tr.state.phase)
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.OVERSHOT_SHINKAICHI })
    }

    @Test
    fun delayedTrainBeforeShinkaichiDoesNotRing() {
        var s = started()
        s = engine.reduce(s, TripEvent.LocationChanged(beforeShinkaichi, t("17:42"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43")))
        assertEquals(Phase.RIDING_SANYO, tr.state.phase)
        assertTrue(tr.actions.none { it is TripAction.Ring })
        assertTrue(tr.actions.any { it is TripAction.Schedule }, "1分後にもう一度確認")
    }

    @Test
    fun staleLocationIsIgnored() {
        var s = started()
        // 10分前の位置（新開地の近く）は古いので使わない → 位置不明として従来どおり鳴らす
        s = engine.reduce(s, TripEvent.LocationChanged(atShinkaichi, t("17:33"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43")))
        assertEquals(Phase.OVERSHOT_SHINKAICHI, tr.state.phase)
    }

    @Test
    fun misjudgedOvershootCorrectsItselfWhenAtShinkaichi() {
        var s = started()
        // 位置が無くて乗り過ごし判定 → そのあと新開地の位置が取れた → 乗り換え中に戻る
        s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43"))).state
        assertEquals(Phase.OVERSHOT_SHINKAICHI, s.phase)
        val tr = engine.reduce(s, TripEvent.LocationChanged(atShinkaichi, t("17:44")))
        assertEquals(Phase.TRANSFERRING, tr.state.phase)
        assertTrue(TripAction.StopRinging in tr.actions)
    }

    @Test
    fun userCanSayTransferring() {
        var s = started()
        s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43"))).state
        val tr = engine.reduce(s, TripEvent.UserTransferring(t("17:44")))
        assertEquals(Phase.TRANSFERRING, tr.state.phase)
        assertTrue(TripAction.Cancel(setOf(AlarmKind.SHINKAICHI_PRE, AlarmKind.SHINKAICHI_MAIN, AlarmKind.SHINKAICHI_CHECK)) in tr.actions)
    }

    @Test
    fun kitaAlarmsRingEvenIfShinkaichiWasMisjudged() {
        var s = started()
        s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:43"))).state
        assertEquals(Phase.OVERSHOT_SHINKAICHI, s.phase)
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.KITA_PRE, t("18:02")))
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.WAKE })
    }
}

/** 乗り過ごしたら、電車に乗っている限り鳴らし続ける */
class KeepRingingWhileOnTrainTest {
    private val tt = loadRealTimetable()
    private val engine = TripEngine(RoutePlanner(tt), StationLocations(tt.stations))
    private val kosoku = tt.stations.getValue(StationId.KOSOKU_KOBE)

    private fun overshot(): TripState {
        val sanyo = tt.sanyo.first { it.dep == t("16:57") } // 新開地 17:41着
        val train = tt.shintetsu.first { it.dep == t("17:49") }
        var s = engine.start(Route(sanyo, sanyo.shinkaichiArr, false, train, train.finalArr!!), "2026-11-12").state
        s = engine.reduce(s, TripEvent.LocationChanged(kosoku, t("17:42"))).state
        assertEquals(Phase.OVERSHOT_SHINKAICHI, s.phase)
        return s
    }

    /** 東へ1分に約1km進む（三宮・梅田方面へ走っている） */
    private fun moving(i: Int) = LatLon(kosoku.lat, kosoku.lon + 0.011 * (i + 1))

    @Test
    fun keepsRingingWhileTrainMoves() {
        var s = overshot()
        repeat(30) { i ->
            val now = t("17:43") + i
            s = engine.reduce(s, TripEvent.LocationChanged(moving(i), now)).state
            val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, now))
            assertTrue(tr.actions.any { it is TripAction.Ring }, "${i + 1}回目も鳴る")
            s = tr.state
        }
        assertEquals(Phase.OVERSHOT_SHINKAICHI, s.phase)
    }

    @Test
    fun stopsWhenStayingAtOneStationForFiveMinutes() {
        var s = overshot()
        // 次の駅のホームで止まって待っている（歩行は検知されない）
        val platform = moving(2)
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.STILL, t("17:45"))).state
        var last: Transition? = null
        for (i in 0..5) {
            last = engine.reduce(s, TripEvent.LocationChanged(platform, t("17:45") + i))
            s = last.state
        }
        // 17:45 から5分 → 降りたとみなして警報を止め、新開地に戻る案内
        assertEquals(Phase.TRANSFERRING, s.phase)
        assertTrue(TripAction.StopRinging in last!!.actions)
    }

    @Test
    fun stoppedTrainStillRingsIfInVehicle() {
        var s = overshot()
        // 駅に長く停車していても、乗り物の判定なら乗っているとみなして鳴らし続ける
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("17:45"))).state
        val p = moving(2)
        for (i in 0..6) s = engine.reduce(s, TripEvent.LocationChanged(p, t("17:45") + i)).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("17:52")))
        assertTrue(tr.actions.any { it is TripAction.Ring })
    }
}

/** 事前に予定を入れたとき：15分前のお知らせと、出発時刻での乗車中への切り替え */
class PlannedTripTest {
    private val tt = loadRealTimetable()
    private val engine = TripEngine(RoutePlanner(tt), StationLocations(tt.stations))
    private val planner = RoutePlanner(tt)

    /** 荒井 21:32 普通 → 新開地 22:43着 → 22:57 → 北鈴蘭台 23:15着 */
    private val route = planner.plan(t("21:30")).first { it.sanyo!!.dep == t("21:32") }

    @Test
    fun plannedStartSchedulesReminder15MinutesBefore() {
        val tr = engine.start(route, "2026-11-13", planned = true)
        assertEquals(Phase.PLANNED, tr.state.phase)
        val alarms = tr.actions.filterIsInstance<TripAction.Schedule>().single().alarms
        val reminder = alarms.single { it.kind == AlarmKind.DEPART_REMINDER }
        assertEquals("21:17", reminder.at.toString())
        // 新開地・北鈴蘭台のアラームも同時に入る
        assertTrue(alarms.any { it.kind == AlarmKind.SHINKAICHI_PRE } && alarms.any { it.kind == AlarmKind.KITA_MAIN })
    }

    @Test
    fun reminderRingsWithThePlan() {
        val s = engine.start(route, "2026-11-13", planned = true).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.DEPART_REMINDER, t("21:17")))
        val ring = tr.actions.filterIsInstance<TripAction.Ring>().single()
        assertEquals(RingKind.DEPART, ring.kind)
        assertEquals(0, ring.level, "出発前のお知らせは振動だけ")
        assertTrue(ring.message.contains("あと15分") && ring.message.contains("21:32発"), ring.message)
        assertTrue(ring.message.contains("北鈴蘭台 23:15着"), ring.message)
        assertEquals(Phase.PLANNED, tr.state.phase)
    }

    @Test
    fun movementBeforeDepartureIsIgnored() {
        var s = engine.start(route, "2026-11-13", planned = true).state
        // 駅まで歩く・バスに乗るなどは無視（乗車中にならない）
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.WALKING, t("21:20"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("21:25"))).state
        assertEquals(Phase.PLANNED, s.phase)
    }

    @Test
    fun becomesRidingAtDepartureAndAlarmsWork() {
        var s = engine.start(route, "2026-11-13", planned = true).state
        // 出発後の最初の出来事で乗車中に切り替わり、新開地の予告が鳴る
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_PRE, t("22:38")))
        assertEquals(Phase.RIDING_SANYO, tr.state.phase)
        assertTrue(tr.actions.any { it is TripAction.Ring && it.kind == RingKind.WAKE })
    }

    @Test
    fun notPlannedStartsRidingImmediately() {
        val tr = engine.start(route, "2026-11-13")
        assertEquals(Phase.RIDING_SANYO, tr.state.phase)
        assertTrue(tr.actions.filterIsInstance<TripAction.Schedule>().single().alarms.none { it.kind == AlarmKind.DEPART_REMINDER })
    }

    @Test
    fun plannedStateSurvivesJsonRoundTrip() {
        val s = engine.start(route, "2026-11-13", planned = true).state
        assertEquals(s, TripState.fromJson(s.toJson()))
    }
}

/** 起こし方を強くする（2026-11-13 に起きられなかった件） */
class WakeEscalationTest {
    private val tt = loadRealTimetable()
    private val engine = TripEngine(RoutePlanner(tt), StationLocations(tt.stations))

    /** 当日の経路：明石 22:31 直通特急 → 新開地 22:54着 → 22:57 三田行き → 北鈴蘭台 23:15着 */
    private fun started(): TripState {
        val r = RoutePlanner(tt).plan(t("22:30"), Origin.AKASHI) { it.type.isLimited }.first()
        assertEquals("22:31", r.sanyo!!.dep.toString())
        return engine.start(r, "2026-11-13").state
    }

    @Test
    fun preAlarmRepeatsEveryMinuteUntilMain() {
        var s = started()
        // 予告 22:46（8分前）→ 22:47〜22:52 に1分おきに起こし直し、22:53 は本アラーム
        var tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_PRE, t("22:46")))
        assertEquals(0, tr.actions.filterIsInstance<TripAction.Ring>().single().level, "新開地に着くまでは振動だけ")
        val times = mutableListOf<String>()
        while (true) {
            val next = tr.actions.filterIsInstance<TripAction.Schedule>().firstOrNull()?.alarms?.single() ?: break
            assertEquals(AlarmKind.WAKE_REPEAT_SHINKAICHI, next.kind)
            times += next.at.toString()
            tr = engine.reduce(tr.state, TripEvent.AlarmFired(next.kind, next.at))
            val ring = tr.actions.filterIsInstance<TripAction.Ring>().single()
            assertEquals(0, ring.level, "新開地に着くまでの起こし直しは振動だけ")
            assertTrue(ring.message.contains("あと"), ring.message)
        }
        assertEquals(listOf("22:47", "22:48", "22:49", "22:50", "22:51", "22:52"), times)
    }

    @Test
    fun pressingAwakeStopsRepeats() {
        var s = started()
        s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_PRE, t("22:46"))).state
        s = engine.reduce(s, TripEvent.UserAwake(t("22:46"))).state
        val tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.WAKE_REPEAT_SHINKAICHI, t("22:47")))
        assertTrue(tr.actions.isEmpty(), "起きたを押したら起こし直さない")
        // 本アラームは鳴る（振動だけ）
        val main = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_MAIN, t("22:53")))
        assertEquals(0, main.actions.filterIsInstance<TripAction.Ring>().single().level)
    }

    @Test
    fun shinkaichiMainIsVibrationOnlyButKitaHasSound() {
        val s = started()
        val main = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_MAIN, t("22:53")))
        assertEquals(0, main.actions.filterIsInstance<TripAction.Ring>().single().level)
        // 新開地に着いてからの北鈴蘭台の予告は音あり
        var k = engine.reduce(s, TripEvent.LocationChanged(tt.stations.getValue(StationId.SHINKAICHI), t("22:55"))).state
        k = engine.reduce(k, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:56"))).state
        val kitaPre = engine.reduce(k, TripEvent.AlarmFired(AlarmKind.KITA_PRE, t("23:10")))
        assertEquals(1, kitaPre.actions.filterIsInstance<TripAction.Ring>().single().level)
    }

    @Test
    fun sirenOnlyAfterTenOvershootRings() {
        var s = started()
        // 乗り過ごし1回目（高速神戸側の位置）はアラーム音
        var tr = engine.reduce(s, TripEvent.LocationChanged(tt.stations.getValue(StationId.KOSOKU_KOBE), t("22:56")))
        val levels = mutableListOf(tr.actions.filterIsInstance<TripAction.Ring>().single().level)
        s = tr.state
        // そのあと1分おきに鳴らし続ける（位置なし＝乗ったまま）
        for (i in 1..12) {
            tr = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:56") + i))
            levels += tr.actions.filterIsInstance<TripAction.Ring>().single().level
            s = tr.state
        }
        // 1〜10回目はアラーム音、11回目からサイレン
        assertEquals(List(10) { 1 } + List(3) { 3 }, levels)
    }

    @Test
    fun awakeFromEarlierStationDoesNotCountForKita() {
        var s = started()
        // 新開地の前に起きた → 北鈴蘭台の予告のあとは、また起こし直す
        s = engine.reduce(s, TripEvent.UserAwake(t("22:46"))).state
        s = engine.reduce(s, TripEvent.LocationChanged(tt.stations.getValue(StationId.SHINKAICHI), t("22:55"))).state
        s = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.SHINKAICHI_CHECK, t("22:56"))).state
        s = engine.reduce(s, TripEvent.MotionChanged(Motion.IN_VEHICLE, t("22:58"))).state
        val pre = engine.reduce(s, TripEvent.AlarmFired(AlarmKind.KITA_PRE, t("23:10")))
        val rep = engine.reduce(pre.state, TripEvent.AlarmFired(AlarmKind.WAKE_REPEAT_KITA, t("23:11")))
        assertTrue(rep.actions.any { it is TripAction.Ring }, "北鈴蘭台の前はまた起こし直す")
    }
}
