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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import norikae.core.DayType
import norikae.core.DrinkingModeRule
import norikae.core.LatLon
import norikae.core.Route
import norikae.core.ServiceTime
import norikae.core.StationId
import norikae.core.TripEvent

class MainActivity : ComponentActivity() {

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
        val dayType = Store.dayType(ctx, date)
        val timetable = Store.timetable(ctx)
        val planner = TripController.planner(ctx)
        val routes = planner.plan(dayType, now)
        val recommended = planner.recommend(routes, Store.localTolerance(ctx))
        val trip = Store.trip(ctx)
        val sanyoMissing = timetable.forDay(dayType).sanyo.isEmpty()

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
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = dayType == DayType.WEEKDAY,
                            onClick = { Store.overrideDayType(ctx, date, DayType.WEEKDAY); refresh() },
                            label = { Text("平日ダイヤ") },
                        )
                        FilterChip(
                            selected = dayType == DayType.HOLIDAY,
                            onClick = { Store.overrideDayType(ctx, date, DayType.HOLIDAY); refresh() },
                            label = { Text("土休日ダイヤ") },
                        )
                    }
                    Text("祝日は自動で切り替わらないので、手動で「土休日ダイヤ」を選んでください", fontSize = 12.sp)
                }
            }

            item {
                Button(
                    onClick = {
                        if (TripController.startFromBoardingNow(ctx)) {
                            toast("乗車時刻からアラームを設定しました")
                        } else {
                            toast("経路を決められません。下の「荒井→新開地の所要分」を設定してください")
                        }
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("今、荒井で乗った（この時刻でアラーム設定）") }
            }

            item {
                Column {
                    Text(
                        if (sanyoMissing) "新開地発 北鈴蘭台行きの候補（山陽の時刻表は未入力）" else "帰り道の候補（普通優先）",
                        fontWeight = FontWeight.Bold,
                    )
                    planner.lastRoute(dayType)?.let {
                        Text("北鈴蘭台に帰れる最終：新開地 ${it.shintetsu.dep}発 ${it.shintetsu.dest}行き（北鈴蘭台 ${it.kitaArr}着）", fontSize = 13.sp)
                    }
                }
            }

            if (routes.isEmpty()) {
                item { Text("この時刻以降に北鈴蘭台へ帰れる電車はありません") }
            }
            items(routes) { route ->
                RouteCard(route, recommended = route == recommended) {
                    TripController.start(ctx, route)
                    toast("アラームを設定しました")
                    refresh()
                }
            }

            item { SettingsCard(onChanged = refresh) }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    @Composable
    private fun RouteCard(route: Route, recommended: Boolean, onStart: () -> Unit) {
        Card(
            colors = if (recommended) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (recommended) AssistChip(onClick = {}, label = { Text("おすすめ") })
                    route.sanyo?.let { AssistChip(onClick = {}, label = { Text(it.type.label) }) }
                    if (route.umedaRisk) AssistChip(onClick = {}, label = { Text("梅田注意") })
                    if (route.shintetsu.transferAtSuzurandai) AssistChip(onClick = {}, label = { Text("鈴蘭台で乗換") })
                }
                route.sanyo?.let { s ->
                    Text("荒井 ${s.dep}発 ${s.type.label} ${s.dest}行き" + (s.transferAt?.let { "（${it}で乗換）" } ?: ""))
                }
                route.shinkaichiArr?.let {
                    Text("新開地 ${it}着" + if (route.shinkaichiEstimated) "（推定）" else "")
                }
                Text(
                    "神戸電鉄 ${route.shintetsu.dep}発 ${route.shintetsu.type.label} ${route.shintetsu.dest}行き",
                    fontWeight = FontWeight.Bold,
                )
                Text("北鈴蘭台 ${route.kitaArr}着")
                route.shintetsu.note?.let { Text(it, fontSize = 12.sp) }
                TextButton(onClick = onStart) { Text("この電車でアラーム設定") }
            }
        }
    }

    @Composable
    private fun SettingsCard(onChanged: () -> Unit) {
        val ctx = this
        var runText by remember { mutableStateOf(Store.sanyoRunMinutes(ctx)?.toString() ?: "") }
        val stations = Store.stations(ctx)
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

                Text("駅の位置（その駅にいるときに押す）", fontWeight = FontWeight.Bold)
                Text("登録すると、位置を使った判定（荒井を出たら自動開始・乗り過ごし・粟生線）が有効になります", fontSize = 12.sp)
                StationId.entries.forEach { id ->
                    Row {
                        Text(
                            "${id.label}：" + if (stations[id] != null) "登録済み" else "未登録",
                            modifier = Modifier.width(180.dp).padding(top = 12.dp),
                        )
                        TextButton(onClick = { registerHere(id, onChanged) }) { Text("今いる場所を登録") }
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
        val missingBasic = remember(tick) { basicPermissions.filterNot { Sensing.has(ctx, it) } }
        val missingBackground = remember(tick) { !Sensing.has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        val nm = getSystemService(NotificationManager::class.java)
        val missingFullScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()
        val am = getSystemService(AlarmManager::class.java)
        val missingExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()

        if (missingBasic.isEmpty() && !missingBackground && !missingFullScreen && !missingExact) return
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("権限の設定が必要です", fontWeight = FontWeight.Bold)
                if (missingBasic.isNotEmpty()) {
                    OutlinedButton(onClick = { basic.launch(missingBasic.toTypedArray()) }) {
                        Text("位置情報・身体活動・通知を許可")
                    }
                } else if (missingBackground) {
                    Text("自動開始と乗り過ごし判定のため、位置情報は「常に許可」にしてください", fontSize = 12.sp)
                    OutlinedButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                        Text("位置情報を「常に許可」にする")
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
