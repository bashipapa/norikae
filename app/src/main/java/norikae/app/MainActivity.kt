package norikae.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import norikae.core.DrinkingModeRule
import norikae.core.LatLon
import norikae.core.RingKind
import norikae.core.Route
import norikae.core.ServiceTime
import norikae.core.StationId
import norikae.core.TrainType
import norikae.core.TripEvent
import norikae.core.isWeekday

class MainActivity : ComponentActivity() {

    /** 設定画面から戻ったときに権限の表示を更新するため */
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ringer.ensureChannel(this)
        Notifier.ensureChannel(this)
        Sensing.registerAll(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MainScreen() } } }
    }

    @Composable
    private fun MainScreen() {
        // 30秒ごと、または操作のたびに作り直す
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
        val refresh: () -> Unit = { tick++ }

        val ctx = this
        val (date, now) = remember(tick) { TripController.now() }
        val serviceDay = Store.isServiceDay(ctx, date)
        val weekday = isWeekday(date)
        val timetable = Store.timetable(ctx)
        val planner = TripController.planner(ctx)
        val routes = planner.plan(now)
        val recommended = planner.recommend(routes, Store.localTolerance(ctx))
        val trip = Store.trip(ctx)
        val sanyoMissing = timetable.sanyo.isEmpty()
        var confirmRoute by remember { mutableStateOf<Route?>(null) }

        confirmRoute?.let { r ->
            AlertDialog(
                onDismissRequest = { confirmRoute = null },
                title = { Text("この電車でアラームを設定しますか？") },
                text = {
                    Text(
                        (r.sanyo?.let { "荒井 ${it.dep}発 → " } ?: "") +
                            (r.sanyo?.transferAt?.let { "${shortName(it)}で乗換 → " } ?: "") +
                            (r.shinkaichiArr?.let { "新開地 ${it}着 → " } ?: "") +
                            "神鉄 ${r.shintetsu.dep}発 → 北鈴蘭台 ${r.kitaArr}着",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        TripController.start(ctx, r)
                        toast("アラームを設定しました")
                        confirmRoute = null
                        refresh()
                    }) { Text("設定する") }
                },
                dismissButton = { TextButton(onClick = { confirmRoute = null }) { Text("やめる") } },
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("荒井 → 新開地 → 北鈴蘭台", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val drinking = DrinkingModeRule().isDrinkingTime(now)
                    Text(
                        "現在 $now｜" + if (drinking) "飲み会モードの時間帯（21時以降）" else "飲み会モードは21時から",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { PermissionCard() }

            if (trip != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("乗車中：${Notifier.phaseLabel(trip)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            trip.shinkaichiArr?.let { Text("新開地 ${ServiceTime(it)}着") }
                            Text("神戸電鉄 ${ServiceTime(trip.shintetsuDep)} ${trip.shintetsuDest}行き → 北鈴蘭台 ${ServiceTime(trip.kitaArr)}着")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    TripController.handle(ctx, TripEvent.UserFinished(TripController.now().second)); refresh()
                                }) { Text("北鈴蘭台で降りた") }
                                OutlinedButton(onClick = { TripController.end(ctx); refresh() }) { Text("中止") }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (serviceDay) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            when {
                                !weekday -> "今日は土日なので自動開始しません（平日専用。時刻表も平日ダイヤです）"
                                !serviceDay -> "「今日は休み」にしているので自動開始しません"
                                else -> "平日：21時以降に荒井から乗ると自動でアラームを設定します"
                            },
                            fontSize = 13.sp,
                        )
                        if (weekday) {
                            Row {
                                FilterChip(
                                    selected = !serviceDay,
                                    onClick = { Store.setDayOff(ctx, date, serviceDay); refresh() },
                                    label = { Text("今日は休み（祝日など）") },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (TripController.startFromBoardingNow(ctx)) {
                            toast("乗車時刻からアラームを設定しました")
                        } else if (sanyoMissing) {
                            toast("経路を決められません。下の「荒井→新開地の所要分」を設定してください")
                        } else {
                            toast("この時刻の電車では北鈴蘭台まで帰れません（終電後）")
                        }
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("今、荒井で乗った（この時刻でアラーム設定）") }
            }

            item {
                Column {
                    Text(
                        if (sanyoMissing) "新開地発 北鈴蘭台行きの候補（山陽の時刻表は未入力）" else "帰り道の候補（タップでアラーム設定）",
                        fontWeight = FontWeight.Bold,
                    )
                    planner.lastDepartureRoute()?.let {
                        Text("荒井発の最終：${it.sanyo!!.dep}発（北鈴蘭台 ${it.kitaArr}着）", fontSize = 13.sp)
                    }
                    planner.lastRoute()?.let {
                        Text("神戸電鉄の最終：新開地 ${it.shintetsu.dep}発 ${it.shintetsu.dest}行き（北鈴蘭台 ${it.kitaArr}着）", fontSize = 13.sp)
                    }
                }
            }

            if (routes.isEmpty()) {
                item { Text("この時刻以降に北鈴蘭台へ帰れる電車はありません") }
            }
            if (routes.isNotEmpty()) item { RouteHeader() }
            items(routes) { route ->
                RouteCard(route, recommended = route == recommended) { confirmRoute = route }
            }

            item { AlarmTestCard() }
            item { SettingsCard(onChanged = refresh) }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    /** 一覧の列見出し（荒井発 → 乗り換え → 新開地着 → 北鈴蘭台着） */
    @Composable
    private fun RouteHeader() {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("荒井発", "乗り換え", "新開地着", "北鈴蘭台着").forEachIndexed { i, label ->
                if (i > 0) Spacer(Modifier.width(ARROW_W))
                Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted)
            }
        }
    }

    @Composable
    private fun RouteCard(route: Route, recommended: Boolean, onClick: () -> Unit) {
        val s = route.sanyo
        Card(
            colors = if (recommended) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp)) {
                val badges = buildList {
                    if (recommended) add("おすすめ" to MaterialTheme.colorScheme.primary)
                    if (route.umedaRisk) add("乗り過ごすと梅田まで行きます" to RED)
                    if (route.shintetsu.transferAtSuzurandai) add("鈴蘭台で三田行きに乗換" to MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        badges.forEach { (t, c) -> Text(t, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // 荒井発
                    TimeCell(
                        Modifier.weight(1f),
                        main = s?.dep?.toString() ?: "—",
                        sub1 = s?.type?.label,
                        sub2 = s?.dest?.let { shortName(it) + "行" },
                        mainRed = s?.type?.isLimited() == true,
                        subRed = s?.type?.isLimited() == true,
                    )
                    Arrow()
                    // 乗り換え
                    if (s?.transferAt != null) {
                        val red = s.transferTrainType?.isLimited() == true
                        TimeCell(
                            Modifier.weight(1f),
                            main = shortName(s.transferAt!!),
                            sub1 = s.transferDep?.let { "${it}発" } ?: "新開地へ行けない",
                            sub2 = s.transferTrainType?.label,
                            mainSmall = true,
                            subRed = red,
                        )
                    } else {
                        TimeCell(Modifier.weight(1f), main = "なし", sub1 = "そのまま", sub2 = null, mainSmall = true)
                    }
                    Arrow()
                    // 新開地着
                    TimeCell(
                        Modifier.weight(1f),
                        main = route.shinkaichiArr?.toString() ?: "—",
                        sub1 = "神鉄${route.shintetsu.dep}発",
                        sub2 = shortName(route.shintetsu.dest) + "行",
                        mainRed = s?.arrivingType?.isLimited() == true,
                    )
                    Arrow()
                    // 北鈴蘭台着
                    TimeCell(
                        Modifier.weight(1f),
                        main = route.kitaArr.toString(),
                        sub1 = if (route.shinkaichiEstimated && route.shinkaichiArr != null) "新開地着は推定" else null,
                        sub2 = null,
                    )
                }
            }
        }
    }

    @Composable
    private fun TimeCell(
        modifier: Modifier,
        main: String,
        sub1: String?,
        sub2: String?,
        mainRed: Boolean = false,
        subRed: Boolean = false,
        mainSmall: Boolean = false,
    ) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(modifier) {
            Text(
                main,
                fontSize = if (mainSmall) 15.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (mainRed) RED else Color.Unspecified,
                maxLines = 1,
                modifier = if (mainSmall) Modifier.padding(top = 5.dp, bottom = 3.dp) else Modifier,
            )
            sub1?.let { Text(it, fontSize = 11.sp, color = if (subRed) RED else muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            sub2?.let { Text(it, fontSize = 11.sp, color = if (subRed) RED else muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }

    @Composable
    private fun Arrow() {
        Text(
            "→",
            modifier = Modifier.width(ARROW_W).padding(top = 6.dp),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun AlarmTestCard() {
        val ctx = this
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("アラームのテスト", fontWeight = FontWeight.Bold)
                Text("本番と同じ鳴らし方（アラーム音・バイブ・ロック画面に全画面表示）で鳴らします。音量は「アラーム音量」に従います", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Ringer.ring(ctx, RingKind.WAKE, "アラームのテストです。「起きた（止める）」で止まります")
                    }) { Text("今すぐ鳴らす") }
                    Button(onClick = {
                        AlarmScheduler.scheduleTest(ctx, 60)
                        toast("1分後に鳴ります。画面を消して待ってください")
                    }) { Text("1分後に鳴らす") }
                }
                Text("「1分後」は予約から鳴るまで本番と同じ流れです。画面を消して、ロック中に鳴るか確かめてください", fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun SettingsCard(onChanged: () -> Unit) {
        val ctx = this
        var runText by remember { mutableStateOf(Store.sanyoRunMinutes(ctx)?.toString() ?: "") }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("設定", fontWeight = FontWeight.Bold)
                Row {
                    OutlinedTextField(
                        value = runText,
                        onValueChange = { runText = it.filter(Char::isDigit).take(3) },
                        label = { Text("荒井→新開地の所要分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(220.dp),
                    )
                    TextButton(onClick = {
                        Store.saveSanyoRunMinutes(ctx, runText.toIntOrNull())
                        toast("保存しました"); onChanged()
                    }) { Text("保存") }
                }
                Text("山陽の時刻表が未入力の間は、乗車時刻＋この分数で新開地着を推定します", fontSize = 12.sp)

                Text("駅の位置", fontWeight = FontWeight.Bold)
                Text(
                    "初期値として各駅の位置（Yahoo!路線情報）が入っているので、登録しなくても判定に使います。" +
                        "ホームの位置などで判定がずれるときだけ、その駅にいるときに上書きしてください",
                    fontSize = 12.sp,
                )
                val saved = Store.savedStations(ctx)
                StationId.entries.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${id.label}：" + if (saved[id] != null) "自分で登録" else "初期値",
                            modifier = Modifier.width(150.dp),
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { registerHere(id, onChanged) }) { Text("今いる場所で上書き", fontSize = 12.sp) }
                        if (saved[id] != null) {
                            TextButton(onClick = {
                                Store.clearStation(ctx, id)
                                if (id == StationId.ARAI) Sensing.registerAraiGeofence(ctx)
                                onChanged()
                            }) { Text("戻す", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionCard() {
        val ctx = this
        var tick by remember { mutableIntStateOf(0) }
        val basic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Sensing.registerAll(ctx); tick++
        }
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            Sensing.registerAll(ctx); tick++
        }
        val basicPermissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingBasic = remember(tick, resumeTick) { basicPermissions.filterNot { Sensing.has(ctx, it) } }
        val missingBackground = remember(tick, resumeTick) { !Sensing.has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        val nm = getSystemService(NotificationManager::class.java)
        val missingFullScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()
        val am = getSystemService(AlarmManager::class.java)
        val missingExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()

        val requiredOk = missingBasic.isEmpty() && !missingFullScreen && !missingExact
        if (requiredOk && !missingBackground) return
        if (requiredOk) {
            // 必須はそろっている。「常に許可」は自動開始を使うときだけ必要
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("位置情報は「使用中のみ許可」です", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "画面から「この電車でアラーム設定」「今乗った」を押せば、アプリを閉じても画面を消しても動きます。" +
                            "荒井を出たら自動で始める機能を使うときだけ「常に許可」が必要です。",
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                        Text("自動開始を使う（「常に許可」にする）")
                    }
                }
            }
            return
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("権限の設定が必要です", fontWeight = FontWeight.Bold)
                if (missingBasic.isNotEmpty()) {
                    OutlinedButton(onClick = { basic.launch(missingBasic.toTypedArray()) }) {
                        Text("位置情報（使用中のみで可）・身体活動・通知を許可")
                    }
                }
                if (missingFullScreen) {
                    OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    }) { Text("ロック画面にアラームを全画面表示する許可") }
                }
                if (missingExact) {
                    OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                    }) { Text("正確なアラームを許可") }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHere(id: StationId, onChanged: () -> Unit) {
        if (!Sensing.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("先に位置情報を許可してください"); return
        }
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) { toast("位置を取得できませんでした。屋外で再度お試しください"); return@addOnSuccessListener }
                Store.saveStation(this, id, LatLon(loc.latitude, loc.longitude))
                if (id == StationId.ARAI) Sensing.registerAraiGeofence(this)
                toast("${id.label}を登録しました（誤差 約${loc.accuracy.toInt()}m）")
                onChanged()
            }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** 特急系（赤で表示する種別） */
private fun TrainType.isLimited() =
    this == TrainType.DIRECT_LIMITED || this == TrainType.LIMITED || this == TrainType.S_LIMITED

/** 駅名・行先の括弧書きを省く（神戸三宮(阪神) → 神戸三宮） */
private fun shortName(name: String) = name.replace(Regex("[(（].*?[)）]"), "")

private val RED = Color(0xFFC00000)
private val ARROW_W = 16.dp
