@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.LocalClipboardManager
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.swing.Swing

private val defaultEnv = mapOf(
    "ADB_SERVER_SOCKET" to "tcp:127.0.0.1:5037",
    "LOGCAT_ADB" to "/mnt/c/Users/avrca/AppData/Local/Android/Sdk/platform-tools/adb.exe"
)

@Suppress("unused")
private val ensureDefaultEnv = run {
    defaultEnv.forEach { (k, v) ->
        if (System.getenv(k).isNullOrBlank() && System.getProperty(k).isNullOrBlank()) {
            System.setProperty(k, v)
        }
    }
}

data class AdbDevice(
    val serial: String,
    val state: String,
    val product: String? = null,
    val model: String? = null,
    val deviceName: String? = null,
    val transportId: String? = null
) {
    val label: String get() =
        buildString {
            append(serial)
            append("  [").append(state).append("]")
            model?.let { append("  ").append(it) }
            product?.let { append("  (prod:").append(it).append(")") }
        }
}

data class AppConfig(
    val selectedSerial: String? = null,
    val lastApkPath: String? = null,
    val lastExportDir: String? = null
)

data class AdbDevicesResult(val output: String, val adbPath: String)

data class LogEntry(
    val raw: String,
    val timestamp: String?,
    val pid: String?,
    val tid: String?,
    val level: String?,
    val tag: String?,
    val message: String
)

data class TimeFilterResult(
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val error: String?
)

private val LOGCAT_REGEX =
    Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEAF])\s+([^:]+):\s*(.*)$""")

fun parseLogLine(line: String): LogEntry {
    val match = LOGCAT_REGEX.find(line)
    return if (match != null) {
        val (ts, pid, tid, level, tag, msg) = match.destructured
        LogEntry(
            raw = line,
            timestamp = ts,
            pid = pid,
            tid = tid,
            level = level,
            tag = tag.trim(),
            message = msg.ifBlank { "-" }
        )
    } else {
        LogEntry(
            raw = line,
            timestamp = null,
            pid = null,
            tid = null,
            level = null,
            tag = null,
            message = line
        )
    }
}

private val LEVEL_PRIORITY = mapOf(
    "V" to 0,
    "D" to 1,
    "I" to 2,
    "W" to 3,
    "E" to 4,
    "F" to 5,
    "A" to 5
)

private val DEFAULT_LEVEL_COLOR = Color(0xFF546E7A)

private val LEVEL_COLORS = mapOf(
    "V" to Color(0xFF9E9E9E),
    "D" to Color(0xFF1976D2),
    "I" to Color(0xFF388E3C),
    "W" to Color(0xFFF57F17),
    "E" to Color(0xFFD32F2F),
    "F" to Color(0xFF880E4F),
    "A" to Color(0xFF880E4F)
)

private val CSV_EXPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val LOG_TIMESTAMP_INPUT_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
private val LOG_TIMESTAMP_WITH_YEAR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

fun levelColor(level: String?): Color =
    LEVEL_COLORS[level] ?: DEFAULT_LEVEL_COLOR

fun findAdbExecutable(): Result<String> =
    runCatching {
        val envAdb = System.getenv("LOGCAT_ADB")?.takeIf { it.isNotBlank() }
        val propAdb = System.getProperty("LOGCAT_ADB")?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (envAdb != null) add(envAdb)
            if (propAdb != null) add(propAdb)
            add("/mnt/c/Android/platform-tools/adb.exe")
            add("/mnt/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe")
            add("/mnt/c/Program Files/Android/platform-tools/adb.exe")
            add("adb")
        }.distinct()

        candidates.firstNotNullOfOrNull { path ->
            try {
                val proc = ProcessBuilder(listOf(path, "version"))
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(1500, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    null
                } else if (proc.exitValue() == 0) {
                    path
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        } ?: error("adb executable not found. Add it to PATH or set LOGCAT_ADB.")
    }

private fun runAdbCommand(
    adbPath: String,
    args: List<String>,
    timeoutSeconds: Long = 5
): String {
    val pb = ProcessBuilder(listOf(adbPath) + args).redirectErrorStream(true)
    val env = pb.environment()
    if (env["ADB_SERVER_SOCKET"].isNullOrBlank()) {
        env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
    }
    val proc = pb.start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        proc.destroyForcibly()
        error("adb ${args.joinToString(" ")} timed out")
    }
    val output = proc.inputStream.bufferedReader().use { it.readText() }
    val exitCode = runCatching { proc.exitValue() }.getOrElse { -1 }
    if (exitCode != 0) {
        error("adb ${args.joinToString(" ")} failed with code $exitCode:\n$output")
    }
    return output
}

fun startLogcatProcess(adbPath: String, serial: String): Process {
    val pb = ProcessBuilder(listOf(adbPath, "-s", serial, "logcat")).redirectErrorStream(true)
    val env = pb.environment()
    if (env["ADB_SERVER_SOCKET"].isNullOrBlank()) {
        env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
    }
    return pb.start()
}

fun runLogcatClear(adbPath: String, serial: String): Result<Unit> =
    runCatching {
        runAdbCommand(adbPath, listOf("-s", serial, "logcat", "-c"))
    }

fun runAdbDevices(): Result<AdbDevicesResult> =
    findAdbExecutable().mapCatching { adbPath ->
        runAdbCommand(adbPath, listOf("start-server"))
        val out = runAdbCommand(adbPath, listOf("devices", "-l"))
        AdbDevicesResult(out, adbPath)
    }

private val KV_REGEX = Regex("""(\w+):([^\s]+)""")

fun parseAdbDevices(output: String): List<AdbDevice> {
    val lines = output
        .lines()
        .map { it.trim() }
    val result = mutableListOf<AdbDevice>()

    for (line in lines) {
        if (line.isBlank()) continue
        if (line.startsWith("List of devices attached")) continue

        // Example:
        // emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
        // R58M123ABC unauthorized
        val parts = line.split(Regex("""\s+"""))
        if (parts.isEmpty()) continue

        val serial = parts[0]
        val state = parts.getOrNull(1) ?: "unknown"

        val kv = KV_REGEX.findAll(line).associate { m -> m.groupValues[1] to m.groupValues[2] }
        val product = kv["product"]
        val model = kv["model"]
        val deviceName = kv["device"]
        val transportId = kv["transport_id"]

        result += AdbDevice(serial, state, product, model, deviceName, transportId)
    }
    return result
}

fun loadConfig(): AppConfig {
    return try {
        val file = configFile()
        if (!file.exists()) return AppConfig()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AppConfig::class.java)
        adapter.fromJson(file.readText()) ?: AppConfig()
    } catch (_: Exception) {
        AppConfig()
    }
}

fun saveConfig(cfg: AppConfig): Result<Unit> =
    runCatching {
        val file = configFile()
        file.parentFile?.mkdirs()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AppConfig::class.java).indent("  ")
        file.writeText(adapter.toJson(cfg))
    }

fun configFile(): File {
    val home = System.getProperty("user.home")
    return File(home, ".logcat-filter/config.json")
}

data class DebugInfo(
    val adbPath: String?,
    val adbServerSocket: String?,
    val raw: String?
)

sealed interface Screen {
    object Picker : Screen
    data class Logcat(
        val device: AdbDevice,
        val adbPathHint: String?,
        val lastApkPath: String?,
        val lastExportDir: String?
    ) : Screen
}

@Composable
fun DevicePicker(
    devices: List<AdbDevice>,
    selected: AdbDevice?,
    onSelect: (AdbDevice?) -> Unit,
    onRefresh: () -> Unit,
    onSaveSelection: (AdbDevice) -> Result<Unit>,
    onNavigateToLogcat: (AdbDevice) -> Unit,
    adbError: String?,
    debugInfo: DebugInfo?
) {
    var saveInfo by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val configPath = remember { configFile().absolutePath }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ADB Device Picker", style = MaterialTheme.typography.h5)

        if (adbError != null) {
            Text(
                "ADB error: $adbError\n" +
                "Provjeri da je Android SDK platform-tools na PATH-u i da je adb pokrenut.",
                color = MaterialTheme.colors.error
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            val label = selected?.label ?: if (devices.isEmpty()) "No devices" else "Select device"

            Box {
                Button(onClick = { if (devices.isNotEmpty()) expanded = true }) { Text(label) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    devices.forEach { dev ->
                        DropdownMenuItem(onClick = {
                            expanded = false
                            saveInfo = null
                            saveError = null
                            onSelect(dev)
                        }) {
                            Text(dev.label)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                saveInfo = null
                saveError = null
                onRefresh()
            }) { Text("Refresh") }
        }

        if (selected != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Selected: ${selected.serial}  [${selected.state}]")
                    selected.model?.let { Text("Model: $it") }
                    selected.product?.let { Text("Product: $it") }
                    selected.deviceName?.let { Text("Device Name: $it") }
                    selected.transportId?.let { Text("Transport ID: $it") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val result = onSaveSelection(selected)
                    if (result.isSuccess) {
                        saveError = null
                        saveInfo = "Saved selection to $configPath"
                        onNavigateToLogcat(selected)
                    } else {
                        saveInfo = null
                        val msg = result.exceptionOrNull()?.localizedMessage ?: "unknown error"
                        saveError = "Failed to save selection: $msg"
                    }
                }) {
                    Text("Use this device (Save)")
                }
                OutlinedButton(onClick = {
                    saveInfo = null
                    saveError = null
                    onSelect(null)
                }) {
                    Text("Clear selection")
                }
            }
            saveInfo?.let {
                Text(it, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
            }
            saveError?.let {
                Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
            }
            Text("Config path: $configPath", style = MaterialTheme.typography.caption)
        } else {
            Text("Nije odabran uređaj.", style = MaterialTheme.typography.caption)
        }

        Divider()
        Text(
            "Savjeti:\n" +
            "• Omogući USB debugging, ili koristi emulator.\n" +
            "• Ako je stanje 'unauthorized', potvrdi RSA fingerprint na uređaju.\n" +
            "• Za više detalja: `adb devices -l` u terminalu.",
            style = MaterialTheme.typography.body2
        )

        DebugBlock(debugInfo)
    }
}

@Composable
fun DebugBlock(info: DebugInfo?) {
    if (info != null && (!info.raw.isNullOrBlank() || info.adbPath != null || info.adbServerSocket != null)) {
        Divider(Modifier.padding(vertical = 8.dp))
        Text("ADB raw output:", style = MaterialTheme.typography.subtitle2)
        Text(info.raw.orEmpty(), style = MaterialTheme.typography.caption)
        Text(
            "ADB path (env): ${info.adbPath ?: "<not set>"}",
            style = MaterialTheme.typography.caption
        )
        Text(
            "ADB_SERVER_SOCKET: ${info.adbServerSocket ?: "<not set>"}",
            style = MaterialTheme.typography.caption
        )
    }
}

@Composable
fun LogcatScreen(
    device: AdbDevice,
    adbPathHint: String?,
    initialApkPath: String?,
    onApkPathChange: (String?) -> Unit,
    initialExportDir: String?,
    onExportDirChange: (String?) -> Unit,
    onBack: () -> Unit
) {
    val logLines = remember { mutableStateListOf<LogEntry>() }
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Initializing…") }
    var error by remember { mutableStateOf<String?>(null) }
    var adbPathUsed by remember { mutableStateOf(adbPathHint) }
    val processHolder = remember { mutableStateOf<Process?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var stoppedByUser by remember { mutableStateOf(false) }
    val maxLines = 1000
    val coroutineScope = rememberCoroutineScope()
    var minLevel by remember { mutableStateOf<String?>(null) }
    var tagFilter by remember { mutableStateOf("") }
    var messageFilter by remember { mutableStateOf("") }
    var pidFilter by remember { mutableStateOf("") }
    var pidPackage by remember { mutableStateOf("") }
    var pidLookupMessage by remember { mutableStateOf<String?>(null) }
    var pidLookupIsError by remember { mutableStateOf(false) }
    var exportInfo by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var packageActionMessage by remember { mutableStateOf<String?>(null) }
    var packageActionIsError by remember { mutableStateOf(false) }
    var apkPath by remember(device.serial) { mutableStateOf(initialApkPath.orEmpty()) }
    var reinstallOnInstall by remember { mutableStateOf(true) }
    var exportDir by remember(device.serial) { mutableStateOf(initialExportDir) }
    var startTimeFilter by remember { mutableStateOf("") }
    var endTimeFilter by remember { mutableStateOf("") }

    val timeFilterResult = remember(startTimeFilter, endTimeFilter) {
        val currentYear = LocalDate.now().year
        val start = parseFilterTimestamp(startTimeFilter, currentYear)
        val end = parseFilterTimestamp(endTimeFilter, currentYear)
        val validationError = when {
            startTimeFilter.isNotBlank() && start == null -> "Invalid start time. Use MM-dd HH:mm:ss.SSS."
            endTimeFilter.isNotBlank() && end == null -> "Invalid end time. Use MM-dd HH:mm:ss.SSS."
            start != null && end != null && end.isBefore(start) -> "End time must be after start time."
            else -> null
        }
        TimeFilterResult(start, end, validationError)
    }

    val (filteredLines, copyAllText) = remember(
        logLines.size,
        minLevel,
        tagFilter,
        messageFilter,
        pidFilter,
        timeFilterResult
    ) {
        val filtered = logLines.filter { entry ->
            val minRank = minLevel?.let { LEVEL_PRIORITY[it] } ?: Int.MIN_VALUE
            val tagQuery = tagFilter.trim()
            val msgQuery = messageFilter.trim()
            val pidQuery = pidFilter.trim()
            val entryRank = entry.level?.let { LEVEL_PRIORITY[it] } ?: Int.MIN_VALUE
            val levelPass = minRank == Int.MIN_VALUE || entryRank >= minRank
            val tagPass =
                tagQuery.isBlank() || (entry.tag?.contains(tagQuery, ignoreCase = true) == true)
            val msgPass =
                msgQuery.isBlank() || entry.message.contains(msgQuery, ignoreCase = true)
            val pidPass =
                pidQuery.isBlank() || (entry.pid?.contains(pidQuery, ignoreCase = true) == true)
            val timePass = if (timeFilterResult.error != null ||
                (timeFilterResult.start == null && timeFilterResult.end == null)
            ) {
                true
            } else {
                val referenceYear = timeFilterResult.start?.year
                    ?: timeFilterResult.end?.year
                    ?: LocalDate.now().year
                val entryTime = entry.timestamp?.let { parseLogTimestamp(it, referenceYear) }
                if (entryTime == null) {
                    false
                } else {
                    val afterStart = timeFilterResult.start?.let { !entryTime.isBefore(it) } ?: true
                    val beforeEnd = timeFilterResult.end?.let { !entryTime.isAfter(it) } ?: true
                    afterStart && beforeEnd
                }
            }
            levelPass && tagPass && msgPass && pidPass && timePass
        }
        filtered to filtered.joinToString(separator = "\n") { it.raw }
    }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(initialApkPath) {
        if (!initialApkPath.isNullOrBlank()) {
            apkPath = initialApkPath
        }
    }

    LaunchedEffect(initialExportDir) {
        exportDir = initialExportDir
    }

    fun stopStreaming(updateStatus: Boolean = true) {
        processHolder.value?.destroy()
        processHolder.value = null
        isStreaming = false
        if (updateStatus) {
            status = "Stopped"
        }
    }

    suspend fun streamLogcat(adbPath: String) {
        error = null
        stoppedByUser = false
        stopStreaming(updateStatus = false)
        status = "Starting…"

        val startServerResult = runCatching {
            runAdbCommand(adbPath, listOf("start-server"))
        }
        if (startServerResult.isFailure) {
            error = "Failed to start adb server: ${startServerResult.exceptionOrNull()?.localizedMessage}"
            status = "Stopped"
            return
        }

        val processResult = runCatching { startLogcatProcess(adbPath, device.serial) }
        if (processResult.isFailure) {
            error = "Failed to start logcat: ${processResult.exceptionOrNull()?.localizedMessage}"
            status = "Stopped"
            return
        }
        val process = processResult.getOrThrow()
        processHolder.value = process
        isStreaming = true
        status = "Streaming…"

        val readResult = runCatching {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        withContext(Dispatchers.Main) {
                            while (logLines.size >= maxLines) {
                                logLines.removeAt(0)
                            }
                            logLines.add(parseLogLine(line))
                        }
                    }
                }
            }
        }

        readResult.exceptionOrNull()?.let { e ->
            if (e !is CancellationException) {
                error = "Logcat stream error: ${e.localizedMessage}"
            }
        }

        val exitCode = runCatching {
            withContext(Dispatchers.IO) {
                if (process.isAlive) {
                    process.waitFor()
                }
                process.exitValue()
            }
        }.getOrNull()
        processHolder.value = null
        isStreaming = false
        status = if (stoppedByUser) {
            "Stopped"
        } else if (exitCode != null) {
            "Logcat exited (code $exitCode)"
        } else {
            "Logcat stopped"
        }
    }

    LaunchedEffect(device.serial) {
        logLines.clear()
        error = null
        status = "Initializing…"
        adbPathUsed = adbPathHint
        stopStreaming(updateStatus = false)
        stoppedByUser = false

        val resolvedPathResult = adbPathHint?.let { Result.success(it) } ?: findAdbExecutable()
        val adbPath = resolvedPathResult.getOrElse {
            error = it.localizedMessage ?: "Failed to locate adb"
            status = "Stopped"
            return@LaunchedEffect
        }
        adbPathUsed = adbPath
        status = "Ready"
        streamLogcat(adbPath)
    }

    DisposableEffect(device.serial) {
        onDispose {
            stopStreaming()
        }
    }

    LaunchedEffect(filteredLines.size, autoScroll) {
        if (autoScroll && filteredLines.isNotEmpty()) {
            listState.scrollToItem(filteredLines.lastIndex)
        }
    }


    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = {
                processHolder.value?.destroy()
                onBack()
            }) {
                Text("Back")
            }
            Text("Logcat – ${device.serial}", style = MaterialTheme.typography.h6)
        }
        Text(device.label, style = MaterialTheme.typography.subtitle2)
        Text("ADB path: ${adbPathUsed ?: "<resolving>"}", style = MaterialTheme.typography.caption)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val adbPath = adbPathUsed
                    if (adbPath != null && !isStreaming) {
                        coroutineScope.launch {
                            streamLogcat(adbPath)
                        }
                    }
                },
                enabled = !isStreaming && adbPathUsed != null
            ) {
                Text("Start")
            }
            OutlinedButton(
                onClick = {
                    stoppedByUser = true
                    status = "Stopping…"
                    stopStreaming(updateStatus = false)
                    status = "Stopped"
                },
                enabled = isStreaming
            ) {
                Text("Stop")
            }
            OutlinedButton(onClick = {
                logLines.clear()
                val adbPath = adbPathUsed
                if (adbPath != null) {
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runLogcatClear(adbPath, device.serial)
                        }
                        result.onSuccess {
                            status = "Cleared logcat"
                        }
                        result.exceptionOrNull()?.let {
                            error = "Failed to clear logcat: ${it.localizedMessage}"
                        }
                    }
                }
            }) {
                Text("Clear")
            }
            OutlinedButton(onClick = {
                exportInfo = null
                exportError = null
                if (filteredLines.isEmpty()) {
                    exportError = "No log entries to export."
                    return@OutlinedButton
                }
                coroutineScope.launch {
                    val suggestedName = "logcat-${LocalDateTime.now().format(CSV_EXPORT_DATE_FORMAT)}.csv"
                    val targetFile = chooseExportFile(suggestedName, exportDir)
                    if (targetFile == null) {
                        exportInfo = null
                        exportError = null
                        return@launch
                    }
                    targetFile.parent?.let {
                        exportDir = it
                        onExportDirChange(it)
                    }
                    val entries = filteredLines.toList()
                    val writeResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val csvContent = logEntriesToCsv(entries)
                            targetFile.writeText(csvContent)
                        }
                    }
                    writeResult.onSuccess {
                        exportError = null
                        exportInfo = "Exported ${entries.size} rows to ${targetFile.absolutePath}"
                    }.onFailure { ex ->
                        exportInfo = null
                        exportError = "Failed to export: ${ex.localizedMessage ?: "unknown error"}"
                    }
                }
            }) {
                Text("Export CSV")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-scroll")
                Spacer(Modifier.width(8.dp))
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
            Spacer(Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.caption)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var levelMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { levelMenuExpanded = true }) {
                        Text("Level ≥ ${minLevel ?: "All"}")
                    }
                    DropdownMenu(expanded = levelMenuExpanded, onDismissRequest = { levelMenuExpanded = false }) {
                        val options = listOf<String?>(null, "V", "D", "I", "W", "E", "F")
                        options.forEach { option ->
                            DropdownMenuItem(onClick = {
                                minLevel = option
                                levelMenuExpanded = false
                            }) {
                                Text(option ?: "All")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = tagFilter,
                    onValueChange = { tagFilter = it },
                    label = { Text("Tag filter") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = messageFilter,
                    onValueChange = { messageFilter = it },
                    label = { Text("Message filter") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = pidFilter,
                    onValueChange = { pidFilter = it },
                    label = { Text("PID filter") },
                    singleLine = true,
                    modifier = Modifier.widthIn(min = 120.dp)
                )
                OutlinedTextField(
                    value = pidPackage,
                    onValueChange = {
                        pidPackage = it
                        pidLookupMessage = null
                        pidLookupIsError = false
                        packageActionMessage = null
                        packageActionIsError = false
                    },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val adbPath = adbPathUsed
                        if (adbPath != null && pidPackage.isNotBlank()) {
                            coroutineScope.launch {
                                val pkg = pidPackage.trim()
                                pidLookupIsError = false
                                pidLookupMessage = "Looking up PID for $pkg…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        runAdbCommand(
                                            adbPath,
                                            listOf("-s", device.serial, "shell", "pidof", "-s", pkg)
                                        ).trim()
                                    }
                                }
                                if (result.isSuccess) {
                                    val output = result.getOrNull().orEmpty()
                                    if (output.isNotBlank()) {
                                        pidFilter = output
                                        pidLookupMessage = "PID for $pkg is $output"
                                        pidLookupIsError = false
                                    } else {
                                        pidLookupMessage = "No PID found for $pkg"
                                        pidLookupIsError = true
                                    }
                                } else {
                                    val ex = result.exceptionOrNull()
                                    pidLookupMessage = "PID lookup failed: ${ex?.localizedMessage ?: "unknown error"}"
                                    pidLookupIsError = true
                                }
                            }
                        }
                    },
                    enabled = adbPathUsed != null && pidPackage.isNotBlank()
                ) {
                    Text("Fetch PID")
                }
                Button(
                    onClick = {
                        val adbPath = adbPathUsed
                        if (adbPath != null && pidPackage.isNotBlank()) {
                            coroutineScope.launch {
                                val pkg = pidPackage.trim()
                                packageActionIsError = false
                                packageActionMessage = "Clearing data for $pkg…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        runAdbCommand(
                                            adbPath,
                                            listOf("-s", device.serial, "shell", "pm", "clear", pkg)
                                        )
                                    }
                                }
                                result.onSuccess { output ->
                                    packageActionIsError = false
                                    val msg = output.trim().ifBlank { "Success" }
                                    packageActionMessage = "Cleared $pkg ($msg)"
                                }.onFailure { ex ->
                                    packageActionIsError = true
                                    packageActionMessage = "Failed to clear $pkg: ${ex.localizedMessage ?: "unknown error"}"
                                }
                            }
                        }
                    },
                    enabled = adbPathUsed != null && pidPackage.isNotBlank()
                ) {
                    Text("Clear data")
                }
                Button(
                    onClick = {
                        val adbPath = adbPathUsed
                        if (adbPath != null && pidPackage.isNotBlank()) {
                            coroutineScope.launch {
                                val pkg = pidPackage.trim()
                                packageActionIsError = false
                                packageActionMessage = "Uninstalling $pkg…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        runAdbCommand(
                                            adbPath,
                                            listOf("-s", device.serial, "uninstall", pkg)
                                        )
                                    }
                                }
                                result.onSuccess { output ->
                                    packageActionIsError = false
                                    val msg = output.trim().ifBlank { "Success" }
                                    packageActionMessage = "Uninstalled $pkg ($msg)"
                                }.onFailure { ex ->
                                    packageActionIsError = true
                                    packageActionMessage = "Failed to uninstall $pkg: ${ex.localizedMessage ?: "unknown error"}"
                                }
                            }
                        }
                    },
                    enabled = adbPathUsed != null && pidPackage.isNotBlank()
                ) {
                    Text("Uninstall")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    minLevel = null
                    tagFilter = ""
                    messageFilter = ""
                    pidFilter = ""
                    pidPackage = ""
                    pidLookupMessage = null
                    pidLookupIsError = false
                    packageActionMessage = null
                    packageActionIsError = false
                    apkPath = initialApkPath.orEmpty()
                    startTimeFilter = ""
                    endTimeFilter = ""
                }) {
                    Text("Reset")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = apkPath,
                    onValueChange = {
                        apkPath = it
                        packageActionMessage = null
                        packageActionIsError = false
                    },
                    label = { Text("APK path") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    coroutineScope.launch {
                        val currentSelection = apkPath.takeIf { it.isNotBlank() } ?: initialApkPath
                        val chosen = chooseApkFile(currentSelection)
                        if (chosen != null) {
                            val absolute = chosen.absolutePath
                            apkPath = absolute
                            packageActionIsError = false
                            packageActionMessage = "Selected APK: ${chosen.name}"
                            onApkPathChange(absolute)
                        }
                    }
                }) {
                    Text("Browse APK")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(
                        checked = reinstallOnInstall,
                        onCheckedChange = { reinstallOnInstall = it }
                    )
                    Text("Reinstall (-r)")
                }
                Button(
                    onClick = {
                        val adbPath = adbPathUsed
                        val path = apkPath.trim()
                        if (adbPath == null) {
                            packageActionIsError = true
                            packageActionMessage = "ADB path unknown; refresh devices."
                            return@Button
                        }
                        if (path.isBlank()) {
                            packageActionIsError = true
                            packageActionMessage = "Select an APK to install."
                            return@Button
                        }
                        val file = File(path)
                        if (!file.exists()) {
                            packageActionIsError = true
                            packageActionMessage = "APK not found at ${file.absolutePath}"
                            return@Button
                        }
                        coroutineScope.launch {
                            packageActionIsError = false
                            packageActionMessage = "Installing ${file.name}…"
                            onApkPathChange(file.absolutePath)
                            val args = buildList {
                                add("-s")
                                add(device.serial)
                                add("install")
                                if (reinstallOnInstall) add("-r")
                                add(file.absolutePath)
                            }
                            val result = withContext(Dispatchers.IO) {
                                runCatching { runAdbCommand(adbPath, args, timeoutSeconds = 120) }
                            }
                            result.onSuccess { output ->
                                val msg = output.trim().ifBlank { "Success" }
                                packageActionIsError = false
                                packageActionMessage = "Installed ${file.name} ($msg)"
                            }.onFailure { ex ->
                                packageActionIsError = true
                                packageActionMessage =
                                    "Failed to install ${file.name}: ${ex.localizedMessage ?: "unknown error"}"
                            }
                        }
                    },
                    enabled = adbPathUsed != null
                ) {
                    Text("Install APK")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = startTimeFilter,
                    onValueChange = { startTimeFilter = it },
                    label = { Text("Start time (MM-dd HH:mm:ss.SSS)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endTimeFilter,
                    onValueChange = { endTimeFilter = it },
                    label = { Text("End time (MM-dd HH:mm:ss.SSS)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        pidLookupMessage?.let { message ->
            val color = if (pidLookupIsError) MaterialTheme.colors.error else MaterialTheme.colors.primary
            Text(message, color = color, style = MaterialTheme.typography.caption)
        }
        timeFilterResult.error?.let { message ->
            Text(message, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
        }
        packageActionMessage?.let { message ->
            val color = if (packageActionIsError) MaterialTheme.colors.error else MaterialTheme.colors.primary
            Text(message, color = color, style = MaterialTheme.typography.caption)
        }
        error?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
        }
        exportInfo?.let {
            Text(it, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
        }
        exportError?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Time", modifier = Modifier.weight(0.22f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("PID", modifier = Modifier.weight(0.08f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("TID", modifier = Modifier.weight(0.08f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Lvl", modifier = Modifier.weight(0.07f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Tag", modifier = Modifier.weight(0.2f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Message", modifier = Modifier.weight(0.35f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Divider()
                SelectAllSelectionContainer(
                    onSelectAllFallback = {
                        if (copyAllText.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(copyAllText))
                        }
                    }
                ) {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                            state = listState
                        ) {
                            items(filteredLines) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = entry.timestamp ?: "",
                                        modifier = Modifier.weight(0.22f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.pid ?: "",
                                        modifier = Modifier.weight(0.08f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.tid ?: "",
                                        modifier = Modifier.weight(0.08f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.level ?: "",
                                        modifier = Modifier.weight(0.07f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2,
                                        color = levelColor(entry.level),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.tag ?: "",
                                        modifier = Modifier.weight(0.2f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.message,
                                        modifier = Modifier.weight(0.35f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.body2
                                    )
                                }
                                Divider()
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectAllSelectionContainer(
    modifier: Modifier = Modifier,
    onSelectAllFallback: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val defaultMenu = TextContextMenu.Default
    CompositionLocalProvider(
        LocalTextContextMenu provides remember(onSelectAllFallback) {
            object : TextContextMenu {
                @Composable
                override fun Area(
                    textManager: TextContextMenu.TextManager,
                    state: androidx.compose.foundation.ContextMenuState,
                    content: @Composable () -> Unit
                ) {
                    val localization = LocalLocalization.current
                    val defaultSelectAll = textManager.selectAll
                    val selectAllAction = defaultSelectAll ?: run {
                        createSelectAllCallback(textManager, onSelectAllFallback)
                    }
                    if (selectAllAction != null && defaultSelectAll == null) {
                        defaultMenu.Area(textManager, state) {
                            ContextMenuDataProvider(
                                items = {
                                    listOf(
                                        ContextMenuItem(localization.selectAll) {
                                            selectAllAction()
                                        }
                                    )
                                }
                            ) {
                                content()
                            }
                        }
                    } else {
                        defaultMenu.Area(textManager, state, content)
                    }
                }
            }
        }
    ) {
        SelectionContainer(modifier = modifier, content = content)
    }
}

private fun createSelectAllCallback(
    textManager: TextContextMenu.TextManager,
    fallback: (() -> Unit)?
): (() -> Unit)? {
    val manager = runCatching {
        textManager.javaClass.declaredFields.firstOrNull { field ->
            field.type.name.contains("SelectionManager")
        }?.apply { isAccessible = true }?.get(textManager)
    }.getOrNull() ?: return fallback
    val selectAllMethod = runCatching {
        manager.javaClass.getMethod("selectAll")
    }.getOrNull() ?: return fallback
    return {
        val result = runCatching { selectAllMethod.invoke(manager) }
        if (result.isFailure) {
            fallback?.invoke()
        }
    }
}

private fun parseFilterTimestamp(value: String, year: Int): LocalDateTime? {
    if (value.isBlank()) return null
    return parseLogTimestamp(value, year)
}

private fun parseLogTimestamp(value: String, year: Int = LocalDate.now().year): LocalDateTime? {
    return runCatching {
        val normalized = value.trim()
        val parsed = LocalDateTime.parse("$year-$normalized", LOG_TIMESTAMP_WITH_YEAR_FORMAT)
        parsed
    }.getOrNull()
}

private suspend fun chooseApkFile(initialPath: String?): File? =
    withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select APK"
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter("Android packages (*.apk)", "apk")
            isAcceptAllFileFilterUsed = true
            initialPath?.let { path ->
                val candidate = File(path)
                if (candidate.parentFile?.exists() == true) {
                    currentDirectory = candidate.parentFile
                }
                if (candidate.exists()) {
                    selectedFile = candidate
                }
            }
        }
        when (chooser.showOpenDialog(null)) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile
            else -> null
        }
    }

private suspend fun chooseExportFile(suggestedName: String, initialDir: String?): File? =
    withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export Logcat CSV"
            fileFilter = FileNameExtensionFilter("CSV files", "csv")
            if (!initialDir.isNullOrBlank()) {
                val dir = File(initialDir)
                if (dir.exists()) {
                    currentDirectory = dir
                }
            }
            selectedFile = File(currentDirectory, suggestedName)
        }
        when (chooser.showSaveDialog(null)) {
            JFileChooser.APPROVE_OPTION -> {
                val selected = chooser.selectedFile ?: return@withContext null
                val baseDir = selected.parentFile ?: chooser.currentDirectory
                val finalFile =
                    if (selected.extension.isBlank()) File(baseDir, "${selected.name}.csv")
                    else selected
                finalFile
            }
            else -> null
        }
    }

private fun logEntriesToCsv(entries: List<LogEntry>): String {
    val sb = StringBuilder()
    sb.appendLine("timestamp,pid,tid,level,tag,message")
    entries.forEach { entry ->
        sb.append(
            listOf(
                entry.timestamp,
                entry.pid,
                entry.tid,
                entry.level,
                entry.tag,
                entry.message
            ).joinToString(",") { escapeCsv(it) }
        )
        sb.appendLine()
    }
    return sb.toString()
}

private fun escapeCsv(value: String?): String {
    val text = value.orEmpty()
    val escaped = text.replace("\"", "\"\"")
    return "\"$escaped\""
}

fun main() = application {
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<AdbDevice?>(null) }
    var adbError by remember { mutableStateOf<String?>(null) }
    var dbg by remember { mutableStateOf(DebugInfo(null, null, null)) }
    var adbPathHint by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }
    var config by remember { mutableStateOf(loadConfig()) }

    fun refreshDevices() {
        val envAdb = System.getenv("LOGCAT_ADB") ?: System.getProperty("LOGCAT_ADB")
        val envSock = System.getenv("ADB_SERVER_SOCKET") ?: System.getProperty("ADB_SERVER_SOCKET")
        val res = runAdbDevices()
        val result = res.getOrNull()
        adbError = res.exceptionOrNull()?.message
        devices = result?.let { parseAdbDevices(it.output) } ?: emptyList()
        val resolvedAdb = result?.adbPath ?: envAdb
        adbPathHint = resolvedAdb
        dbg = DebugInfo(resolvedAdb, envSock, result?.output)

        // Auto-select from saved config if available
        val saved = config.selectedSerial
        if (selected == null && saved != null) {
            selected = devices.firstOrNull { it.serial == saved }
        } else if (selected != null && devices.none { it.serial == selected!!.serial }) {
            // Previously selected disappeared
            selected = null
        }
    }

    LaunchedEffect(Unit) { refreshDevices() }

    val windowTitle = when (val current = screen) {
        Screen.Picker -> "Logcat Filter – Device Picker"
        is Screen.Logcat -> "Logcat Filter – Logcat (${current.device.serial})"
    }

    Window(onCloseRequest = ::exitApplication, title = windowTitle) {
        MaterialTheme {
            when (val current = screen) {
                Screen.Picker -> DevicePicker(
                    devices = devices,
                    selected = selected,
                    onSelect = { selected = it },
                    onRefresh = ::refreshDevices,
                    onSaveSelection = { device ->
                        val newConfig = config.copy(selectedSerial = device.serial)
                        val result = saveConfig(newConfig)
                        if (result.isSuccess) {
                            selected = device
                            config = newConfig
                        }
                        result
                    },
                    onNavigateToLogcat = { device ->
                        screen = Screen.Logcat(device, adbPathHint, config.lastApkPath, config.lastExportDir)
                    },
                    adbError = adbError,
                    debugInfo = dbg
                )

                is Screen.Logcat -> LogcatScreen(
                    device = current.device,
                    adbPathHint = current.adbPathHint,
                    initialApkPath = current.lastApkPath,
                    onApkPathChange = { newPath ->
                        val newConfig = config.copy(lastApkPath = newPath)
                        if (saveConfig(newConfig).isSuccess) {
                            config = newConfig
                        }
                    },
                    initialExportDir = current.lastExportDir,
                    onExportDirChange = { newDir ->
                        val newConfig = config.copy(lastExportDir = newDir)
                        if (saveConfig(newConfig).isSuccess) {
                            config = newConfig
                        }
                    },
                    onBack = {
                        screen = Screen.Picker
                        refreshDevices()
                    }
                )
            }
        }
    }
}
