package norikae.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** どこからでもモード（大阪などから三宮・新開地で乗り換えて帰る）のテスト。駅の位置は時刻表の初期値 */
class FreeTripEngineTest {
    private val st = loadRealTimetable().stations
    private fun at(id: StationId) = st.getValue(id)

    // テスト用の通過点（おおよその位置）
    private val ashiya = LatLon(34.7336, 135.3036)
    private val motomachi = LatLon(34.6887, 135.1867)
    private val nagata = LatLon(34.6890, 135.1560)
    private val hiyodorigoe = LatLon(34.7000, 135.1520)
    private val nishidai = LatLon(34.6655, 135.1478)

    /** a→b を約 [stepM] m ごとに区切った点（a は含まず b は含む） */
    private fun path(vararg points: LatLon, stepM: Double = 700.0): List<LatLon> = buildList {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val n = maxOf(1, (a.distanceTo(b) / stepM).toInt())
            for (k in 1..n) add(LatLon(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n))
        }
    }

    private inner class Sim(via: StationId? = null) {
        val engine = FreeTripEngine(StationLocations(st))
        var t = t("22:00")
        val rings = mutableListOf<TripAction.Ring>()
        val notes = mutableListOf<String>()
        var ticks = 0
        var state = engine.start("2026-11-19", t, via).state

        private fun apply(e: TripEvent) {
            val tr = engine.reduce(state, e)
            state = tr.state
            tr.actions.forEach {
                when (it) {
                    is TripAction.Ring -> rings += it
                    is TripAction.Notify -> notes += it.message
                    is TripAction.Schedule -> if (it.alarms.any { a -> a.kind == AlarmKind.FREE_TICK }) ticks++
                    else -> Unit
                }
            }
        }

        fun loc(p: LatLon) = apply(TripEvent.LocationChanged(p, t))
        fun tick() = apply(TripEvent.AlarmFired(AlarmKind.FREE_TICK, t))
        fun motion(m: Motion) = apply(TripEvent.MotionChanged(m, t))
        fun awake() = apply(TripEvent.UserAwake(t))
        fun via(v: StationId?) = apply(TripEvent.UserChoseVia(v, t))
        fun minute(n: Int = 1) { t += n }

        /** 1分ごとに1点ずつ進む */
        fun ride(points: List<LatLon>) = points.forEach { loc(it); minute() }

        fun ringsAbout(word: String) = rings.filter { word in it.message }
    }

    @Test
    fun osakaToSannomiyaThenTanigamiThenKita() {
        val s = Sim()
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        // 三宮：予告は振動だけ、本アラームは音あり
        val sannomiya = s.ringsAbout("三宮")
        assertTrue(sannomiya.isNotEmpty(), "三宮で鳴っていない")
        assertEquals(0, sannomiya.first().level)
        assertTrue("まもなく三宮" in sannomiya.first().message)
        assertTrue(sannomiya.any { it.level == 1 && "三宮に着きます" in it.message })
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)

        // 三宮で降りて地下鉄に乗り換え
        s.awake(); s.motion(Motion.WALKING); s.minute(3)
        s.motion(Motion.IN_VEHICLE)
        val boarded = s.t
        s.minute(2)
        s.loc(at(StationId.SHIN_KOBE))
        assertEquals(FreePhase.TO_TANIGAMI, s.state.phase)
        assertEquals(boarded.minutes + 10, s.state.expectedArr)

        // トンネルで位置が取れない → 時刻で谷上の予告（振動）・本アラーム（音）
        s.rings.clear()
        repeat(8) { s.minute(); s.tick() }
        val tanigami = s.ringsAbout("谷上")
        assertTrue(tanigami.any { it.level == 0 && "まもなく終点の谷上" in it.message }, "谷上の予告がない: $tanigami")
        assertTrue(tanigami.any { it.level == 1 && "谷上に着きます" in it.message }, "谷上の本アラームがない: $tanigami")

        // 谷上で神戸電鉄に乗り換えて北鈴蘭台へ
        s.loc(at(StationId.TANIGAMI)); s.awake()
        s.motion(Motion.WALKING); s.minute(4); s.motion(Motion.IN_VEHICLE)
        s.rings.clear()
        s.ride(path(at(StationId.TANIGAMI), at(StationId.YAMANOMACHI), at(StationId.KITA_SUZURANDAI), stepM = 400.0))
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(StationId.TANIGAMI, s.state.kitaFrom)
        val kita = s.ringsAbout("北鈴蘭台")
        // 北鈴蘭台は予告から音あり
        assertTrue(kita.any { it.level == 1 && "まもなく北鈴蘭台" in it.message }, "北鈴蘭台の予告がない: $kita")
        assertTrue(kita.any { "北鈴蘭台に着きます" in it.message })

        s.motion(Motion.WALKING)
        s.loc(at(StationId.KITA_SUZURANDAI))
        assertEquals(FreePhase.DONE, s.state.phase)
    }

    @Test
    fun throughSannomiyaToShinkaichiThenOvershootKitaAtYamanomachi() {
        val s = Sim()
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        s.awake()
        s.rings.clear()
        // 三宮で降りずにそのまま新開地へ（阪神・阪急の直通）
        s.ride(path(at(StationId.SANNOMIYA), motomachi, at(StationId.KOSOKU_KOBE), at(StationId.SHINKAICHI), stepM = 500.0))
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.state.viaSannomiya)
        val shinkaichi = s.ringsAbout("新開地")
        assertEquals(0, shinkaichi.first().level, "新開地の予告は振動だけ")
        assertTrue(shinkaichi.any { it.level == 1 && "新開地に着きます" in it.message })

        // 神戸電鉄で北へ
        s.awake()
        s.rings.clear()
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.MINATOGAWA), nagata, hiyodorigoe, at(StationId.SUZURANDAI), at(StationId.KITA_SUZURANDAI)))
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(StationId.SHINKAICHI, s.state.kitaFrom)
        assertTrue(s.ringsAbout("北鈴蘭台に着きます").isNotEmpty())
        assertTrue(s.rings.none { it.kind == RingKind.OVERSHOT_SHINKAICHI }, "新開地の乗り過ごしと誤判定: ${s.rings}")

        // 北鈴蘭台を過ぎて山の街へ → 乗り過ごし
        s.rings.clear()
        s.ride(path(at(StationId.KITA_SUZURANDAI), at(StationId.YAMANOMACHI), stepM = 300.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.rings.any { it.kind == RingKind.OVERSHOT_KITA })
    }

    @Test
    fun viaShinkaichiSkipsSannomiyaAlarm() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA), motomachi, at(StationId.KOSOKU_KOBE), at(StationId.SHINKAICHI), stepM = 500.0))
        assertTrue(s.ringsAbout("三宮").isEmpty(), "三宮で鳴った: ${s.ringsAbout("三宮")}")
        assertTrue(s.ringsAbout("まもなく新開地").isNotEmpty())
        assertTrue(s.ringsAbout("新開地に着きます").isNotEmpty())
    }

    @Test
    fun viaTanigamiGoesToTanigamiLegAtSannomiya() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        assertTrue(s.ringsAbout("三宮に着きます").isNotEmpty())
        assertEquals(FreePhase.TO_TANIGAMI, s.state.phase)
    }

    @Test
    fun overshootShinkaichiWestwardThenGetOffAndReturn() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.rings.clear()
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.DAIKAI), nishidai, stepM = 400.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.rings.any { it.kind == RingKind.OVERSHOT_SHINKAICHI && it.level == 1 })

        // 降りて歩いた → 新開地に戻る案内
        s.motion(Motion.WALKING)
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.notes.any { "新開地に戻って" in it })
    }

    @Test
    fun waitingAtShinkaichiIsNotOvershoot() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.rings.clear()
        // 新開地のホームで待っている（地下なので位置が数百mぶれる）
        val c = at(StationId.SHINKAICHI)
        listOf(0.002, -0.002, 0.0025, -0.001, 0.0).forEach { s.loc(LatLon(c.lat + it, c.lon - it)); s.minute() }
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.rings.isEmpty(), "鳴った: ${s.rings}")
    }

    @Test
    fun wakeRepeatsEveryMinuteUntilAwake() {
        val s = Sim()
        // 三宮まで3km の手前まで来て止まる（駅間で停車）
        val points = path(ashiya, at(StationId.SANNOMIYA), stepM = 500.0)
        val idx = points.indexOfFirst { it.distanceTo(at(StationId.SANNOMIYA)) <= 3000 }
        s.ride(points.take(idx + 1))
        assertEquals(1, s.ringsAbout("まもなく三宮").size)
        assertTrue(s.ticks > 0, "1分後の見直しを予約していない")
        repeat(3) { s.tick(); s.minute() }
        assertEquals(3, s.ringsAbout("起きて！まもなく三宮").size)
        assertTrue(s.ringsAbout("起きて！").all { it.level == 0 }, "起こし直しは振動だけ")
        s.awake()
        repeat(3) { s.minute(); s.tick() }
        assertEquals(3, s.ringsAbout("起きて！まもなく三宮").size, "起きたあとも鳴った")
    }

    @Test
    fun sirenAfterTenOvershootRings() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.DAIKAI), nishidai, stepM = 400.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        repeat(12) { s.tick(); s.minute() }
        val over = s.rings.filter { it.kind == RingKind.OVERSHOT_SHINKAICHI }
        assertTrue(over.size >= 11)
        assertTrue(over.take(10).all { it.level == 1 }, "10回目まではアラーム音")
        assertEquals(3, over[10].level, "11回目からサイレン")
    }

    @Test
    fun stayingOnTrainBackFromTanigamiIsOvershoot() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHIN_KOBE), at(StationId.TANIGAMI), stepM = 1500.0))
        assertTrue(s.ringsAbout("谷上に着きます").isNotEmpty())
        // 谷上で降りずに折り返し
        s.rings.clear()
        s.ride(path(at(StationId.TANIGAMI), at(StationId.SHIN_KOBE), stepM = 1500.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.rings.any { it.kind == RingKind.OVERSHOT_TANIGAMI })
    }

    @Test
    fun startingNearSannomiyaDoesNotRingSannomiya() {
        val s = Sim()
        s.loc(LatLon(at(StationId.SANNOMIYA).lat + 0.002, at(StationId.SANNOMIYA).lon))
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        assertTrue(s.rings.isEmpty())
    }

    @Test
    fun staleLocationDoesNotRing() {
        val s = Sim()
        val points = path(ashiya, at(StationId.SANNOMIYA), stepM = 500.0)
        s.ride(points.take(points.size - 8))
        val before = s.rings.size
        // 位置が止まったまま5分（古い位置では判定しない）
        repeat(5) { s.minute(); s.tick() }
        assertTrue(s.rings.drop(before).none { "三宮に着きます" in it.message })
    }

    @Test
    fun stateSurvivesJson() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)).take(10))
        assertEquals(s.state, FreeState.fromJson(s.state.toJson()))
    }

    @Test
    fun autoEndsAfterFourHours() {
        val s = Sim()
        s.minute(241)
        s.tick()
        assertEquals(FreePhase.DONE, s.state.phase)
    }
}
