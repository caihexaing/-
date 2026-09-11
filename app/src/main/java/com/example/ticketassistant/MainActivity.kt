package com.example.ticketassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.ticketassistant.automation.AccessibilityServiceStatus
import com.example.ticketassistant.automation.OfficialAppLauncher
import com.example.ticketassistant.automation.PersistentSubmitGate
import com.example.ticketassistant.automation.submitGateKey
import com.example.ticketassistant.data.Station
import com.example.ticketassistant.data.StationRepository
import com.example.ticketassistant.data.TaskStatus
import com.example.ticketassistant.data.TaskStore
import com.example.ticketassistant.data.TicketTask
import com.example.ticketassistant.data.Train
import com.example.ticketassistant.data.TrainRepository
import com.example.ticketassistant.data.SaleState
import com.example.ticketassistant.data.SaleTimeSource
import com.example.ticketassistant.data.SaleStateRules
import com.example.ticketassistant.notifications.TaskScheduler
import com.example.ticketassistant.update.AppUpdate
import com.example.ticketassistant.update.UpdateChecker
import com.example.ticketassistant.update.UpdateProgress
import com.example.ticketassistant.diagnostics.DiagnosticExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
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
        handleResumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResumeIntent(intent)
    }

    private fun handleResumeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RESUME_TASK, false) != true) return
        val expectedId = intent.getStringExtra(EXTRA_TASK_ID)
        val task = TaskStore(this).load()
        if (task == null || !task.enabled || (expectedId != null && expectedId != task.taskId)) {
            viewModel.error.value = "任务已失效，请重新查询并配置"
            return
        }
        runCatching { TaskScheduler(this).startImmediately(task) }
            .onFailure { viewModel.error.value = it.message ?: "无法启动任务，请保持应用在前台重试" }
    }

    companion object {
        const val EXTRA_RESUME_TASK = "resume_task"
        const val EXTRA_TASK_ID = "task_id"
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
    val updateProgress = MutableStateFlow<UpdateProgress?>(null)
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

    fun refreshTask(context: android.content.Context) {
        storedTask = TaskStore(context).load()
    }

    fun saveTask(context: android.content.Context, seat: String, name: String, saleDateTimeInput: String, confirmNotYetOnSale: Boolean) = viewModelScope.launch {
        val departure = from ?: run { error.value = "请选择标准出发站"; return@launch }
        val arrival = to ?: run { error.value = "请选择标准到达站"; return@launch }
        if (departure.telecode == arrival.telecode) {
            error.value = "出发站和到达站不能相同"
            return@launch
        }
        val target = selectedTrain ?: run { error.value = "请先选择车次"; return@launch }
        if (name.isBlank()) { error.value = "请填写乘车人姓名"; return@launch }
        val selectedAvailability = target.seats[seat]?.trim()
        if (seat.isBlank() || selectedAvailability.isNullOrBlank() || selectedAvailability in TrainRepository.UNAVAILABLE_SEAT_VALUES) {
            error.value = "所选席别当前不可购买，请重新选择"
            return@launch
        }
        busy.value = true
        error.value = null
        try {
            val assessment = runCatching {
                trainRepository.assessSaleState(date, departure, arrival, target, seat)
            }.getOrElse {
                error.value = "无法确认开售状态：${it.message ?: "官方查询失败"}"
                return@launch
            }
            val normalizedSale = saleDateTimeInput.trim().ifBlank { null }
            val saleState = SaleStateRules.resolveState(assessment, confirmNotYetOnSale)
            when (saleState) {
                SaleState.ALREADY_ON_SALE, SaleState.NOT_YET_ON_SALE, SaleState.UNKNOWN ->
                    SaleStateRules.validateForSave(saleState, normalizedSale)?.let {
                        error.value = it
                        return@launch
                    }
            }
            val task = TicketTask(
                date = date,
                from = departure,
                to = arrival,
                train = target,
                seat = seat,
                passengerName = name.trim(),
                saleState = saleState,
                saleDateTime = if (saleState == SaleState.NOT_YET_ON_SALE) normalizedSale else null,
                saleTimeSource = if (saleState == SaleState.ALREADY_ON_SALE) SaleTimeSource.OFFICIAL else SaleTimeSource.USER_CONFIRMED,
                enabled = true,
                status = TaskStatus.ENABLED
            )
            TaskStore(context).save(task)
            runCatching { TaskScheduler(context).schedule(task) }.onFailure {
                TaskStore(context).save(task.copy(enabled = false, status = TaskStatus.DRAFT, lastError = it.message ?: "调度失败"))
                error.value = it.message ?: "任务调度失败"
                return@launch
            }
            storedTask = TaskStore(context).load()
            page.value = Page.TASK
        } finally {
            busy.value = false
        }
    }

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
        updateProgress.value = UpdateProgress(0L, candidate.expectedSize ?: 0L, 1)
        try {
            UpdateChecker().downloadAndInstall(context, candidate) { progress ->
                updateProgress.value = progress
            }
        } catch (failure: Throwable) {
            error.value = failure.message ?: "更新失败，请稍后重试"
        } finally {
            updateBusy.value = false
            updateProgress.value = null
        }
    }
}

private fun SaleState.label(): String = when (this) {
    SaleState.ALREADY_ON_SALE -> "已确认开售"
    SaleState.NOT_YET_ON_SALE -> "已确认未开售"
    SaleState.UNKNOWN -> "开售状态未知"
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
    val updateProgress by vm.updateProgress.collectAsStateCompat()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.restore(context) }
    androidx.compose.runtime.LaunchedEffect(page) {
        while (page == Page.TASK) {
            vm.refreshTask(context)
            delay(1_000L)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.refreshTask(context)
                vm.checkForUpdate(context)
            }
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
                    if (updateBusy) {
                        Spacer(Modifier.height(16.dp))
                        val progress = updateProgress
                        if (progress != null && progress.totalBytes > 0L) {
                            val fraction = (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text("正在下载 ${"%.0f".format(fraction * 100)}% · 第 ${progress.attempt} 次尝试")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text("正在连接下载服务…")
                        }
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
    val busy by vm.busy.collectAsStateCompat()
    val purchasableSeats = remember(train) {
        train.seats.filter { (_, availability) ->
            availability.trim().isNotBlank() && availability.trim() !in TrainRepository.UNAVAILABLE_SEAT_VALUES
        }
    }
    var seat by remember(train) { mutableStateOf(purchasableSeats.keys.firstOrNull().orEmpty()) }
    var name by remember { mutableStateOf("") }
    var saleDateTime by remember { mutableStateOf("") }
    var confirmNotYetOnSale by remember(train) { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(false) }
    Text("配置任务", style = MaterialTheme.typography.titleLarge)
    Text("${train.trainNo}  ${train.from} ${train.depart} → ${train.to} ${train.arrive}")
    Spacer(Modifier.height(12.dp))
    Text("选择席别")
    if (purchasableSeats.isEmpty()) {
        Text("当前查询结果没有可购买席别，请稍后重新查询。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
        purchasableSeats.forEach { (label, availability) -> Row(modifier = Modifier.clickable { seat = label }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = seat == label, onClick = { seat = label }); Text("$label（$availability）") } }
    }
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("乘车人姓名（仅一名成人）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = saleDateTime, onValueChange = { saleDateTime = it }, label = { Text("开售日期时间（未开售时填写 yyyy-MM-dd HH:mm）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(confirmNotYetOnSale, { confirmNotYetOnSale = it })
        Text("我已确认目标车次尚未开售")
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allowed, { allowed = it }); Text("允许在开售时创建真实待支付订单") }
    Button(onClick = {
        val travelDate = runCatching { LocalDate.parse(vm.date, DateTimeFormatter.ISO_DATE) }.getOrNull()
        if (travelDate == null || travelDate.isBefore(LocalDate.now())) {
            vm.error.value = "乘车日期无效或早于今天"
            return@Button
        }
        vm.saveTask(context, seat, name, saleDateTime, confirmNotYetOnSale)
    }, enabled = !busy && seat.isNotBlank() && name.isNotBlank() && allowed, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("查询确认并启用任务")
    }
    Spacer(Modifier.height(8.dp)); Text("保存时会重新查询官方车次：已确认有票则立即执行；确认未开售才要求填写未来开售时间。查询不明确时不会盲目启用。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TaskScreen(vm: TicketViewModel) {
    val context = LocalContext.current
    val task = vm.storedTask ?: return
    val statusText = when (task.status) {
        TaskStatus.ENABLED, TaskStatus.WAITING_FOR_SALE -> "任务已启用，等待开售"
        TaskStatus.PREPARING -> "正在准备并唤起官方 12306"
        TaskStatus.WAITING_OFFICIAL_PAGE -> "已发现余票，等待官方 12306 页面"
        TaskStatus.OPENING_SEARCH -> "正在定位官方 12306 查询入口"
        TaskStatus.FILLING_DEPARTURE -> "正在自动填写出发站"
        TaskStatus.FILLING_ARRIVAL -> "正在自动填写到达站"
        TaskStatus.FILLING_DATE -> "正在自动填写乘车日期"
        TaskStatus.SUBMITTING_SEARCH -> "正在自动查询车次"
        TaskStatus.OBSERVING -> "已发现余票，但尚未提交订单，等待官方页面操作"
        TaskStatus.VALIDATING_SEARCH_RESULT -> "正在核对官方查询结果页"
        TaskStatus.SELECTING_TRAIN_SEAT -> "正在定位目标车次和席别"
        TaskStatus.SELECTING_PASSENGER -> "正在选择指定乘车人"
        TaskStatus.VALIDATING_ORDER -> "正在核对订单确认页，尚未提交"
        TaskStatus.SUBMIT_ACTION_SENT -> "已发送提交点击，等待官方结果"
        TaskStatus.WAITING_SERVER_RESULT -> "正在等待官方确认，不会自动重复提交"
        TaskStatus.SEARCHING -> "正在查询余票"
        TaskStatus.SUBMIT_REJECTED -> "官方未创建订单"
        TaskStatus.TAKEOVER -> "需要人工接管，请查看官方 App"
        TaskStatus.PENDING_PAYMENT -> "已进入待支付订单，请在官方 App 完成后续操作"
        TaskStatus.EXPIRED -> "任务已超时"
        TaskStatus.RESULT_UNKNOWN -> "提交结果不明确，请到官方订单页核对"
        TaskStatus.DISABLED -> "任务已停用"
        TaskStatus.DRAFT -> "任务草稿"
    }
    Text(statusText, style = MaterialTheme.typography.titleLarge)
    task.lastEvent?.let { eventText ->
        val whenText = task.lastEventAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        }
        Text(
            if (whenText != null) "$eventText（$whenText）" else eventText,
            style = MaterialTheme.typography.bodySmall
        )
    }
    task.lastError?.let { errorText ->
        Text("最近错误：$errorText", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    val accessibilityEnabled = AccessibilityServiceStatus.isEnabled(context)
    val submitLocked = PersistentSubmitGate(
        context.getSharedPreferences("submit_gate", android.content.Context.MODE_PRIVATE),
        submitGateKey(task)
    ).isLocked()
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
    Text("诊断状态", fontWeight = FontWeight.SemiBold)
        Text("无障碍服务：${if (accessibilityEnabled) "已启用" else "未启用"}")
        Text("最近官方页面：${task.lastPageState ?: "尚未收到页面事件"}")
        Text("最近动作：${task.lastAction ?: "暂无"}")
        val eventTime = task.lastAccessibilityEventAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        } ?: "暂无"
        Text("最近事件：$eventTime（累计 ${task.accessibilityEventCount} 次）")
        Text("提交锁：${if (submitLocked) "已锁定，禁止自动重试" else "未锁定"}")
    } }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = {
        runCatching {
            val report = DiagnosticExporter.export(context, task)
            context.startActivity(Intent.createChooser(DiagnosticExporter.shareIntent(context, report), "分享脱敏诊断"))
        }.onFailure { vm.error.value = "导出诊断失败：${it.message ?: "无法创建文件"}" }
    }, modifier = Modifier.fillMaxWidth()) { Text("导出脱敏诊断") }
    Spacer(Modifier.height(10.dp))
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("${task.date}  ${task.train.trainNo}", fontWeight = FontWeight.Bold)
        Text("${task.from.name} ${task.train.depart} → ${task.to.name} ${task.train.arrive}")
        Text("${task.seat} · ${task.passengerName} · ${task.saleState.label()} · 开售 ${task.saleDateTime ?: "无需填写"}（${task.saleTimeSource.name}）")
    } }
    Spacer(Modifier.height(10.dp))
    Text(
        "后台发现余票不代表已经下单。请在官方 12306 手动查询完全一致的日期、路线、车次和席别并停留在结果页；只有页面字段核对通过后才会继续辅助操作。验证码、身份核验、风控和支付必须由你手动完成。",
        style = MaterialTheme.typography.bodySmall
    )
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
    val power = context.getSystemService(PowerManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !power.isIgnoringBatteryOptimizations(context.packageName)) {
        Spacer(Modifier.height(8.dp))
        Text("当前受电池优化限制，开售时后台唤起可能延迟。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth()) { Text("允许后台不受电池优化限制") }
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
