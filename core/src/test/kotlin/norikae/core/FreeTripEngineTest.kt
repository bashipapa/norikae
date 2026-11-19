package norikae.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** どこからでもモード（大阪などから三宮・新開地で乗り換えて帰る）のテスト。駅の位置は時刻表の初期値 */
class FreeTripEngineTest {
    private val st = loadRealTimetable().stations
    private fun at(id: StationId) = st.getValue(id)

    // テスト用の通過点（おおよその位置）
    private val ashiya = LatLon(34.7336, 135.3036)
    /** 阪神 岩屋（ここから三宮・新開地まで地下） */
    private val iwaya = LatLon(34.7052, 135.2163)
    private val motomachi = LatLon(34.6887, 135.1867)
    private val nagata = LatLon(34.6890, 135.1560)
    private val hiyodorigoe = LatLon(34.7000, 135.1520)
    private val nishidai = LatLon(34.6655, 135.1478)
    private val higashiSuma = LatLon(34.6560, 135.1230)
    /** JR 元町・神戸・兵庫・新長田（高架なので位置が取れる） */
    private val jrMotomachi = LatLon(34.6900, 135.1875)
    private val jrKobe = LatLon(34.6794, 135.1780)
    private val jrHyogo = LatLon(34.6727, 135.1627)
    private val jrShinNagata = LatLon(34.6575, 135.1420)
    /** 神鉄 花山（谷上の北、有馬方面） */
    private val hanayama = LatLon(34.7700, 135.1620)

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
        fun gotOff() = apply(TripEvent.UserTransferring(t))
        fun via(v: StationId?) = apply(TripEvent.UserChoseVia(v, t))
        fun minute(n: Int = 1) { t += n }

        /** 1分ごとに1点ずつ進む */
        fun ride(points: List<LatLon>) = points.forEach { loc(it); minute() }

        /** 位置が取れないまま [n] 分：1分おきの見直しだけ届く */
        fun dark(n: Int) = repeat(n) { minute(); tick() }

        fun ringsAbout(word: String) = rings.filter { word in it.message }
        fun ringsOf(kind: RingKind) = rings.filter { it.kind == kind }
    }

    // ---- 位置が取れている基本の流れ ----

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
        // 新神戸で位置が取れたら、そこから谷上まで7分（Yahoo!路線情報）に見直す
        assertEquals(s.t.minutes + 7, s.state.expectedArr)
        assertTrue(boarded.minutes + 10 - s.state.expectedArr!! in -1..1)

        // トンネルで位置が取れない → 時刻で谷上の予告（振動）・本アラーム（音）
        s.rings.clear()
        s.dark(8)
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
        assertTrue(s.ringsOf(RingKind.CHECK).isEmpty(), "確認アラームが鳴った: ${s.ringsOf(RingKind.CHECK)}")

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
        s.awake(); s.motion(Motion.WALKING); s.minute(); s.motion(Motion.IN_VEHICLE)
        s.rings.clear()
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.MINATOGAWA), nagata, hiyodorigoe, at(StationId.SUZURANDAI), at(StationId.KITA_SUZURANDAI)))
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(StationId.SHINKAICHI, s.state.kitaFrom)
        assertTrue(s.ringsAbout("北鈴蘭台に着きます").isNotEmpty())
        assertTrue(s.rings.none { it.kind == RingKind.OVERSHOT_SHINKAICHI || it.kind == RingKind.CHECK }, "誤って鳴った: ${s.rings}")

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
    fun viaTanigamiWaitsAtSannomiyaForTransfer() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        assertTrue(s.ringsAbout("三宮に着きます").isNotEmpty())
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        s.motion(Motion.WALKING); s.minute(3); s.motion(Motion.IN_VEHICLE)
        assertEquals(FreePhase.TO_TANIGAMI, s.state.phase)
        assertEquals(s.t.minutes + 10, s.state.expectedArr)
    }

    @Test
    fun overshootShinkaichiWestwardThenGetOffAndReturn() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.rings.clear()
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.DAIKAI), nishidai, stepM = 400.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.rings.any { it.kind == RingKind.OVERSHOT_SHINKAICHI && it.level == 1 })

        // 降りて歩いた → 新開地に戻る案内。戻りは駅のすぐ手前で本アラーム（降りた場所では鳴らない）
        s.rings.clear()
        s.motion(Motion.WALKING)
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.notes.any { "新開地に戻って" in it })
        s.loc(nishidai); s.minute()
        assertTrue(s.rings.isEmpty(), "降りた場所で鳴った: ${s.rings}")
        s.ride(path(nishidai, at(StationId.DAIKAI), at(StationId.SHINKAICHI), stepM = 400.0))
        assertTrue(s.ringsAbout("新開地に着きます").isNotEmpty())
    }

    @Test
    fun waitingAtShinkaichiAfterWalkingIsQuiet() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.rings.clear()
        s.motion(Motion.WALKING)
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
        repeat(2) { s.minute(); s.tick() }
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
        s.motion(Motion.WALKING); s.minute(2); s.motion(Motion.IN_VEHICLE)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHIN_KOBE), at(StationId.TANIGAMI), stepM = 1500.0))
        assertTrue(s.ringsAbout("谷上に着きます").isNotEmpty())
        // 谷上で降りずに折り返し（位置は取れている）
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

    // ---- 位置が取れない（地下・トンネル）ときに鳴らないことがないか ----

    /** 阪神で大阪から新開地経由。岩屋から先は地下で位置が来ない → 時刻で予告・本アラーム、降りなければ確認アラーム */
    @Test
    fun hanshinViaShinkaichiUndergroundRingsByTime() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(ashiya, iwaya, stepM = 600.0))
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase, "10km以内で近づいたら区間に入る")
        val eta = assertNotNull(s.state.expectedArr)
        // 岩屋→三宮 約4分 ＋ 三宮→新開地 8分 ≒ 12分（Yahoo!路線情報の所要と同じくらい）
        val left = eta - s.t.minutes
        assertTrue(left in 10..14, "到着見込みが ${left}分")
        s.rings.clear()
        s.dark(left)
        assertTrue(s.ringsAbout("まもなく新開地").any { it.level == 0 && "推定" in it.message }, "時刻での予告がない: ${s.rings}")
        assertTrue(s.ringsAbout("新開地に着きます").any { it.level == 1 && "推定" in it.message }, "時刻での本アラームがない: ${s.rings}")
        assertNotNull(s.state.arrivedAt, "着いたとみなしていない")
        // 降りた動きが無い → 3分後から確認アラーム（音）が毎分
        s.rings.clear()
        s.dark(3)
        assertEquals(1, s.ringsOf(RingKind.CHECK).size, "確認アラーム: ${s.rings}")
        assertEquals(1, s.ringsOf(RingKind.CHECK).first().level)
        s.dark(2)
        assertEquals(3, s.ringsOf(RingKind.CHECK).size)
        // 降りて歩いた → 止まる。神戸電鉄に乗ったら北鈴蘭台へ
        s.motion(Motion.WALKING)
        s.dark(2)
        assertEquals(3, s.ringsOf(RingKind.CHECK).size, "歩いたあとも鳴った")
        s.motion(Motion.IN_VEHICLE)
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(StationId.SHINKAICHI, s.state.kitaFrom)
        assertEquals(s.t.minutes + 19, s.state.expectedArr)
    }

    /** 新開地に着いたはずの時刻のあと、東須磨で位置が戻った → 乗り過ごし */
    @Test
    fun locationReturnsPastShinkaichiIsOvershoot() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(ashiya, iwaya, stepM = 600.0))
        val eta = s.state.expectedArr!!
        s.dark(eta - s.t.minutes + 2)
        s.rings.clear()
        s.loc(higashiSuma); s.minute(); s.loc(LatLon(higashiSuma.lat - 0.001, higashiSuma.lon - 0.003))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.ringsOf(RingKind.OVERSHOT_SHINKAICHI).isNotEmpty())
    }

    /** 確認アラームも10回を超えたらサイレン */
    @Test
    fun checkRingsEscalateToSiren() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(ashiya, iwaya, stepM = 600.0))
        val eta = s.state.expectedArr!!
        s.dark(eta - s.t.minutes + 1)
        s.rings.clear()
        s.dark(15)
        val checks = s.ringsOf(RingKind.CHECK)
        assertTrue(checks.size >= 11, "確認が ${checks.size} 回")
        assertTrue(checks.take(10).all { it.level == 1 })
        assertEquals(3, checks[10].level)
    }

    /** 三宮経由で阪神。岩屋から地下 → 三宮は時刻で本アラーム。降りた動きが無ければ確認アラーム、6分で新開地の起こしも入る */
    @Test
    fun hanshinViaTanigamiUndergroundSannomiyaByTimeThenCheck() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, iwaya, stepM = 600.0))
        assertEquals(FreePhase.TO_SANNOMIYA, s.state.phase)
        val eta = s.state.expectedArr!!
        val left = eta - s.t.minutes
        assertTrue(left in 3..5, "三宮までの見込み ${left}分")
        s.rings.clear()
        s.dark(left)
        assertTrue(s.ringsAbout("三宮に着きます").any { it.level == 1 }, "三宮の本アラームがない: ${s.rings}")
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        // 降りた動きが無い → 3分で確認アラーム
        s.rings.clear()
        s.dark(3)
        assertEquals(1, s.ringsOf(RingKind.CHECK).size, "${s.rings}")
        assertTrue("三宮で降りましたか" in s.ringsOf(RingKind.CHECK).first().message)
        // 6分で新開地の区間にも入る（三宮着＋8分で新開地）
        s.dark(3)
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertEquals(StationId.SHINKAICHI, s.state.via)
        assertEquals(s.state.arrivedAt, null)
        val shinkaichiEta = s.state.expectedArr!!
        assertTrue(shinkaichiEta - s.t.minutes in 1..3, "新開地まで ${shinkaichiEta - s.t.minutes}分")
        s.rings.clear()
        s.dark(3)
        assertTrue(s.ringsAbout("新開地に着きます").isNotEmpty(), "${s.rings}")
    }

    /** 三宮経由で三宮に着いて歩いた → 乗ったのが取れなくても5分で地下鉄に乗ったとみなして谷上を時刻で起こす */
    @Test
    fun walkedAtSannomiyaWithoutBoardingDetectionStillRingsTanigami() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        s.awake(); s.motion(Motion.WALKING)
        val walked = s.t.minutes
        s.dark(5)
        assertEquals(FreePhase.TO_TANIGAMI, s.state.phase)
        assertEquals(walked + 5 + 10, s.state.expectedArr)
        s.rings.clear()
        s.dark(10)
        assertTrue(s.ringsAbout("谷上に着きます").isNotEmpty(), "${s.rings}")
        assertTrue(s.ringsOf(RingKind.CHECK).isEmpty())
    }

    /** JR で三宮経由のつもりが三宮で降りず神戸へ（高架で位置あり）→ 三宮の降りそびれを鳴らし、新開地（神戸駅）で起こす */
    @Test
    fun jrPastSannomiyaWithViaTanigamiRingsOvershootSannomiya() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        s.rings.clear()
        s.ride(path(at(StationId.SANNOMIYA), jrMotomachi, jrKobe, stepM = 400.0))
        assertTrue(s.ringsOf(RingKind.OVERSHOT_SANNOMIYA).isNotEmpty(), "${s.rings}")
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.ringsAbout("新開地に着きます").isNotEmpty(), "神戸駅（新開地まで約800m）で本アラーム: ${s.rings}")
        // さらに兵庫・新長田へ → 乗り過ごし
        s.rings.clear()
        s.ride(path(jrKobe, jrHyogo, jrShinNagata, stepM = 500.0))
        assertEquals(FreePhase.OVERSHOT, s.state.phase)
        assertTrue(s.ringsOf(RingKind.OVERSHOT_SHINKAICHI).isNotEmpty())
    }

    /** おまかせで三宮を通過（地下で位置なし・動きなし）→ 3分で新開地へ向かっているとみなし、時刻で起こす */
    @Test
    fun anyViaRidingThroughSannomiyaUndergroundAssumesShinkaichi() {
        val s = Sim()
        s.ride(path(ashiya, iwaya, stepM = 600.0))
        val eta = s.state.expectedArr!!
        s.dark(eta - s.t.minutes)
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        val arrived = s.state.arrivedAt!!
        s.rings.clear()
        s.dark(3)
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertEquals(arrived + 8, s.state.expectedArr)
        assertTrue(s.ringsOf(RingKind.CHECK).isEmpty(), "おまかせでは三宮の確認は鳴らさない")
        s.dark(6)
        assertTrue(s.ringsAbout("新開地に着きます").any { it.level == 1 }, "${s.rings}")
    }

    /** 谷上で本アラームのあと寝たまま → トンネルで位置が消えても確認アラームが鳴り続ける */
    @Test
    fun tanigamiNoWalkingAndNoLocationRingsCheck() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        s.motion(Motion.WALKING); s.minute(2); s.motion(Motion.IN_VEHICLE)
        s.dark(7)
        s.loc(at(StationId.TANIGAMI))
        assertTrue(s.ringsAbout("谷上に着きます").isNotEmpty())
        assertNotNull(s.state.arrivedAt)
        s.rings.clear()
        s.dark(5)
        assertTrue(s.ringsOf(RingKind.CHECK).size >= 2, "${s.rings}")
        assertTrue("谷上" in s.ringsOf(RingKind.CHECK).first().message)
        // 「谷上で降りた」ボタン → 止まって北鈴蘭台へ
        s.gotOff()
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(StationId.TANIGAMI, s.state.kitaFrom)
        s.rings.clear()
        s.dark(3)
        assertTrue(s.ringsOf(RingKind.CHECK).isEmpty())
    }

    /** 谷上から有馬方面（北）に乗ってしまった → 離れていくので電車違いを鳴らす */
    @Test
    fun wrongDirectionFromTanigamiRingsWrongLine() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        s.motion(Motion.WALKING); s.minute(2); s.motion(Motion.IN_VEHICLE)
        s.dark(7)
        s.loc(at(StationId.TANIGAMI)); s.minute()
        s.motion(Motion.WALKING); s.minute(3); s.motion(Motion.IN_VEHICLE)
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        s.rings.clear()
        s.ride(path(at(StationId.TANIGAMI), hanayama, LatLon(34.7850, 135.1500), stepM = 500.0))
        assertTrue(s.ringsOf(RingKind.WRONG_LINE).isNotEmpty(), "${s.rings}")
    }

    /** 三宮に着いてから「新開地経由」に変えた → すぐ新開地の区間に入り、時刻で起こす */
    @Test
    fun switchingToShinkaichiAtSannomiyaStartsShinkaichiLeg() {
        val s = Sim(via = StationId.TANIGAMI)
        s.ride(path(ashiya, at(StationId.SANNOMIYA)))
        assertEquals(FreePhase.AT_SANNOMIYA, s.state.phase)
        s.minute(); s.via(StationId.SHINKAICHI)
        assertEquals(FreePhase.TO_SHINKAICHI, s.state.phase)
        assertTrue(s.state.expectedArr!! - s.t.minutes in 2..8)
        s.rings.clear()
        s.dark(8)
        assertTrue(s.ringsAbout("新開地に着きます").isNotEmpty(), "${s.rings}")
    }

    /** 新開地で「降りた」ボタン → 確認アラームは鳴らず、北鈴蘭台の区間へ */
    @Test
    fun gotOffButtonAtShinkaichiMovesToKita() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.gotOff()
        assertEquals(FreePhase.TO_KITA, s.state.phase)
        assertEquals(s.t.minutes + 19, s.state.expectedArr)
        s.rings.clear()
        s.dark(5)
        assertTrue(s.ringsOf(RingKind.CHECK).isEmpty())
    }

    /** 新開地で歩いたのが取れないまま神戸電鉄に乗った → 長田あたりで位置が戻れば確認は止まり北鈴蘭台へ */
    @Test
    fun shintetsuLocationStopsCheckRings() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.dark(3)
        assertTrue(s.ringsOf(RingKind.CHECK).isNotEmpty())
        s.loc(nagata)
        assertEquals(FreePhase.TO_KITA, s.state.phase)
    }

    /** 北鈴蘭台に着いて歩いた（位置なし）→ 終了。着いても歩かなければ確認アラーム */
    @Test
    fun kitaArrivalByTimeThenWalkingEnds() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.gotOff()
        val eta = s.state.expectedArr!!
        s.dark(eta - s.t.minutes + 1)
        assertNotNull(s.state.arrivedAt)
        s.motion(Motion.WALKING)
        s.dark(2)
        assertEquals(FreePhase.DONE, s.state.phase)
    }

    @Test
    fun kitaNoWalkingRingsCheck() {
        val s = Sim(via = StationId.SHINKAICHI)
        s.ride(path(at(StationId.SANNOMIYA), at(StationId.SHINKAICHI), stepM = 500.0))
        s.gotOff()
        s.ride(path(at(StationId.SHINKAICHI), at(StationId.MINATOGAWA), nagata, hiyodorigoe, at(StationId.SUZURANDAI), at(StationId.KITA_SUZURANDAI)))
        s.rings.clear()
        // 北鈴蘭台に停まったまま（位置は駅、動きは乗り物のまま）
        repeat(4) { s.loc(at(StationId.KITA_SUZURANDAI)); s.minute() }
        assertTrue(s.ringsOf(RingKind.CHECK).isNotEmpty(), "${s.rings}")
    }
}
