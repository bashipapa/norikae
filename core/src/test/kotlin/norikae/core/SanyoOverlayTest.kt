package norikae.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SanyoOverlayTest {
    private val base = File(System.getProperty("timetable.path")).readText()

    /** 形式確認用の架空データ（実際の時刻ではない） */
    private val overlay = """
        {
          "sanyo": {
            "weekday": [
              { "dep": "21:00", "type": "普通", "dest": "神戸三宮", "shinkaichi": "22:10" },
              { "dep": "21:10", "type": "普通", "dest": "東二見", "transferAt": "東二見",
                "shinkaichi": "22:00", "transferTrainType": "直通特急", "transferTrainDest": "大阪梅田(阪神線)",
                "transferNote": "東二見で直通特急に乗り換え" },
              { "dep": "23:30", "type": "普通", "dest": "東二見", "transferAt": "東二見", "shinkaichi": null }
            ],
            "holiday": []
          }
        }
    """.trimIndent()

    @Test
    fun overlayReplacesSanyo() {
        val tt = Timetable.parse(base, overlay)
        assertEquals(3, tt.weekday.sanyo.size)
        assertTrue(tt.holiday.sanyo.isEmpty())
        // 神戸電鉄はそのまま
        assertEquals(tt.weekday.shintetsu, Timetable.parse(base).weekday.shintetsu)
    }

    @Test
    fun transferToDirectLimitedIsUmedaRiskAndNotLocalOnly() {
        val train = Timetable.parse(base, overlay).weekday.sanyo[1]
        assertEquals(TrainType.DIRECT_LIMITED, train.transferTrainType)
        assertTrue(train.goesToUmeda)
        assertFalse(train.isLocalOnly)
        val plain = Timetable.parse(base, overlay).weekday.sanyo[0]
        assertFalse(plain.goesToUmeda)
        assertTrue(plain.isLocalOnly)
    }

    @Test
    fun localPreferenceConsidersTransferTrain() {
        val planner = RoutePlanner(Timetable.parse(base, overlay))
        val routes = planner.plan(DayType.WEEKDAY, t("21:00"))
        // 23:30 は新開地に行けないので除外
        assertEquals(listOf("21:00", "21:10"), routes.map { it.sanyo!!.dep.toString() })
        // 21:10（直特乗り換え）: 22:00着→22:12→22:30着 / 21:00（普通）: 22:10着→22:27→22:45着。差15分以内なので普通
        assertEquals("21:00", planner.recommend(routes, 15)!!.sanyo!!.dep.toString())
        assertTrue(routes[1].umedaRisk)
    }

    @Test
    fun withNoOverlayKeepsBase() {
        assertTrue(Timetable.parse(base, null).weekday.sanyo.isEmpty())
    }

    /** ローカルの実データがあれば、最低限の整合性を確認する（CI などで無いときは何もしない） */
    @Test
    fun localFileIsConsistentIfPresent() {
        val file = File(System.getProperty("sanyo.local.path"))
        if (!file.exists()) return
        val tt = Timetable.parse(base, file.readText())
        for (day in listOf(tt.weekday, tt.holiday)) {
            assertTrue(day.sanyo.isNotEmpty())
            day.sanyo.forEach { s ->
                s.shinkaichiArr?.let { assertTrue(it > s.dep, "新開地着が荒井発より後: $s") }
                if (s.transferAt != null) assertNotNull(s.transferNote, "乗り換えの説明: $s")
            }
            val planner = RoutePlanner(tt)
            assertNotNull(planner.lastRoute(DayType.WEEKDAY))
        }
    }
}
