package com.example.ticketassistant

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ticketassistant.automation.OfficialAppLauncher
import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.StationRepository
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import com.example.ticketassistant.data.TrainRepository
import com.example.ticketassistant.notifications.TaskScheduler
import com.example.ticketassistant.update.AppUpdate
import com.example.ticketassistant.update.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: TicketViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { TicketApp(viewModel) } }
    }
}

class TicketViewModel : ViewModel() {
    private val stationRepository = StationRepository()
    private val trainRepository = TrainRepository()
    val stations = MutableStateFlow<List<Station>>(emptyList())
    val trains = MutableStateFlow<List<Train>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val page = MutableStateFlow(Page.HOME)
    val update = MutableStateFlow<AppUpdate?>(null)
    val updateBusy = MutableStateFlow(false)
    var from by mutableStateOf<Station?>(null)
    var to by mutableStateOf<Station?>(null)
    var date by mutableStateOf(LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_DATE))
    var selectedTrain by mutableStateOf<Train?>(null)
    var storedTask by mutableStateOf<TicketTask?>(null)
    private val _stations = stations.asStateFlow()

    fun loadStations() = viewModelScope.launch {
        if (stations.value.isNotEmpty() || busy.value) return@launch
        busy.value = true; error.value = null
        runCatching { stationRepository.fetchStations() }.onSuccess { stations.value = it }
            .onFailure { error.value = "无法读取官方车站资源：${it.message ?: "网络不可用"}" }
        busy.value = false
    }

    fun query() = viewModelScope.launch {
        error.value = when {
            from == null -> "请选择标准出发站"
            to == null -> "请选择标准到达站"
            else -> null
        }
        if (error.value != null) return@launch

        val travelDate = runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_DATE) }.getOrNull()
        if (travelDate == null) {
            error.value = "乘车日期格式应为 yyyy-MM-dd"
            return@launch
        }
        if (travelDate.isBefore(LocalDate.now())) {
            error.value = "乘车日期不能早于今天"
            return@launch
        }

        val departure = requireNotNull(from)
        val arrival = requireNotNull(to)
        busy.value = true; error.value = null
        runCatching { trainRepository.query(date, departure, arrival) }.onSuccess {
            trains.value = it; page.value = Page.TRAINS
        }.onFailure { error.value = "真实车次查询失败：${it.message ?: "请稍后重试"}" }
        busy.value = false
    }

    fun choose(train: Train) { selectedTrain = train; page.value = Page.CONFIGURE }
    fun backHome() { page.value = Page.HOME }
    fun goBack() {
        page.value = when (page.value) {
            Page.TRAINS -> Page.HOME
            Page.CONFIGURE -> Page.TRAINS
            Page.TASK -> Page.HOME
            Page.HOME -> Page.HOME
        }
    }
    fun restore(context: android.content.Context) { storedTask = TaskStore(context).load(); if (storedTask != null) page.value = Page.TASK }

    fun checkForUpdate(context: android.content.Context) = viewModelScope.launch {
        if (update.value != null || updateBusy.value) return@launch
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        updateBusy.value = true
        update.value = runCatching { UpdateChecker().latest(current) }.getOrNull()
        updateBusy.value = false
    }

    fun installUpdate(context: android.content.Context) = viewModelScope.launch {
        val candidate = update.value ?: return@launch
        updateBusy.value = true
        runCatching { UpdateChecker().downloadAndInstall(context, candidate) }
            .onFailure { error.value = it.message ?: "更新失败，请稍后重试" }
        updateBusy.value = false
    }
}

enum class Page { HOME, TRAINS, CONFIGURE, TASK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketApp(vm: TicketViewModel) {
    val context = LocalContext.current
    val page by vm.page.collectAsStateCompat()
    val busy by vm.busy.collectAsStateCompat()
    val error by vm.error.collectAsStateCompat()
    val update by vm.update.collectAsStateCompat()
    val updateBusy by vm.updateBusy.collectAsStateCompat()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.restore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.checkForUpdate(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("行程助手") },
            navigationIcon = {
                if (page != Page.HOME) {
                    IconButton(onClick = vm::goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (error != null) ErrorBanner(error ?: "")
            when (page) {
                Page.HOME -> HomeScreen(vm, busy)
                Page.TRAINS -> TrainList(vm, busy)
                Page.CONFIGURE -> ConfigureScreen(vm)
                Page.TASK -> TaskScreen(vm)
            }
        }
    }
    update?.let { candidate ->
        AlertDialog(
            onDismissRequest = { vm.update.value = null },
            title = { Text("发现新版本 ${candidate.versionName}") },
            text = {
                Column {
                    Text(candidate.releaseName)
                    if (candidate.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(candidate.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.installUpdate(context) }, enabled = !updateBusy) {
                    if (updateBusy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("立即更新")
                }
            },
            dismissButton = { OutlinedButton(onClick = { vm.update.value = null }, enabled = !updateBusy) { Text("稍后") } }
        )
    }
}

@Composable
private fun HomeScreen(vm: TicketViewModel, busy: Boolean) {
    val stations by vm.stations.collectAsStateCompat()
    var fromQuery by rememberSaveable { mutableStateOf(vm.from?.name.orEmpty()) }
    var toQuery by rememberSaveable { mutableStateOf(vm.to?.name.orEmpty()) }
    Text("查询真实车次", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    StationField(
        label = "出发站",
        query = fromQuery,
        selectedStation = vm.from,
        stations = stations,
        loading = busy && stations.isEmpty(),
        onQueryChange = {
            fromQuery = it
            vm.from = null
            if (it.isNotBlank()) vm.loadStations()
        },
        onSelect = {
            vm.from = it
            fromQuery = it.name
        }
    )
    Spacer(Modifier.height(8.dp))
    StationField(
        label = "到达站",
        query = toQuery,
        selectedStation = vm.to,
        stations = stations,
        loading = busy && stations.isEmpty(),
        onQueryChange = {
            toQuery = it
            vm.to = null
            if (it.isNotBlank()) vm.loadStations()
        },
        onSelect = {
            vm.to = it
            toQuery = it.name
        }
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = vm.date, onValueChange = { vm.date = it }, label = { Text("乘车日期（yyyy-MM-dd）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Button(onClick = vm::query, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("搜索当天全部车次")
    }
    Spacer(Modifier.height(16.dp))
    Text("查询结果来自 12306 官方接口。验证码、登录、身份核验、候补协议与支付均需要你在官方 App 中处理。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StationField(
    label: String,
    query: String,
    selectedStation: Station?,
    stations: List<Station>,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSelect: (Station) -> Unit
) {
    val candidates = remember(query, stations) {
        if (query.isBlank()) emptyList() else stations.filter {
            it.name.contains(query, ignoreCase = true) || it.city.contains(query, ignoreCase = true)
        }.take(6)
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(label) },
            placeholder = { Text("输入站名或拼音后选择") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        when {
            query.isBlank() -> Unit
            loading -> Text("正在读取官方车站信息...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            candidates.isEmpty() && stations.isNotEmpty() -> Text("未找到匹配车站，请换一个站名或拼音", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            selectedStation?.name == query -> Text("已选择标准车站", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            else -> candidates.forEach { station ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(station) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(station.name, fontWeight = FontWeight.Medium)
                    if (station.city.isNotBlank()) Text("  ${station.city}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TrainList(vm: TicketViewModel, busy: Boolean) {
    val trains by vm.trains.collectAsStateCompat()
    Text("当天真实车次", style = MaterialTheme.typography.titleLarge)
    if (busy) CircularProgressIndicator()
    if (!busy && trains.isEmpty()) {
        Text("当天没有查到符合条件的车次，请检查日期、站点或网络后重试。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        items(trains) { train ->
            Card(Modifier.fillMaxWidth().clickable { vm.choose(train) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(train.trainNo, fontWeight = FontWeight.Bold)
                    Text("${train.from} ${train.depart}  →  ${train.to} ${train.arrive}  ${train.duration}")
                    Text(train.seats.entries.joinToString("  ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConfigureScreen(vm: TicketViewModel) {
    val context = LocalContext.current
    val train = vm.selectedTrain ?: return
    var seat by remember { mutableStateOf(train.seats.keys.firstOrNull().orEmpty()) }
    var name by remember { mutableStateOf("") }
    var saleTime by remember { mutableStateOf("14:00") }
    var allowed by remember { mutableStateOf(false) }
    Text("配置任务", style = MaterialTheme.typography.titleLarge)
    Text("${train.trainNo}  ${train.from} ${train.depart} → ${train.to} ${train.arrive}")
    Spacer(Modifier.height(12.dp))
    Text("选择席别")
    train.seats.forEach { (label, availability) -> Row(modifier = Modifier.clickable { seat = label }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = seat == label, onClick = { seat = label }); Text("$label（$availability）") } }
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("乘车人姓名（仅一名成人）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = saleTime, onValueChange = { saleTime = it }, label = { Text("开售时间（HH:mm）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allowed, { allowed = it }); Text("允许在开售时创建真实待支付订单") }
    Button(onClick = {
        val from = vm.from ?: run { vm.error.value = "请选择标准出发站"; return@Button }
        val to = vm.to ?: run { vm.error.value = "请选择标准到达站"; return@Button }
        if (from.telecode == to.telecode) {
            vm.error.value = "出发站和到达站不能相同"
            return@Button
        }
        val travelDate = runCatching { LocalDate.parse(vm.date, DateTimeFormatter.ISO_DATE) }.getOrNull()
        if (travelDate == null || travelDate.isBefore(LocalDate.now())) {
            vm.error.value = "乘车日期无效或早于今天"
            return@Button
        }
        val sale = runCatching { LocalDateTime.parse("${vm.date} $saleTime", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
        if (sale == null || sale.atZone(ZoneId.of("Asia/Shanghai")).toInstant().isBefore(java.time.Instant.now())) {
            vm.error.value = "开售时间已过去，请填写未来时间"
            return@Button
        }
        val task = TicketTask(vm.date, from, to, train, seat, name.trim(), saleTime, enabled = true, status = TaskStatus.ENABLED)
        TaskStore(context).save(task); TaskScheduler(context).schedule(task); vm.storedTask = task; vm.page.value = Page.TASK
    }, enabled = seat.isNotBlank() && name.isNotBlank() && saleTime.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) && allowed, modifier = Modifier.fillMaxWidth()) { Text("保存并启用唯一任务") }
    Spacer(Modifier.height(8.dp)); Text("提交仅允许一次；无法识别页面、验证码、登录失效或字段不一致时会停止并要求你接管。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TaskScreen(vm: TicketViewModel) {
    val context = LocalContext.current
    val task = vm.storedTask ?: return
    Text("已启用任务", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("${task.date}  ${task.train.trainNo}", fontWeight = FontWeight.Bold)
        Text("${task.from.name} ${task.train.depart} → ${task.to.name} ${task.train.arrive}")
        Text("${task.seat} · ${task.passengerName} · 开售 ${task.saleTime}")
    } }
    Spacer(Modifier.height(12.dp))
    Button(onClick = {
        val launcher = OfficialAppLauncher(context)
        if (!launcher.isInstalled()) vm.error.value = "未安装官方 12306 App，请先安装并登录后再执行任务"
        else launcher.launch().onFailure { vm.error.value = it.message ?: "无法打开官方 12306" }
    }, modifier = Modifier.fillMaxWidth()) { Text("立即测试打开官方 12306") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("打开无障碍服务设置") }
    Spacer(Modifier.height(8.dp))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("允许精确闹钟") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { TaskScheduler(context).cancel(); TaskStore(context).clear(); vm.storedTask = null; vm.page.value = Page.HOME }, modifier = Modifier.fillMaxWidth()) { Text("停用任务") }
    Spacer(Modifier.height(12.dp))
    Text("可靠模式请在开售前保持手机已解锁、屏幕可用，并在官方 12306 保持登录。Android 16 可能限制后台唤起，因此后台尝试不能保证执行。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ErrorBanner(message: String) = Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat() = collectAsState()
