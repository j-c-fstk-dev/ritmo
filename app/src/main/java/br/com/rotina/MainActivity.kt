package br.com.rotina

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RitmoApp() }
    }
}

private val listColors = RitmoPalettes.lists
private val taskPalette = RitmoPalettes.tasks
private val appPalettes = RitmoPalettes.app
private data class QuickCapture(val lines: List<String>, val asSubtasks: Boolean = false)
private fun applyNoteTool(value: TextFieldValue, action: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
    val markers = when (action) { "B" -> "**"; "I" -> "_"; "U" -> "__"; else -> null }
    val replacement = when {
        markers != null -> if (selected.isEmpty()) markers + markers else "$markers$selected$markers"
        action == "•" -> selected.lineSequence().joinToString("\n") { "• $it" }
        action == "1." -> selected.lineSequence().mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
        else -> "🙂"
    }
    val text = value.text.replaceRange(start, end, replacement)
    if (markers != null && selected.isNotEmpty()) return TextFieldValue(text, TextRange(start + markers.length, start + markers.length + selected.length))
    if (action == "•" || action == "1.") return TextFieldValue(text, if (selected.isEmpty()) TextRange(start + replacement.length) else TextRange(start, start + replacement.length))
    val cursor = if (markers != null && selected.isEmpty()) start + markers.length else start + replacement.length
    return TextFieldValue(text, TextRange(cursor))
}
private fun noteToolActive(value: TextFieldValue, action: String): Boolean {
    val markers = when (action) { "B" -> "**"; "I" -> "_"; "U" -> "__"; else -> return false }
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val start = if (value.selection.collapsed) cursor else value.selection.min
    val end = if (value.selection.collapsed) cursor else value.selection.max
    return start >= markers.length && value.text.substring(start - markers.length, start) == markers && value.text.substring(end, (end + markers.length).coerceAtMost(value.text.length)) == markers
}
private fun insertAtSelection(value: TextFieldValue, insertion: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    return TextFieldValue(value.text.replaceRange(start, end, insertion), TextRange(start + insertion.length))
}
@Composable
private fun EmojiPickerButton(label: String = "🙂", accessibilityLabel: String = "Inserir emoji", onPicked: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val latestPicked = rememberUpdatedState(onPicked)
    IconButton(onClick = { visible = true }, modifier = Modifier.size(RitmoTokens.minTouchTarget).semantics { contentDescription = accessibilityLabel }) {
        Text(label, fontSize = 20.sp)
    }
    if (visible) Dialog(
        onDismissRequest = { visible = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val pickerDark = MaterialTheme.colorScheme.background.luminance() < .35f
        val pickerSurface = MaterialTheme.colorScheme.surface.toArgbInt()
        Surface(
            Modifier.fillMaxWidth(.94f).fillMaxHeight(.78f).heightIn(max = 470.dp),
            shape = RoundedCornerShape(RitmoTokens.cornerCard),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Emojis", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { visible = false }) { Text("Fechar") }
                }
                AndroidView(
                    factory = { context -> EmojiPickerView(ContextThemeWrapper(context,
                        if (pickerDark) R.style.RitmoEmojiPickerDark else R.style.RitmoEmojiPickerLight
                    )).apply {
                        emojiGridColumns = 8
                        setBackgroundColor(pickerSurface)
                        setOnEmojiPickedListener { item -> visible = false; latestPicked.value(item.emoji) }
                    } },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmojiButton(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, afterInsert: () -> Unit = {}) {
    EmojiPickerButton(accessibilityLabel = "Inserir emoji") { emoji -> onValue(insertAtSelection(value, emoji)); afterInsert() }
}
@Composable
private fun VoiceButton(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, afterInsert: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stopSignal by remember { mutableStateOf<java.util.concurrent.atomic.AtomicBoolean?>(null) }
    val latestOnValue = rememberUpdatedState(onValue)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginDictation(value, context, scope, { listening = it }, { status = it }, { stopSignal = it }, latestOnValue.value, afterInsert)
        else android.widget.Toast.makeText(context, "Permita o microfone para ditar texto.", android.widget.Toast.LENGTH_SHORT).show()
    }
    IconButton(
        onClick = {
            if (listening) {
                stopSignal?.set(true)
                status = "Encerrando…"
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                beginDictation(value, context, scope, { listening = it }, { status = it }, { stopSignal = it }, latestOnValue.value, afterInsert)
            } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = if (listening) "Parar ditado" else "Ditar texto"
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (listening) "■" else "🎙️", fontSize = 18.sp, color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            val shortStatus = when {
                listening && status.startsWith("Baixando") -> "Baixando…"
                listening && status == "Encerrando…" -> "Encerrando…"
                listening -> "Ouvindo…"
                else -> ""
            }
            if (shortStatus.isNotBlank()) Text(shortStatus, fontSize = 8.sp, color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun beginDictation(
    value: TextFieldValue,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setListening: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setStopSignal: (java.util.concurrent.atomic.AtomicBoolean) -> Unit,
    onValue: (TextFieldValue) -> Unit,
    afterInsert: () -> Unit
) {
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    setStopSignal(stop)
    setStatus("Preparando…")
    setListening(true)
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val prefix = value.text.substring(0, start)
    val suffix = value.text.substring(end)
    var committed = ""
    scope.launch {
        try {
            OfflineDictation.run(context, stop, { message ->
                setStatus(message)
            }) { spoken, isFinal ->
                if (isFinal) {
                    committed = listOf(committed.trimEnd(), spoken).filter(String::isNotBlank).joinToString(" ") + " "
                }
                val partial = if (isFinal) "" else spoken
                val inserted = committed + partial
                val next = TextFieldValue(prefix + inserted + suffix, TextRange(prefix.length + inserted.length))
                onValue(next)
            }
            if (committed.isNotEmpty()) afterInsert()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            setStatus(error.message ?: "Falha no ditado. Toque para tentar novamente.")
            android.widget.Toast.makeText(context, error.message ?: "Falha no ditado.", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            setListening(false)
            setStopSignal(java.util.concurrent.atomic.AtomicBoolean(false))
        }
    }
}
private fun continueNoteList(value: TextFieldValue): TextFieldValue {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    if (cursor == 0 || value.text.getOrNull(cursor - 1) != '\n') return value
    val lineStart = value.text.lastIndexOf('\n', (cursor - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val line = value.text.substring(lineStart, cursor - 1)
    val marker = when {
        line.startsWith("• ") -> "• "
        Regex("^\\d+\\. ").containsMatchIn(line) -> "${line.takeWhile { it.isDigit() }.toIntOrNull()?.plus(1) ?: 1}. "
        else -> null
    } ?: return value
    if (line == marker.trimEnd()) return TextFieldValue(value.text.removeRange(lineStart, cursor - 1), TextRange(lineStart))
    return TextFieldValue(value.text.substring(0, cursor) + marker + value.text.substring(cursor), TextRange(cursor + marker.length))
}
private fun richText(source: String) = androidx.compose.ui.text.buildAnnotatedString {
    val regex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)|(?<!_)_([^_\\n]+?)_(?!_)")
    var cursor = 0
    regex.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val content = match.groupValues.drop(1).first { it.isNotEmpty() }
        val style = when {
            match.value.startsWith("**") -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            match.value.startsWith("__") -> androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.Underline)
            else -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }
        pushStyle(style); append(content); pop()
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}
private fun tintFor(list: TaskList?) = listColors.getOrElse(list?.color ?: 0) { listColors[0] }
private fun onAccent(color: Color) = Color(contentArgb(color.toArgbInt()))
private fun Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
@Composable
private fun readableAccent(color: Color): Color = Color(accentArgbOn(MaterialTheme.colorScheme.background.toArgbInt(), color.toArgbInt(), 4.5))
private fun dateLabel(time: Long) = SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(Date(time))
private fun dateTimeLabel(time: Long) = SimpleDateFormat("d 'de' MMM, HH:mm", Locale("pt", "BR")).format(Date(time))
private fun rootList(id: String?, lists: List<TaskList>): TaskList? {
    val item = lists.firstOrNull { it.id == id } ?: return null
    return if (item.parentId == null) item else lists.firstOrNull { it.id == item.parentId }
}
private fun destinationLabel(item: TaskList, lists: List<TaskList>): String {
    val root = rootList(item.id, lists)
    val base = if (item.parentId == null) item.name else "${root?.name} · ${item.name}"
    val sameNameCount = lists.count { it.name == item.name && it.parentId == item.parentId }
    return if (sameNameCount > 1) "$base · ${item.id.takeLast(5)}" else base
}
private fun tabLabel(item: TaskList, root: TaskList, tabs: List<TaskList>): String = when {
    item.id == root.id -> "Geral"
    tabs.count { it.name == item.name } > 1 -> "${item.name} · ${item.id.takeLast(5)}"
    else -> item.name
}

private data class ListDialogState(val editing: TaskList? = null, val parentId: String? = null)

@Composable
private fun RitmoApp() {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val dao = remember { db.dao() }
    val listsFlow = remember { dao.lists().map { it as List<TaskList>? } }
    val emittedLists by listsFlow.collectAsStateWithLifecycle(initialValue = null)
    val lists = emittedLists.orEmpty()
    val appPrefs = remember { context.getSharedPreferences("ritmo", 0) }
    val backupPrefs = remember { context.getSharedPreferences(Backup.PREF, 0) }
    val scope = rememberCoroutineScope()
    val detailStateHolder = rememberSaveableStateHolder()
    val saveGeneration = remember { java.util.concurrent.ConcurrentHashMap<String, Long>() }

    var themeMode by remember { mutableStateOf(appPrefs.getString("themeMode", if (appPrefs.getBoolean("dark", false)) "Escuro" else "Claro") ?: "Sistema") }
    var paletteIndex by remember { mutableIntStateOf(appPrefs.getInt("palette", 0).coerceIn(appPalettes.indices)) }
    val dark = when (themeMode) { "Claro" -> false; "Escuro" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val paletteColor = appPalettes[paletteIndex]
    var initialized by remember { mutableStateOf(false) }
    var activeRootId by remember { mutableStateOf<String?>(null) }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusNoteOnOpen by rememberSaveable { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf<ListDialogState?>(null) }
    var listToDelete by remember { mutableStateOf<TaskList?>(null) }
    var restoreCounts by remember { mutableStateOf<Pair<android.net.Uri, BackupSummary>?>(null) }
    var message by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val editingTaskFlow = remember(editingTaskId) { editingTaskId?.let { dao.taskFlow(it) } }
    val editingTask = editingTaskFlow?.collectAsStateWithLifecycle(initialValue = null)?.value
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val saveEditedTask: (TaskItem, (Boolean) -> Unit) -> Unit = { updated, complete ->
        val generation = saveGeneration.merge(updated.id, 1L, Long::plus)!!
        scope.launch {
            try {
                val accepted = db.withTransaction {
                    val previous = dao.task(updated.id) ?: return@withTransaction false
                    if (saveGeneration[updated.id] != generation || dao.allLists().none { it.id == updated.listId }) return@withTransaction false
                    dao.putTask(updated)
                    if (updated.parentTaskId == null && updated.listId != previous.listId) dao.children(updated.id).forEach { dao.putTask(it.copy(listId = updated.listId)) }
                    true
                }
                if (!accepted) {
                    if (saveGeneration[updated.id] == generation && dao.task(updated.id) != null && dao.allLists().none { it.id == updated.listId }) message = "A lista escolhida foi excluída. Sua edição foi mantida; escolha outro destino."
                    complete(false); return@launch
                }
                Reminders.schedule(context, updated); Backup.markDirty(context)
                locationForList(lists, updated.listId)?.let { activeRootId = it.rootId; activeTabId = it.tabId }
                complete(true)
            } catch (error: Exception) { message = "Não foi possível salvar: ${error.message ?: "tente novamente"}"; complete(false) }
        }
    }
    val toggleEditedChild: (TaskItem) -> Unit = { child -> scope.launch {
        val updated = child.copy(done = !child.done); dao.putTask(updated); Reminders.schedule(context, updated); Backup.markDirty(context)
    } }
    val addEditedChild: (String) -> Unit = { title -> scope.launch {
        val parent = editingTask
        if (title.isNotBlank() && parent != null) {
            val order = (dao.maxOrder(parent.listId, parent.id) ?: -1024L) + 1024L
            dao.putTask(TaskItem(UUID.randomUUID().toString(), parent.listId, parent.id, title.trim(), order = order)); Backup.markDirty(context)
        }
    } }
    val saveEditedChild: (TaskItem) -> Unit = { child -> scope.launch { if (dao.task(child.id) != null) dao.putTask(child); Backup.markDirty(context) } }

    // The first Flow value is the database result. Startup is read-only: an empty/loading
    // emission must never create a list, and existing IDs (including same-name lists) survive.
    LaunchedEffect(emittedLists) {
        val snapshot = emittedLists ?: return@LaunchedEffect
        if (!initialized) {
            val location = resolveListLocation(
                snapshot,
                appPrefs.getString("lastListId", null),
                appPrefs.getString("lastSublistId", null)
            )
            activeRootId = location?.rootId
            activeTabId = location?.tabId
            initialized = true
        } else if (snapshot.isNotEmpty()) {
            val location = resolveListLocation(snapshot, activeRootId, activeTabId)
            if (location != null && (location.rootId != activeRootId || location.tabId != activeTabId)) {
                activeRootId = location.rootId
                activeTabId = location.tabId
            }
        }
    }
    LaunchedEffect(initialized, activeRootId, activeTabId, emittedLists) {
        if (!initialized) return@LaunchedEffect
        val root = lists.firstOrNull { it.id == activeRootId && it.parentId == null } ?: return@LaunchedEffect
        val tab = lists.firstOrNull { it.id == activeTabId && (it.id == root.id || it.parentId == root.id) } ?: return@LaunchedEffect
        appPrefs.edit().putString("lastListId", root.id).putString("lastSublistId", tab.id).apply()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            backupPrefs.edit().putString("tree", uri.toString()).putString("error", "").apply()
            Backup.scheduleDaily(context)
            message = "Pasta de backup selecionada."
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val summary = Backup.summary(context, uri)
                restoreCounts = uri to summary
            } catch (error: Exception) {
                message = "Backup inválido: ${error.message ?: "formato não reconhecido"}"
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val scheme = if (dark) darkColorScheme(
        primary = lerp(paletteColor, Color.White, .34f), onPrimary = Color(0xFF10231F), primaryContainer = paletteColor.copy(alpha = .38f), onPrimaryContainer = Color(0xFFEAF4EF),
        secondary = Color(0xFFE7B58F), onSecondary = Color(0xFF352013), background = Color(0xFF141916), onBackground = Color(0xFFF0F3EF),
        surface = Color(0xFF202824), onSurface = Color(0xFFF0F3EF), surfaceVariant = Color(0xFF303A35), onSurfaceVariant = Color(0xFFD0D8D2),
        outline = Color(0xFF89968F), error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
    ) else lightColorScheme(
        primary = paletteColor, onPrimary = Color.White, primaryContainer = paletteColor.copy(alpha = .18f), onPrimaryContainer = Color(0xFF103B33),
        secondary = Color(0xFF9A4E32), onSecondary = Color.White, background = Color(0xFFF5F4EF), onBackground = Color(0xFF1D2824),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D2824), surfaceVariant = Color(0xFFE8ECE8), onSurfaceVariant = Color(0xFF46524D),
        outline = Color(0xFF727E78)
    )

    val view = LocalView.current
    SideEffect {
        val window = (context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = scheme.background.toArgbInt()
            window.navigationBarColor = scheme.background.toArgbInt()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    BackHandler(enabled = editingTaskId != null || settingsOpen || listDialog != null || listToDelete != null) {
        when {
            editingTaskId != null -> editingTaskId = null
            listDialog != null -> listDialog = null
            listToDelete != null -> listToDelete = null
            else -> settingsOpen = false
        }
    }

    MaterialTheme(colorScheme = scheme, typography = RitmoTypography) {
        ModalNavigationDrawer(
            drawerState = drawerState,
                    gesturesEnabled = editingTaskId == null && listDialog == null,
            drawerContent = {
                DrawerContent(
                    lists = lists,
                    activeTabId = activeTabId,
                    onChoose = { id ->
                        val location = locationForList(lists, id)
                        if (location != null) {
                            activeRootId = location.rootId
                            activeTabId = location.tabId
                            settingsOpen = false
                            scope.launch { drawerState.close() }
                        }
                    },
                    onNewList = { listDialog = ListDialogState() },
                    onNewSublist = { parent -> listDialog = ListDialogState(parentId = parent.id) },
                    onEdit = { item -> listDialog = ListDialogState(editing = item) },
                    onDelete = { item -> listToDelete = item },
                    onSettings = { settingsOpen = true; scope.launch { drawerState.close() } }
                )
            }
        ) {
            when {
                editingTaskId != null && editingTask != null && !isTablet -> {
                    val task = editingTask!!
                    detailStateHolder.SaveableStateProvider("task:${task.id}") {
                        TaskDetailScreen(
                            task = task,
                            lists = lists,
                            dao = dao,
                            onClose = { editingTaskId = null; focusNoteOnOpen = false },
                            onSave = saveEditedTask,
                            onToggleChild = toggleEditedChild,
                            onAddChild = addEditedChild,
                            onSaveChild = saveEditedChild,
                            focusNoteOnOpen = focusNoteOnOpen,
                        )
                    }
                }
                settingsOpen -> SettingsScreen(
                    themeMode = themeMode,
                    setThemeMode = { themeMode = it; appPrefs.edit().putString("themeMode", it).putBoolean("dark", it == "Escuro").apply() },
                    paletteIndex = paletteIndex,
                    setPalette = { paletteIndex = it; appPrefs.edit().putInt("palette", it).apply() },
                    chooseFolder = { folderPicker.launch(null) },
                    backupNow = {
                        scope.launch {
                            try {
                                val tree = backupPrefs.getString("tree", null)?.let(android.net.Uri::parse)
                                    ?: error("Escolha uma pasta de backup primeiro.")
                                val doc = android.provider.DocumentsContract.createDocument(
                                    context.contentResolver, Backup.folderDocument(tree), "application/zip",
                                    "ritmo-${System.currentTimeMillis()}.ritmo"
                                ) ?: error("Não foi possível criar o arquivo de backup.")
                                Backup.save(context, doc)
                                message = "Backup salvo com sucesso."
                            } catch (error: Exception) {
                                val reason = error.message ?: "Não foi possível salvar o backup."
                                backupPrefs.edit().putString("error", reason).apply()
                                message = reason
                            }
                        }
                    },
                    restore = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/json", "text/*")) },
                    backupPrefs = backupPrefs,
                    back = { settingsOpen = false }
                )
                !initialized -> LoadingScreen()
                lists.isEmpty() -> FirstListScreen(onCreate = { listDialog = ListDialogState() })
                else -> {
                    val root = rootList(activeRootId, lists) ?: rootList(lists.firstOrNull { it.parentId == null }?.id, lists)
                    if (root == null) FirstListScreen(onCreate = { listDialog = ListDialogState() })
                    else {
                        val selectedTab = lists.firstOrNull { it.id == activeTabId && (it.id == root.id || it.parentId == root.id) } ?: root
                        val tasks by dao.tasks(selectedTab.id).collectAsStateWithLifecycle(initialValue = emptyList())
                        WorkScreen(
                            root = root,
                            selectedTab = selectedTab,
                            tabs = listOf(root) + lists.filter { it.parentId == root.id },
                            tasks = tasks,
                            dao = dao,
                            drawerOpen = { scope.launch { drawerState.open() } },
                            selectTab = { id -> activeTabId = id },
                            addTasks = { capture, complete ->
                                scope.launch {
                                    try {
                                        val validTitles = capture.lines.filter { it.isNotBlank() }
                                        db.withTransaction {
                                            var order = dao.maxOrder(selectedTab.id, null) ?: -1024L
                                            if (capture.asSubtasks && validTitles.isNotEmpty()) {
                                                order += 1024L
                                                val parentId = UUID.randomUUID().toString()
                                                dao.putTask(TaskItem(parentId, selectedTab.id, title = validTitles.first().trim(), order = order))
                                                var childOrder = -1024L
                                                validTitles.drop(1).forEach { title ->
                                                    childOrder += 1024L
                                                    dao.putTask(TaskItem(UUID.randomUUID().toString(), selectedTab.id, parentId, title.trim(), order = childOrder))
                                                }
                                            } else validTitles.forEach { title ->
                                                order += 1024L
                                                dao.putTask(TaskItem(UUID.randomUUID().toString(), selectedTab.id, title = title.trim(), order = order))
                                            }
                                        }
                                        if (validTitles.isNotEmpty()) Backup.markDirty(context)
                                        complete(validTitles.isNotEmpty())
                                    } catch (_: Exception) {
                                        complete(false)
                                    }
                                }
                            },
                            toggleTask = { task ->
                                scope.launch {
                                    val updated = db.withTransaction {
                                        val current = dao.task(task.id) ?: return@withTransaction null
                                        current.copy(done = !current.done).also { dao.putTask(it) }
                                    } ?: return@launch
                                    Reminders.schedule(context, updated)
                                    Backup.markDirty(context)
                                }
                            },
                            archiveTask = { task, archived ->
                                scope.launch {
                                    val updated = db.withTransaction {
                                        val current = dao.task(task.id) ?: return@withTransaction null
                                        if (current.archived == archived) return@withTransaction null
                                        current.copy(archived = archived).also { dao.putTask(it) }
                                    } ?: return@launch
                                    if (archived) Reminders.cancel(context, task.id) else Reminders.schedule(context, updated)
                                    Backup.markDirty(context)
                                    android.widget.Toast.makeText(context, if (archived) "Tarefa arquivada" else "Tarefa restaurada", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            openTask = { task, focusNote -> editingTaskId = task.id; focusNoteOnOpen = focusNote },
                            deleteTask = { task ->
                                scope.launch {
                                    val childTasks = dao.children(task.id)
                                    childTasks.forEach { Reminders.cancel(context, it.id) }
                                    Reminders.cancel(context, task.id)
                                    val doomedIds = childTasks.map { it.id }.toSet() + task.id
                                    val files = dao.allAttachments().filter { it.taskId in doomedIds }
                                    db.withTransaction { dao.deleteTask(task.id) }
                                    files.forEach { runCatching { AttachmentStore.resolve(context, it.relativePath).delete() } }
                                    Backup.markDirty(context)
                                }
                            },
                            addSublist = { listDialog = ListDialogState(parentId = root.id) },
                            moveTask = { task, delta -> scope.launch {
                                val changed = db.withTransaction {
                                    val ordered = dao.allTasks()
                                        .filter { it.listId == task.listId && it.parentTaskId == null && it.archived == task.archived }
                                        .sortedBy { it.order }.toMutableList()
                                    val from = ordered.indexOfFirst { it.id == task.id }
                                    if (from < 0) return@withTransaction false
                                    val to = (from + delta).coerceIn(0, ordered.lastIndex)
                                    if (from == to) return@withTransaction false
                                    ordered.add(to, ordered.removeAt(from))
                                    ordered.forEachIndexed { index, item -> dao.setTaskOrder(item.id, index * 1024L) }
                                    true
                                }
                                if (changed) Backup.markDirty(context)
                            } }
                        )
                        if (isTablet && editingTask != null) Dialog(onDismissRequest = { editingTaskId = null; focusNoteOnOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
                            Surface(Modifier.fillMaxWidth(.86f).widthIn(max = 820.dp).fillMaxHeight(.9f), shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp) {
                                detailStateHolder.SaveableStateProvider("task:${editingTask!!.id}") {
                                    TaskDetailScreen(
                                        task = editingTask!!, lists = lists, dao = dao,
                                        onClose = { editingTaskId = null; focusNoteOnOpen = false }, onSave = saveEditedTask,
                                        onToggleChild = toggleEditedChild, onAddChild = addEditedChild, onSaveChild = saveEditedChild,
                                        focusNoteOnOpen = focusNoteOnOpen, modal = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        listDialog?.let { state ->
            ListEditorDialog(
                state = state,
                onDismiss = { listDialog = null },
                onSave = { name, color ->
                    scope.launch {
                        val saved = state.editing?.copy(name = name, color = color)
                            ?: TaskList(UUID.randomUUID().toString(), name, state.parentId, color)
                        dao.putList(saved)
                        Backup.markDirty(context)
                        val location = locationForList(lists + saved, saved.id)
                        if (location != null) { activeRootId = location.rootId; activeTabId = location.tabId }
                        listDialog = null
                        if (state.parentId == null) settingsOpen = false
                        scope.launch { drawerState.close() }
                    }
                }
            )
        }
        listToDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { listToDelete = null },
                title = { Text("Excluir ${item.name}?") },
                text = { Text("As tarefas e subtarefas desta lista também serão removidas.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val doomedIds = if (item.parentId == null) lists.filter { it.id == item.id || it.parentId == item.id }.map { it.id }.toSet() else setOf(item.id)
                            val doomedTasks = dao.allTasks().filter { it.listId in doomedIds }
                            doomedTasks.forEach { Reminders.cancel(context, it.id) }
                            val doomedTaskIds = doomedTasks.map { it.id }.toSet()
                            val files = dao.allAttachments().filter { it.taskId in doomedTaskIds }
                            db.withTransaction {
                                dao.deleteTasksInListTree(item.id)
                                dao.deleteListTree(item.id)
                            }
                            files.forEach { runCatching { AttachmentStore.resolve(context, it.relativePath).delete() } }
                            Backup.markDirty(context)
                            listToDelete = null
                        }
                    }) { Text("Excluir") }
                },
                dismissButton = { TextButton(onClick = { listToDelete = null }) { Text("Cancelar") } }
            )
        }
        restoreCounts?.let { (uri, summary) ->
            AlertDialog(
                onDismissRequest = { restoreCounts = null },
                title = { Text("Restaurar backup?") },
                text = { Text("O arquivo contém ${summary.lists} listas, ${summary.tasks} tarefas e ${summary.attachments} anexos. Ele substituirá os dados atuais.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                Backup.restore(context, uri)
                                Backup.markDirty(context)
                                message = "Backup restaurado."
                            } catch (error: Exception) {
                                message = "Falha ao restaurar: ${error.message}"
                            }
                            restoreCounts = null
                        }
                    }) { Text("Restaurar") }
                },
                dismissButton = { TextButton(onClick = { restoreCounts = null }) { Text("Cancelar") } }
            )
        }
        if (message.isNotBlank()) AlertDialog(
            onDismissRequest = { message = "" },
            confirmButton = { TextButton(onClick = { message = "" }) { Text("OK") } },
            text = { Text(message) }
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FirstListScreen(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
            .navigationBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("RITMO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(18.dp))
        Text("Um lugar para organizar o que importa.", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Crie sua primeira lista para começar. Seus dados ficam neste aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Criar primeira lista", fontSize = 16.sp)
        }
    }
}

@Composable
private fun DrawerContent(
    lists: List<TaskList>, activeTabId: String?, onChoose: (String) -> Unit,
    onNewList: () -> Unit, onNewSublist: (TaskList) -> Unit, onEdit: (TaskList) -> Unit,
    onDelete: (TaskList) -> Unit, onSettings: () -> Unit
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(28.dp))
            Row(Modifier.padding(start = 16.dp, top = 17.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_ritmo_mark), contentDescription = null, modifier = Modifier.size(30.dp))
                Text("Ritmo", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Text("Suas listas", Modifier.padding(start = 54.dp, top = 1.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(lists.filter { it.parentId == null }, key = { it.id }) { list ->
                    val expanded = menuFor == list.id
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NavigationDrawerItem(
                            label = { Text(destinationLabel(list, lists), maxLines = 1) },
                            selected = activeTabId == list.id,
                            onClick = { onChoose(list.id) },
                            icon = { Box(Modifier.size(13.dp).clip(CircleShape).background(tintFor(list))) },
                            modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurface)
                        )
                        Box {
                            TextButton(onClick = { menuFor = if (expanded) null else list.id }, modifier = Modifier.semantics { contentDescription = "Ações da lista ${list.name}" }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Nova sublista") }, onClick = { menuFor = null; onNewSublist(list) })
                                DropdownMenuItem(text = { Text("Editar lista") }, onClick = { menuFor = null; onEdit(list) })
                                DropdownMenuItem(text = { Text("Excluir lista") }, onClick = { menuFor = null; onDelete(list) })
                            }
                        }
                    }
                    lists.filter { it.parentId == list.id }.forEach { sub ->
                        key(sub.id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NavigationDrawerItem(
                                    label = { Text(destinationLabel(sub, lists), maxLines = 1) }, selected = activeTabId == sub.id,
                                    onClick = { onChoose(sub.id) },
                                    icon = { Text("↳", color = tintFor(list), fontSize = 18.sp) },
                                    modifier = Modifier.weight(1f).padding(start = 18.dp).heightIn(min = 48.dp),
                                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Box {
                                    TextButton(onClick = { menuFor = if (menuFor == sub.id) null else sub.id }, modifier = Modifier.semantics { contentDescription = "Ações da sublista ${sub.name}" }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⋮", color = MaterialTheme.colorScheme.onSurface) }
                                    DropdownMenu(expanded = menuFor == sub.id, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(text = { Text("Editar sublista") }, onClick = { menuFor = null; onEdit(sub) })
                                        DropdownMenuItem(text = { Text("Excluir sublista") }, onClick = { menuFor = null; onDelete(sub) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
            NavigationDrawerItem(
                label = { Text("Criar lista") }, selected = false, onClick = onNewList,
                icon = { Text("＋", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp) },
                colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurface)
            )
            NavigationDrawerItem(
                label = { Text("Configurações e backup") }, selected = false, onClick = onSettings,
                icon = { Text("⚙", color = MaterialTheme.colorScheme.primary) },
                colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurface)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WorkScreen(
    root: TaskList, selectedTab: TaskList, tabs: List<TaskList>, tasks: List<TaskItem>, dao: TaskDao,
    drawerOpen: () -> Unit, selectTab: (String) -> Unit, addTasks: (QuickCapture, (Boolean) -> Unit) -> Unit,
    toggleTask: (TaskItem) -> Unit, archiveTask: (TaskItem, Boolean) -> Unit,
    openTask: (TaskItem, Boolean) -> Unit, deleteTask: (TaskItem) -> Unit,
    addSublist: () -> Unit, moveTask: (TaskItem, Int) -> Unit
) {
    val accent = readableAccent(tintFor(root))
    val quickFocus = remember(selectedTab.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val viewPrefs = remember { context.getSharedPreferences("ritmo", 0) }
    var viewMode by remember { mutableStateOf(viewPrefs.getString("taskViewMode", "Lista") ?: "Lista") }
    var quickText by rememberSaveable(selectedTab.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var captureChoice by rememberSaveable(selectedTab.id) { mutableStateOf(false) }
    var makeSubtasks by rememberSaveable(selectedTab.id) { mutableStateOf(false) }
    var submitting by rememberSaveable(selectedTab.id) { mutableStateOf(false) }
    var captureError by rememberSaveable(selectedTab.id) { mutableStateOf(false) }
    var tagFilterOpen by rememberSaveable(selectedTab.id) { mutableStateOf(false) }
    var tagFilterCsv by rememberSaveable(selectedTab.id) { mutableStateOf("") }
    fun commitCapture(titles: List<String>, asSubtasks: Boolean = false) {
        if (submitting) return
        submitting = true
        addTasks(QuickCapture(titles, asSubtasks)) { success ->
            submitting = false
            captureError = !success
            if (success) {
                quickText = TextFieldValue("")
                captureChoice = false
                makeSubtasks = false
                quickFocus.requestFocus()
                keyboard?.show()
            }
        }
    }
    fun sendCapture() {
        val text = quickText.text
        if (text.isBlank() || submitting) return
        val nonEmptyLines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (text.contains('\n') && nonEmptyLines.size > 1) captureChoice = true
        else commitCapture(listOf(text.trim()))
    }
    var showArchived by remember(selectedTab.id) { mutableStateOf(false) }
    val allTags by dao.tags().collectAsStateWithLifecycle(initialValue = emptyList())
    val tagRefs by dao.taskTagRefs().collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedTagIds = tagFilterCsv.split(',').filter(String::isNotBlank).toSet()
    val tasksMatchingTags = remember(tagRefs, selectedTagIds) {
        tagRefs.asSequence().filter { it.tagId in selectedTagIds }.map { it.taskId }.toSet()
    }
    val visibleTasks = tasks.filter { task ->
        task.archived == showArchived && (selectedTagIds.isEmpty() || task.id in tasksMatchingTags)
    }
    val listState = rememberLazyListState()
    val visibleIds = visibleTasks.map { it.id }
    var previewOrder by remember(visibleIds) { mutableStateOf<List<String>?>(null) }
    var draggedId by remember(visibleIds) { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    val taskById = visibleTasks.associateBy { it.id }

    fun finishDrag(task: TaskItem, commit: Boolean) {
        if (draggedId != task.id) return
        draggedId = null
        val targetIndex = previewOrder?.indexOf(task.id) ?: return
        val originalIndex = visibleIds.indexOf(task.id)
        if (!commit || targetIndex == originalIndex) {
            previewOrder = null
            return
        }
        val targetId = visibleIds[targetIndex]
        val ordered = tasks.filter { it.archived == task.archived }
        val from = ordered.indexOfFirst { it.id == task.id }
        val to = ordered.indexOfFirst { it.id == targetId }
        moveTask(task, to - from)
    }
    val filterBaseTasks = tasks.filter { task -> !task.archived && (selectedTagIds.isEmpty() || task.id in tasksMatchingTags) }
    val archivedCount = tasks.count { it.archived && (selectedTagIds.isEmpty() || it.id in tasksMatchingTags) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = drawerOpen, modifier = Modifier.size(52.dp).semantics { contentDescription = "Abrir listas e configurações" }) { Text("☰", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface) }
                    Box(Modifier.padding(start = 5.dp).size(11.dp).clip(CircleShape).background(accent))
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(root.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(if (selectedTab.id == root.id) "Tarefas gerais" else selectedTab.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = addSublist, modifier = Modifier.size(RitmoTokens.minTouchTarget).semantics { contentDescription = "Criar sublista" }) { Text("＋", fontSize = 24.sp, color = accent) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        if (index > 0) Text("|", color = MaterialTheme.colorScheme.outline, fontSize = 15.sp)
                        val selected = tab.id == selectedTab.id
                        Text(
                            tabLabel(tab, root, tabs),
                            Modifier.clickable { selectTab(tab.id) }.semantics { role = Role.Tab; this.selected = selected }.padding(horizontal = 5.dp, vertical = 10.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (showArchived) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { showArchived = false }) { Text("Voltar às tarefas", color = accent) }
                    }
                }
            } else Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = RitmoTokens.spaceMd, vertical = RitmoTokens.spaceSm)) {
                    if (captureError) Text("Não foi possível salvar. O texto continua aqui; tente novamente.", Modifier.padding(start = 6.dp, bottom = 5.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = quickText,
                            onValueChange = { quickText = it; captureError = false },
                            modifier = Modifier.weight(1f).heightIn(max = 142.dp).focusRequester(quickFocus),
                            placeholder = { Text("Adicionar uma tarefa…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            minLines = 1, maxLines = 5, shape = RoundedCornerShape(RitmoTokens.cornerCard),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedBorderColor = accent, unfocusedBorderColor = MaterialTheme.colorScheme.outline)
                        )
                        Row(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(RitmoTokens.cornerControl)).background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
                            EmojiButton(quickText, { quickText = it }, { quickFocus.requestFocus(); keyboard?.show() })
                            VoiceButton(quickText, { quickText = it }, { quickFocus.requestFocus(); keyboard?.show() })
                        }
                        Spacer(Modifier.width(10.dp))
                        FilledIconButton(onClick = { sendCapture() }, enabled = !submitting, modifier = Modifier.size(52.dp).semantics { contentDescription = "Adicionar tarefa" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB6533D), contentColor = Color.White)) {
                            Text("＋", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showArchived) "$archivedCount arquivadas" else "${filterBaseTasks.count { !it.done }} em aberto · Concluídas ${filterBaseTasks.count { it.done }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f)
                    )
                    TooltipIconButton("Arquivadas", "archive", selected = showArchived, badge = if (archivedCount > 0) archivedCount.toString() else null, onClick = { showArchived = !showArchived })
                    Box {
                        TooltipIconButton("Filtrar por etiquetas", "tag", selected = selectedTagIds.isNotEmpty(), badge = selectedTagIds.size.takeIf { it > 0 }?.toString(), enabled = allTags.isNotEmpty(), onClick = { tagFilterOpen = true })
                        DropdownMenu(expanded = tagFilterOpen, onDismissRequest = { tagFilterOpen = false }) {
                            allTags.forEach { tag ->
                                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(tag.id in selectedTagIds, null, colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant))
                                    Text("${tag.emoji} ${tag.name}")
                                } }, onClick = {
                                    val next = if (tag.id in selectedTagIds) selectedTagIds - tag.id else selectedTagIds + tag.id
                                    tagFilterCsv = next.joinToString(",")
                                })
                            }
                            if (selectedTagIds.isNotEmpty()) DropdownMenuItem(text = { Text("Limpar filtro") }, onClick = { tagFilterCsv = ""; tagFilterOpen = false })
                        }
                    }
                    TooltipIconButton("Visualização em lista", "list", selected = viewMode == "Lista", onClick = { viewMode = "Lista"; viewPrefs.edit().putString("taskViewMode", "Lista").apply() })
                    TooltipIconButton("Visualização em cards", "cards", selected = viewMode == "Cards", onClick = { viewMode = "Cards"; viewPrefs.edit().putString("taskViewMode", "Cards").apply() })
                }
            }
            if (visibleTasks.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (selectedTagIds.isNotEmpty()) "Nenhuma tarefa com essas etiquetas" else if (showArchived) "Nenhuma tarefa arquivada" else "Tudo em dia por aqui", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(5.dp))
                        if (selectedTagIds.isNotEmpty()) TextButton(onClick = { tagFilterCsv = "" }) { Text("Limpar filtro") }
                        else Text("Escreva abaixo para guardar uma ideia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(start = if (viewMode == "Cards") RitmoTokens.spaceMd else 0.dp, end = if (viewMode == "Cards") RitmoTokens.spaceMd else 0.dp, top = RitmoTokens.spaceXs, bottom = RitmoTokens.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(if (viewMode == "Cards") 7.dp else 0.dp)
                ) {
                    items(previewOrder ?: visibleIds, key = { it }) { id ->
                        val task = taskById.getValue(id)
                        val dragging = draggedId == id
                        val subtasks by dao.childrenFlow(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
                        val tags by dao.tagsForTask(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
                        val attachments by dao.attachments(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
                        val rowModifier = if (dragging) {
                            Modifier.zIndex(1f).graphicsLayer {
                                val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset
                                translationY = if (offset == null) 0f else dragTop - offset
                            }
                        } else Modifier.animateItem()
                        TaskRow(task, subtasks, tags, attachments.size, accent, viewMode, rowModifier, dragging,
                            onDragStart = {
                                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let {
                                    dragTop = it.offset.toFloat()
                                    draggedId = id
                                    if (previewOrder == null) previewOrder = visibleIds
                                }
                            },
                            onDrag = { amount ->
                                if (draggedId == id) {
                                    dragTop += amount
                                    val layout = listState.layoutInfo.visibleItemsInfo
                                    val row = layout.firstOrNull { it.key == id }
                                    val order = previewOrder
                                    if (row != null && order != null) {
                                        val from = order.indexOf(id)
                                        val center = dragTop + row.size / 2f
                                        val candidates = layout.filter { item ->
                                            val index = order.indexOf(item.key as? String ?: return@filter false)
                                            val midpoint = item.offset + item.size / 2f
                                            index >= 0 && index != from &&
                                                ((amount > 0 && index > from && center >= midpoint) ||
                                                    (amount < 0 && index < from && center <= midpoint))
                                        }
                                        val target = if (amount > 0) candidates.maxByOrNull { it.index } else candidates.minByOrNull { it.index }
                                        if (target != null) {
                                            val to = order.indexOf(target.key as String)
                                            previewOrder = order.toMutableList().apply { add(to, removeAt(from)) }
                                        }
                                    }
                                }
                            },
                            onDragEnd = { commit -> finishDrag(task, commit) },
                            onToggle = { toggleTask(task) }, onToggleChild = { child -> toggleTask(child) },
                            onOpen = { openTask(task, false) }, onOpenNote = { openTask(task, true) }, onDelete = { deleteTask(task) },
                            onMove = { direction -> moveTask(task, direction) }, onArchive = { archiveTask(task, !task.archived) })
                    }
                }
            }
        }
    }
    if (captureChoice) AlertDialog(
        onDismissRequest = { captureChoice = false },
        title = { Text("Como criar as tarefas?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O texto tem ${quickText.text.lineSequence().count { it.isNotBlank() }} linhas preenchidas.")
                if (quickText.text.lineSequence().count { it.isNotBlank() } > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(makeSubtasks, { makeSubtasks = it })
                        Text("Usar linhas seguintes como subtarefas", modifier = Modifier.clickable { makeSubtasks = !makeSubtasks }, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = !submitting, onClick = {
                    val nonEmpty = quickText.text.lineSequence().filter { it.isNotBlank() }.toList()
                    if (makeSubtasks && nonEmpty.size > 1) commitCapture(nonEmpty, true) else commitCapture(listOf(quickText.text.trim()))
                }) { Text(if (makeSubtasks) "Criar tarefa com subtarefas" else "Criar 1 tarefa com quebras") }
                TextButton(enabled = !submitting, onClick = { commitCapture(quickText.text.lineSequence().filter { it.isNotBlank() }.toList()) }) {
                    Text("Criar ${quickText.text.lineSequence().count { it.isNotBlank() }} tarefas")
                }
                TextButton(onClick = { captureChoice = false }) { Text("Continuar editando") }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TooltipIconButton(
    label: String, glyph: String, selected: Boolean, badge: String? = null,
    enabled: Boolean = true, onClick: () -> Unit
) {
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(showHint) { if (showHint) { kotlinx.coroutines.delay(1300); showHint = false } }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(RitmoTokens.minTouchTarget).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { showHint = true })
                .semantics { role = Role.Button; contentDescription = label; this.selected = selected },
            contentAlignment = Alignment.Center
        ) {
            val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(Modifier.size(22.dp)) {
                fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
                val stroke = size.width * .08f
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                    drawLine(tint, point(x1, y1), point(x2, y2), stroke)
                fun outline(x: Float, y: Float, width: Float, height: Float) =
                    drawRect(tint, point(x, y), Size(width * size.width, height * size.height), style = Stroke(stroke))
                when (glyph) {
                    "archive" -> {
                        outline(.10f, .14f, .80f, .20f)
                        outline(.16f, .34f, .68f, .52f)
                        line(.38f, .51f, .62f, .51f)
                    }
                    "tag" -> {
                        val path = Path().apply {
                            moveTo(size.width * .12f, size.height * .14f)
                            lineTo(size.width * .48f, size.height * .14f)
                            lineTo(size.width * .89f, size.height * .55f)
                            lineTo(size.width * .53f, size.height * .91f)
                            lineTo(size.width * .12f, size.height * .50f)
                            close()
                        }
                        drawPath(path, tint, style = Stroke(stroke))
                        drawCircle(tint, stroke * .8f, point(.32f, .34f))
                    }
                    "list" -> for (y in listOf(.24f, .50f, .76f)) {
                        drawCircle(tint, stroke * .65f, point(.16f, y))
                        line(.32f, y, .88f, y)
                    }
                    "cards" -> for (x in listOf(.12f, .56f)) {
                        outline(x, .12f, .32f, .32f)
                        outline(x, .56f, .32f, .32f)
                    }
                }
            }
            if (badge != null) Surface(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp), color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                Text(badge, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showHint) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -40), onDismissRequest = { showHint = false }) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(8.dp), shadowElevation = 4.dp) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    task: TaskItem, children: List<TaskItem>, tags: List<TaskTag>, attachmentCount: Int,
    accent: Color, viewMode: String, modifier: Modifier, dragging: Boolean,
    onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Boolean) -> Unit,
    onToggle: () -> Unit, onToggleChild: (TaskItem) -> Unit, onOpen: () -> Unit,
    onOpenNote: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit,
    onArchive: () -> Unit
) {
    var menu by remember(task.id) { mutableStateOf(false) }
    var subtasksExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var noteExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var childNoteExpanded by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    val dragStart = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    val dragEnd = rememberUpdatedState(onDragEnd)
    val archive = rememberUpdatedState(onArchive)
    var swipeOffset by remember(task.id) { mutableFloatStateOf(0f) }
    val renderedSwipe by androidx.compose.animation.core.animateFloatAsState(swipeOffset, label = "archive swipe")
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgbInt()
    val backgroundArgb = taskSurfaceArgb(task.taskColor, surfaceArgb, task.done)
    val rowBg = Color(backgroundArgb)
    val renderedBg = if (dragging) lerp(rowBg, accent, .20f) else rowBg
    val renderedBgArgb = renderedBg.toArgbInt()
    val functionalAccent = Color(accentArgbOn(renderedBgArgb, accent.toArgbInt()))
    val foreground = Color(contentArgb(renderedBgArgb))
    val cards = viewMode == "Cards"
    val hasNote = task.notes.isNotBlank()
    val hasChildren = children.isNotEmpty()
    val hasFooter = task.dueAt != null || task.reminderAt != null || attachmentCount > 0 || task.priority > 0
    val hasDetails = hasNote || hasChildren || tags.isNotEmpty() || hasFooter
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Box(
        modifier.fillMaxWidth().clip(if (cards) RoundedCornerShape(RitmoTokens.cornerCard) else RoundedCornerShape(0.dp))
            .background(if (swipeOffset == 0f) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD9E8DF))
            .pointerInput(task.id, task.archived) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = size.width * .24f
                        if (kotlin.math.abs(swipeOffset) >= threshold) archive.value()
                        swipeOffset = 0f
                    },
                    onDragCancel = { swipeOffset = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipeOffset = (swipeOffset + amount).coerceIn(-size.width * .72f, size.width * .72f)
                    }
                )
            }
    ) {
        Row(Modifier.matchParentSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (swipeOffset < 0f) Arrangement.End else Arrangement.Start) {
            Text(if (task.archived) "↩ Restaurar" else "⇢ Arquivar", color = Color(0xFF254C38), fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxWidth().graphicsLayer { translationX = renderedSwipe }
                .then(if (cards) Modifier.clip(RoundedCornerShape(RitmoTokens.cornerCard)).background(renderedBg) else Modifier.background(renderedBg))
                .padding(horizontal = if (cards) 12.dp else 8.dp, vertical = if (cards) 10.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(if (cards) 3.dp else 0.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Checkbox(checked = task.done, onCheckedChange = { onToggle() },
                    modifier = Modifier.size(RitmoTokens.minTouchTarget).semantics { contentDescription = if (task.done) "Reabrir tarefa: ${task.title}" else "Concluir tarefa: ${task.title}" },
                    colors = CheckboxDefaults.colors(checkedColor = functionalAccent, checkmarkColor = Color(contentArgb(functionalAccent.toArgbInt())), uncheckedColor = foreground))
                Text(task.title, Modifier.weight(1f).clickable(onClickLabel = "Abrir ficha da tarefa: ${task.title}", onClick = onOpen).padding(vertical = 9.dp),
                    color = foreground, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None, fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(RitmoTokens.minTouchTarget).semantics {
                    contentDescription = "Reordenar ${task.title}"
                    customActions = listOf(CustomAccessibilityAction("Mover tarefa para cima") { onMove(-1); true }, CustomAccessibilityAction("Mover tarefa para baixo") { onMove(1); true })
                }.pointerInput(task.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragStart.value() },
                        onDragEnd = { dragEnd.value(true) },
                        onDragCancel = { dragEnd.value(false) }
                    ) { change, amount ->
                        change.consume()
                        drag.value(amount.y)
                    }
                }, contentAlignment = Alignment.Center) { Text("⠿", color = foreground, fontSize = 18.sp) }
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Mais ações para ${task.title}" }, contentPadding = PaddingValues(0.dp)) { Text("⋮", fontSize = 21.sp, color = foreground) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Abrir ficha") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Mover para cima") }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text("Mover para baixo") }, onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text(if (task.archived) "Restaurar tarefa" else "Arquivar tarefa") }, onClick = { menu = false; onArchive() })
                        DropdownMenuItem(text = { Text("Excluir tarefa") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (hasDetails) HorizontalDivider(thickness = .5.dp, color = foreground.copy(alpha = if (cards) .18f else .13f))
            if (tags.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = if (cards) 4.dp else 1.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { tag ->
                    val tagBg = compositeArgb(tag.color, backgroundArgb, .18f)
                    Surface(color = Color(tagBg), shape = RoundedCornerShape(50)) {
                        Text("${tag.emoji} ${tag.name}", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = Color(contentArgb(tagBg)), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (hasChildren) {
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Subtarefas ${children.count { it.done }}/${children.size}", Modifier.weight(1f)
                        .clickable(onClickLabel = if (subtasksExpanded) "Recolher subtarefas" else "Expandir subtarefas") {
                            subtasksExpanded = !subtasksExpanded
                        }.heightIn(min = RitmoTokens.minTouchTarget).wrapContentHeight(), color = functionalAccent, style = MaterialTheme.typography.labelLarge)
                    Text(if (subtasksExpanded) "⌄" else "›", color = functionalAccent, fontSize = 18.sp)
                }
                if (subtasksExpanded) children.forEach { child ->
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(child.done, { onToggleChild(child) }, modifier = Modifier.size(42.dp).semantics { contentDescription = if (child.done) "Reabrir subtarefa: ${child.title}" else "Concluir subtarefa: ${child.title}" }, colors = CheckboxDefaults.colors(checkedColor = functionalAccent, checkmarkColor = Color(contentArgb(functionalAccent.toArgbInt())), uncheckedColor = foreground))
                        Text(child.title, Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodyMedium, textDecoration = if (child.done) TextDecoration.LineThrough else TextDecoration.None)
                        if (child.notes.isNotBlank()) TextButton(onClick = { childNoteExpanded = if (childNoteExpanded == child.id) null else child.id }, contentPadding = PaddingValues(4.dp)) { Text("Nota", color = functionalAccent) }
                    }
                    if (child.notes.isNotBlank() && childNoteExpanded == child.id) Row(Modifier.fillMaxWidth().padding(start = 50.dp), verticalAlignment = Alignment.Top) {
                        Text(richText(child.notes), Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodyMedium)
                        CopyNoteButton(richText(child.notes), functionalAccent, "Copiar nota da subtarefa")
                    }
                }
            }
            if (hasNote) {
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).clickable(onClickLabel = "Editar nota", onClick = onOpenNote)
                        .heightIn(min = RitmoTokens.minTouchTarget).padding(vertical = 5.dp), verticalArrangement = Arrangement.Center) {
                        Text("Nota", color = functionalAccent, style = MaterialTheme.typography.labelLarge)
                        if (cards || noteExpanded) Text(richText(task.notes), Modifier.padding(top = 2.dp), color = foreground, style = MaterialTheme.typography.bodyMedium, maxLines = if (noteExpanded) Int.MAX_VALUE else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (noteExpanded) CopyNoteButton(richText(task.notes), functionalAccent)
                    Text(if (noteExpanded) "⌄" else "›", Modifier.size(RitmoTokens.minTouchTarget).clickable {
                        noteExpanded = !noteExpanded
                    }.semantics { contentDescription = if (noteExpanded) "Recolher prévia da nota" else "Expandir prévia da nota" }.wrapContentSize(Alignment.Center), color = functionalAccent, fontSize = 18.sp)
                }
            }
            if (hasFooter) Row(Modifier.fillMaxWidth().padding(top = if (cards) 5.dp else 1.dp), verticalAlignment = Alignment.Bottom) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (task.dueAt != null) MetaLabel("calendar", dateLabel(task.dueAt), "Prazo", foreground)
                    if (task.reminderAt != null) MetaLabel("bell", dateTimeLabel(task.reminderAt), "Lembrete", foreground)
                    if (attachmentCount > 0) MetaLabel("clip", attachmentCount.toString(), "Anexos", foreground)
                }
                if (task.priority > 0) PriorityFlag(task.priority, renderedBgArgb)
            }
        }
    }
    if (!cards) HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = .30f))
}

@Composable
private fun CopyNoteButton(note: androidx.compose.ui.text.AnnotatedString, tint: Color, label: String = "Copiar nota") {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    IconButton(onClick = {
        clipboard.setText(note)
        android.widget.Toast.makeText(context, "Nota copiada", android.widget.Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(w*.28f,h*.22f), size = androidx.compose.ui.geometry.Size(w*.58f,h*.62f), style = Stroke(width = 2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
            drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(w*.12f,h*.08f), size = androidx.compose.ui.geometry.Size(w*.58f,h*.62f), style = Stroke(width = 2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
        }
    }
}

@Composable
private fun FormatGlyph(action: String, tint: Color) {
    if (action == "•") {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension * .10f
            for (y in listOf(.24f, .50f, .76f)) {
                drawCircle(tint, r, androidx.compose.ui.geometry.Offset(size.width*.18f, size.height*y))
                drawLine(tint, androidx.compose.ui.geometry.Offset(size.width*.38f, size.height*y), androidx.compose.ui.geometry.Offset(size.width*.88f, size.height*y), size.minDimension*.09f)
            }
        }
    } else Text(action, color = tint, fontWeight = if (action == "B") FontWeight.Bold else FontWeight.Medium,
        fontStyle = if (action == "I") androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        textDecoration = if (action == "U") TextDecoration.Underline else TextDecoration.None)
}

@Composable
private fun MetaLabel(kind: String, text: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $text" }) {
        MetaGlyph(kind, color)
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetaGlyph(kind: String, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val sw = size.minDimension * .10f
        when (kind) {
            "calendar" -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width*.12f,size.height*.18f), size = androidx.compose.ui.geometry.Size(size.width*.76f,size.height*.72f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(sw))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.12f,size.height*.40f), androidx.compose.ui.geometry.Offset(size.width*.88f,size.height*.40f), sw)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.32f,size.height*.08f), androidx.compose.ui.geometry.Offset(size.width*.32f,size.height*.30f), sw)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.68f,size.height*.08f), androidx.compose.ui.geometry.Offset(size.width*.68f,size.height*.30f), sw)
                listOf(.34f,.61f).forEach { x -> listOf(.58f,.76f).forEach { y -> drawCircle(color, sw*.8f, androidx.compose.ui.geometry.Offset(size.width*x,size.height*y)) } }
            }
            "bell" -> {
                val p = Path().apply { moveTo(size.width*.22f,size.height*.70f); quadraticTo(size.width*.34f,size.height*.58f,size.width*.34f,size.height*.36f); quadraticTo(size.width*.36f,size.height*.12f,size.width*.50f,size.height*.12f); quadraticTo(size.width*.66f,size.height*.12f,size.width*.67f,size.height*.36f); quadraticTo(size.width*.67f,size.height*.59f,size.width*.79f,size.height*.70f); close() }
                drawPath(p,color,style=Stroke(sw)); drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.22f,size.height*.76f), androidx.compose.ui.geometry.Offset(size.width*.79f,size.height*.76f), sw)
                drawCircle(color, sw, androidx.compose.ui.geometry.Offset(size.width*.50f,size.height*.88f))
            }
            else -> {
                val p = Path().apply { moveTo(size.width*.67f,size.height*.17f); quadraticTo(size.width*.82f,size.height*.02f,size.width*.93f,size.height*.15f); quadraticTo(size.width*1.04f,size.height*.28f,size.width*.89f,size.height*.42f); lineTo(size.width*.43f,size.height*.88f); quadraticTo(size.width*.24f,size.height*1.02f,size.width*.11f,size.height*.88f); quadraticTo(size.width*-.01f,size.height*.75f,size.width*.14f,size.height*.60f); lineTo(size.width*.61f,size.height*.14f); quadraticTo(size.width*.70f,size.height*.06f,size.width*.78f,size.height*.15f); quadraticTo(size.width*.86f,size.height*.24f,size.width*.77f,size.height*.33f); lineTo(size.width*.34f,size.height*.76f) }
                drawPath(p,color,style=Stroke(sw))
            }
        }
    }
}

@Composable
private fun PriorityFlag(priority: Int, cardBackground: Int) {
    val label = listOf("", "Baixa", "Média", "Alta").getOrElse(priority) { "Prioridade" }
    val preferred = when (priority) { 1 -> Color(0xFF4F91C7); 2 -> Color(0xFFE9A43B); else -> Color(0xFFD9534F) }
    val color = Color(accentArgbOn(cardBackground, preferred.toArgbInt(), 3.0))
    val outline = Color(contentArgb(cardBackground, 3.0))
    androidx.compose.foundation.Canvas(Modifier.size(20.dp, 24.dp).semantics { contentDescription = "Prioridade $label" }) {
        val path = Path().apply {
            moveTo(size.width * .25f, size.height * .9f); lineTo(size.width * .25f, size.height * .1f)
            lineTo(size.width * .88f, size.height * .22f); lineTo(size.width * .62f, size.height * .48f)
            lineTo(size.width * .88f, size.height * .67f); lineTo(size.width * .25f, size.height * .55f); close()
        }
        drawPath(path, color)
        drawLine(outline, androidx.compose.ui.geometry.Offset(size.width*.25f,size.height*.1f), androidx.compose.ui.geometry.Offset(size.width*.25f,size.height*.94f), strokeWidth=2f)
    }
}

@Composable
private fun TaskDetailScreen(
    task: TaskItem, lists: List<TaskList>, dao: TaskDao, onClose: () -> Unit,
    onSave: (TaskItem, (Boolean) -> Unit) -> Unit, onToggleChild: (TaskItem) -> Unit, onAddChild: (String) -> Unit, onSaveChild: (TaskItem) -> Unit,
    focusNoteOnOpen: Boolean = false, modal: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val childTitleFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = lists.firstOrNull { it.id == task.listId }
    val root = rootList(task.listId, lists)
    var title by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(task.title)) }
    var notes by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(task.notes)) }
    var done by rememberSaveable(task.id) { mutableStateOf(task.done) }
    var dueAt by rememberSaveable(task.id) { mutableStateOf(task.dueAt) }
    var reminderAt by rememberSaveable(task.id) { mutableStateOf(task.reminderAt) }
    var repeat by rememberSaveable(task.id) { mutableStateOf(task.repeat ?: "Não repetir") }
    var priority by rememberSaveable(task.id) { mutableIntStateOf(task.priority.coerceIn(0, 3)) }
    var listId by rememberSaveable(task.id) { mutableStateOf(task.listId) }
    var taskColor by rememberSaveable(task.id) { mutableStateOf(task.taskColor) }
    var dirty by rememberSaveable(task.id) { mutableStateOf(false) }
    var urgent by rememberSaveable(task.id) { mutableStateOf(false) }
    var saveState by rememberSaveable(task.id) { mutableStateOf("Salvo") }
    var childExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var childNoteExpanded by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var childTitle by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var activeField by rememberSaveable(task.id) { mutableStateOf("") }
    val children by dao.childrenFlow(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val accent = readableAccent(tintFor(root))
    LaunchedEffect(task.id, focusNoteOnOpen) {
        if (focusNoteOnOpen) {
            kotlinx.coroutines.delay(120)
            runCatching { noteFocusRequester.requestFocus(); keyboard?.show() }
        }
    }
    val currentDraft = remember(task.id, title, notes, done, dueAt, reminderAt, repeat, priority, listId, taskColor) {
        task.copy(title = title.text.trim(), notes = notes.text, done = done, dueAt = dueAt,
            reminderAt = reminderAt, repeat = repeat.takeIf { it != "Não repetir" }, priority = priority, listId = listId, taskColor = taskColor)
    }
    val latestDraft = rememberUpdatedState(currentDraft)
    LaunchedEffect(task.id, currentDraft, dirty, urgent) {
        if (dirty && currentDraft.title.isNotBlank()) {
            saveState = "Salvando…"
            if (!urgent) kotlinx.coroutines.delay(550)
            onSave(currentDraft) { success -> if (latestDraft.value == currentDraft) { dirty = !success; saveState = if (success) "Salvo" else "Erro · tentar novamente" } }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, task.id, currentDraft, dirty) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && dirty && currentDraft.title.isNotBlank()) {
                saveState = "Salvando…"
                onSave(currentDraft) { success -> if (latestDraft.value == currentDraft) { dirty = !success; saveState = if (success) "Salvo" else "Erro · tentar novamente" } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler {
        if (dirty && currentDraft.title.isNotBlank()) onSave(currentDraft) { success -> if (latestDraft.value == currentDraft) { dirty = !success; saveState = if (success) "Salvo" else "Erro · tentar novamente" } }
        onClose()
    }
    val closeDetail = {
        if (dirty && currentDraft.title.isNotBlank()) onSave(currentDraft) { success -> if (latestDraft.value == currentDraft) { dirty = !success; saveState = if (success) "Salvo" else "Erro · tentar novamente" } }
        onClose()
    }

    val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    val editorInsets = if (modal) Modifier.windowInsetsPadding(bottomInsets)
        else Modifier.statusBarsPadding().windowInsetsPadding(bottomInsets)
    Column(editorInsets.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { paneTitle = "Ficha da tarefa" }) {
        Row(
            Modifier.fillMaxWidth().background(accent).padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = closeDetail, modifier = Modifier.size(RitmoTokens.minTouchTarget).semantics { contentDescription = "Voltar à lista" }) { Text("‹", fontSize = 32.sp, color = Color.White) }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("FICHA DA TAREFA", color = onAccent(accent).copy(alpha = .8f), fontSize = 11.sp, letterSpacing = 1.5.sp)
                Text(root?.name ?: "Ritmo", color = onAccent(accent), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (saveState.startsWith("Erro")) TextButton(
                onClick = { if (title.text.isNotBlank()) onSave(currentDraft) { ok -> if (latestDraft.value == currentDraft) { dirty = !ok; urgent = !ok; saveState = if (ok) "Salvo" else "Erro · tentar novamente" } } },
                modifier = Modifier.semantics { contentDescription = "Tentar salvar tarefa novamente" },
                contentPadding = PaddingValues(horizontal = RitmoTokens.spaceSm)
            ) { Text("Tentar salvar", color = onAccent(accent), style = MaterialTheme.typography.labelLarge) }
            else Text(saveState, Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Estado do salvamento: $saveState" }, color = onAccent(accent), style = MaterialTheme.typography.bodyMedium)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it; dirty = true; urgent = false; saveState = "Salvando…" }, modifier = Modifier.weight(1f).focusRequester(titleFocusRequester).onFocusChanged { if (it.isFocused) activeField = "title" },
                    label = { Text("Tarefa") }, textStyle = MaterialTheme.typography.titleLarge, minLines = 1, maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    colors = detailFieldColors(accent), shape = RoundedCornerShape(RitmoTokens.cornerControl)
                )
            }
            if (activeField == "title") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                EmojiButton(title, { title = it; dirty = true; urgent = false }, { titleFocusRequester.requestFocus() })
                VoiceButton(title, { title = it; dirty = true; urgent = false }, { titleFocusRequester.requestFocus(); keyboard?.show() })
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(RitmoTokens.cornerControl))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(RitmoTokens.cornerControl))
            ) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = continueNoteList(it); dirty = true; urgent = false; saveState = "Salvando…" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 106.dp).focusRequester(noteFocusRequester).onFocusChanged { if (it.isFocused) activeField = "notes" },
                    label = { Text("Notas") }, minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        cursorColor = accent
                    ), shape = RoundedCornerShape(RitmoTokens.cornerControl)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f), thickness = .5.dp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("B" to "Negrito", "I" to "Itálico", "U" to "Sublinhado", "•" to "Lista com marcadores").forEach { (action, label) ->
                        val active = noteToolActive(notes, action)
                        FilledTonalIconButton(onClick = { notes = applyNoteTool(notes, action); dirty = true; urgent = false; noteFocusRequester.requestFocus() }, modifier = Modifier.size(38.dp).semantics { contentDescription = label; this.selected = active }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (active) accent else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (active) onAccent(accent) else MaterialTheme.colorScheme.onSurfaceVariant)) {
                            FormatGlyph(action, if (active) onAccent(accent) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f, fill = false))
                    if (activeField == "notes") {
                        EmojiButton(notes, { notes = it; dirty = true; urgent = false }, { noteFocusRequester.requestFocus() })
                        VoiceButton(notes, { notes = it; dirty = true; urgent = false }, { noteFocusRequester.requestFocus(); keyboard?.show() })
                    }
                    if (notes.text.isNotBlank()) CopyNoteButton(richText(notes.text), accent)
                }
            }
            DetailSection("COR DA TAREFA", accent) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RitmoTokens.spaceXs), verticalAlignment = Alignment.CenterVertically) {
                    ColorOption(MaterialTheme.colorScheme.surfaceVariant, "Sem cor da tarefa", taskColor == null) { taskColor = null; dirty = true; urgent = true }
                    taskPalette.forEachIndexed { index, color -> ColorOption(Color(color), "Cor de tarefa ${index + 1}", taskColor == color) { taskColor = color; dirty = true; urgent = true } }
                }
            }
            val allTags by dao.tags().collectAsStateWithLifecycle(initialValue = emptyList())
            val linkedTagIds by dao.tagIds(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
            var newTagName by rememberSaveable(task.id) { mutableStateOf("") }
            var newTagEmoji by rememberSaveable(task.id) { mutableStateOf("🏷️") }
            var newTagColor by rememberSaveable(task.id) { mutableIntStateOf(taskPalette.first()) }
            var tagEdit by remember(task.id) { mutableStateOf<TaskTag?>(null) }
            DetailSection("ETIQUETAS", accent) {
                allTags.forEach { tag ->
                    key(tag.id) {
                        var tagMenu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(tag.id in linkedTagIds, { checked -> scope.launch {
                                if (dao.task(task.id) == null) dao.putTask(currentDraft)
                                if (checked) dao.putTaskTag(TaskTagRef(task.id, tag.id)) else dao.deleteTaskTag(task.id, tag.id)
                                Backup.markDirty(context)
                            } }, modifier = Modifier.semantics { contentDescription = if (tag.id in linkedTagIds) "Remover etiqueta ${tag.name}" else "Aplicar etiqueta ${tag.name}" }, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = onAccent(accent)))
                            val tagSurface = compositeArgb(tag.color, MaterialTheme.colorScheme.surface.toArgbInt(), .16f)
                            Surface(Modifier.weight(1f).clickable { scope.launch {
                                if (dao.task(task.id) == null) dao.putTask(currentDraft)
                                if (tag.id in linkedTagIds) dao.deleteTaskTag(task.id, tag.id) else dao.putTaskTag(TaskTagRef(task.id, tag.id))
                                Backup.markDirty(context)
                            } }, color = Color(tagSurface), shape = RoundedCornerShape(10.dp)) {
                                Text("${tag.emoji} ${tag.name}", Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = Color(contentArgb(tagSurface)))
                            }
                            Box {
                                TextButton(onClick = { tagMenu = true }) { Text("⋮") }
                                DropdownMenu(tagMenu, { tagMenu = false }) {
                                    DropdownMenuItem(text = { Text("Renomear etiqueta") }, onClick = { tagMenu = false; tagEdit = tag })
                                    DropdownMenuItem(text = { Text("Excluir etiqueta") }, onClick = { tagMenu = false; scope.launch { dao.deleteTag(tag.id); Backup.markDirty(context) } })
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiPickerButton(newTagEmoji) { newTagEmoji = it }
                    Spacer(Modifier.width(6.dp))
                        OutlinedTextField(newTagName, { newTagName = it }, Modifier.weight(1f), label = { Text("Nova etiqueta") }, singleLine = true, shape = RoundedCornerShape(RitmoTokens.cornerControl))
                    TextButton(enabled = newTagName.isNotBlank(), onClick = {
                        scope.launch {
                            val tag = TaskTag(UUID.randomUUID().toString(), newTagName.trim(), newTagEmoji, newTagColor)
                            if (dao.task(task.id) == null) dao.putTask(currentDraft)
                            dao.putTag(tag)
                            dao.putTaskTag(TaskTagRef(task.id, tag.id))
                            Backup.markDirty(context)
                            newTagName = ""
                        }
                    }) { Text("＋") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RitmoTokens.spaceXs)) {
                    taskPalette.forEachIndexed { index, swatch -> ColorOption(Color(swatch), "Cor de etiqueta ${index + 1}", newTagColor == swatch) { newTagColor = swatch } }
                }
            }
            tagEdit?.let { selected ->
                var editedName by rememberSaveable(selected.id) { mutableStateOf(selected.name) }
                var editedEmoji by rememberSaveable(selected.id) { mutableStateOf(selected.emoji) }
                var editedColor by rememberSaveable(selected.id) { mutableIntStateOf(selected.color) }
                AlertDialog(onDismissRequest = { tagEdit = null }, title = { Text("Editar etiqueta") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(RitmoTokens.spaceSm)) { Row(verticalAlignment = Alignment.CenterVertically) { EmojiPickerButton(editedEmoji) { editedEmoji = it }; Text("Símbolo", color = MaterialTheme.colorScheme.onSurfaceVariant) }; OutlinedTextField(editedName, { editedName = it }, label = { Text("Nome") }); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RitmoTokens.spaceXs)) { taskPalette.forEachIndexed { index, swatch -> ColorOption(Color(swatch), "Cor de etiqueta ${index + 1}", editedColor == swatch) { editedColor = swatch } } } } },
                    confirmButton = { TextButton(enabled = editedName.isNotBlank(), onClick = { scope.launch { dao.putTag(selected.copy(name = editedName.trim(), emoji = editedEmoji, color = editedColor)); dirty = true; urgent = true }; tagEdit = null }) { Text("Salvar") } },
                    dismissButton = { TextButton(onClick = { tagEdit = null }) { Text("Cancelar") } })
            }

            DetailSection("SUBTAREFAS", accent) {
                if (children.isEmpty()) Text("Divida esta tarefa em passos menores.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                else TextButton(onClick = { childExpanded = !childExpanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("${children.count { it.done }}/${children.size} subtarefas ${if (childExpanded) "▴" else "▾"}", color = accent)
                }
                children.forEach { child ->
                    key(child.id) {
                    var childNotes by rememberSaveable(child.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(child.notes)) }
                    val childNoteFocusRequester = remember(child.id) { FocusRequester() }
                    LaunchedEffect(child.id, childNotes) {
                        if (childNotes.text != child.notes) {
                            kotlinx.coroutines.delay(550)
                            onSaveChild(child.copy(notes = childNotes.text))
                        }
                    }
                    if (childExpanded) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(child.done, { onToggleChild(child) }, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = onAccent(accent)))
                        Text(
                            child.title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (child.done) TextDecoration.LineThrough else TextDecoration.None
                        )
                        TextButton(onClick = { childNoteExpanded = if (childNoteExpanded == child.id) null else child.id }, contentPadding = PaddingValues(4.dp)) { Text(if (childNoteExpanded == child.id) "Nota ▴" else "Nota ▾", color = accent, fontSize = 11.sp) }
                    }
                    if (childExpanded && childNoteExpanded == child.id) {
                        OutlinedTextField(childNotes, { childNotes = it }, Modifier.fillMaxWidth().padding(start = 42.dp).focusRequester(childNoteFocusRequester).onFocusChanged { if (it.isFocused) activeField = "childNote:${child.id}" }, label = { Text("Nota da subtarefa") }, minLines = 2, colors = detailFieldColors(accent))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("B" to "Negrito", "I" to "Itálico", "U" to "Sublinhado", "•" to "Lista com marcadores").forEach { (tool, label) ->
                                val active = noteToolActive(childNotes, tool)
                                FilledTonalIconButton(onClick = { childNotes = applyNoteTool(childNotes, tool) }, modifier = Modifier.size(40.dp).semantics { contentDescription = label; this.selected = active }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (active) accent else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (active) onAccent(accent) else MaterialTheme.colorScheme.onSurfaceVariant)) { FormatGlyph(tool, if (active) onAccent(accent) else MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (activeField == "childNote:${child.id}") {
                                EmojiButton(childNotes, { childNotes = it }, { childNoteFocusRequester.requestFocus() })
                                VoiceButton(childNotes, { childNotes = it }, { childNoteFocusRequester.requestFocus(); keyboard?.show() })
                            }
                        }
                    }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        childTitle, { childTitle = it }, Modifier.weight(1f).focusRequester(childTitleFocusRequester).onFocusChanged { if (it.isFocused) activeField = "childTitle" },
                        placeholder = { Text("Nova subtarefa") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (childTitle.text.isNotBlank()) { onAddChild(childTitle.text); childTitle = TextFieldValue("") } }),
                        colors = detailFieldColors(accent), shape = RoundedCornerShape(RitmoTokens.cornerControl)
                    )
                    if (activeField == "childTitle") {
                        EmojiButton(childTitle, { childTitle = it }, { childTitleFocusRequester.requestFocus() })
                        VoiceButton(childTitle, { childTitle = it }, { childTitleFocusRequester.requestFocus(); keyboard?.show() })
                    }
                    IconButton(onClick = { if (childTitle.text.isNotBlank()) { onAddChild(childTitle.text); childTitle = TextFieldValue("") } }, modifier = Modifier.size(RitmoTokens.minTouchTarget).semantics { contentDescription = "Adicionar subtarefa" }) {
                        Text("＋", color = accent, fontSize = 24.sp)
                    }
                }
            }

            DetailSection("LISTA", accent) {
                if (task.parentTaskId != null) {
                    Text("${list?.name ?: "Lista"} · vinculada à tarefa principal", color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Box {
                        OutlinedButton(onClick = { listMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Box(Modifier.size(11.dp).clip(CircleShape).background(tintFor(rootList(listId, lists))))
                            Spacer(Modifier.width(10.dp))
                            Text(lists.firstOrNull { it.id == listId }?.let { destinationLabel(it, lists) } ?: "Escolher lista", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            lists.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(destinationLabel(item, lists), color = MaterialTheme.colorScheme.onSurface)
                                    },
                            onClick = { listId = item.id; dirty = true; urgent = true; listMenu = false },
                                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(tintFor(rootList(item.id, lists)))) }
                                )
                            }
                        }
                    }
                    if (lists.none { it.id == listId }) Text("Esta lista foi excluída. Sua edição continua aqui; escolha outro destino para salvar.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }

            DetailSection("DATAS", accent) {
                DateField("Vencimento", dueAt, dateLabel(dueAt ?: 0L), accent, { pickDate(context, dueAt) { dueAt = it; dirty = true; urgent = true } }, { dueAt = null; dirty = true; urgent = true })
                DateField("Lembrete", reminderAt, dateTimeLabel(reminderAt ?: 0L), accent, { pickDateTime(context, reminderAt) { reminderAt = it; dirty = true; urgent = true } }, { reminderAt = null; repeat = "Não repetir"; dirty = true; urgent = true })
                if (reminderAt != null) Box {
                    OutlinedButton(onClick = { repeatMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text("Repetição", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(repeat, color = accent)
                    }
                    DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                        listOf("Não repetir", "Diário", "Semanal", "Mensal").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { repeat = option; dirty = true; urgent = true; repeatMenu = false })
                        }
                    }
                }
            }

            DetailSection("PRIORIDADE", accent) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Nenhuma", "Baixa", "Média", "Alta").forEachIndexed { value, label ->
                        val selected = priority == value
                        Surface(
                            onClick = { priority = value; dirty = true; urgent = true }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp),
                            color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) onAccent(accent) else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                if (value > 0) PriorityFlag(value, MaterialTheme.colorScheme.surface.toArgbInt())
                                Text(label, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
            }
            val attachments by dao.attachments(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
            val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                scope.launch { uris.forEach { uri -> runCatching { AttachmentStore.import(context, task.id, uri) }.onSuccess { dao.putAttachment(it) }.onFailure { saveState = "Erro ao anexar arquivo" } }; Backup.markDirty(context) }
            }
            DetailSection("ANEXOS", accent) {
                attachments.forEach { attachment ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (attachment.mimeType.startsWith("image/")) {
                            val preview by produceState<Bitmap?>(null, attachment.relativePath) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AttachmentStore.thumbnail(AttachmentStore.resolve(context, attachment.relativePath)) }
                            }
                            if (preview != null) Image(preview!!.asImageBitmap(), contentDescription = "Miniatura de ${attachment.fileName}", modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).padding(end = 8.dp))
                            else Text("▧", Modifier.padding(end = 8.dp), fontSize = 22.sp)
                        } else Text("📄", Modifier.padding(end = 8.dp), fontSize = 22.sp)
                        Column(Modifier.weight(1f).clickable { runCatching { AttachmentStore.open(context, attachment) }.onFailure { saveState = "Não há app para abrir este arquivo" } }) {
                            Text(attachment.fileName, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                            Text(attachment.mimeType, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        TextButton(onClick = { scope.launch { dao.deleteAttachment(attachment.id); AttachmentStore.resolve(context, attachment.relativePath).delete(); Backup.markDirty(context) } }) { Text("Remover") }
                    }
                }
                OutlinedButton(onClick = { attachmentPicker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Anexar arquivo ou imagem") }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable { done = !done; dirty = true; urgent = true }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(done, { done = it; dirty = true; urgent = true }, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = onAccent(accent)))
                Text("Concluída", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text(if (done) "Sim" else "Não", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun detailFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = accent,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedBorderColor = accent,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = accent
)

@Composable
private fun ColorOption(color: Color, label: String, isSelected: Boolean, onSelect: () -> Unit) {
    Box(
        Modifier.size(RitmoTokens.minTouchTarget)
            .semantics { contentDescription = label; role = Role.RadioButton; this.selected = isSelected }
            .clickable(onClickLabel = label, onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(color)
                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) Text("✓", color = onAccent(color), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetailSection(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(RitmoTokens.cornerCard)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = RitmoTokens.spaceLg, vertical = RitmoTokens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(RitmoTokens.spaceSm)
    ) {
        Text(title, color = accent, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun DateField(label: String, value: Long?, formatted: String, accent: Color, choose: () -> Unit, clear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(RitmoTokens.cornerControl)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = choose).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(if (value == null) "Adicionar data" else formatted, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        }
        if (value != null) TextButton(onClick = clear) { Text("Limpar", color = accent) }
        else Text("＋", color = accent, fontSize = 21.sp)
    }
}

private fun pickDate(context: android.content.Context, initial: Long?, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
    DatePickerDialog(context, { _, year, month, day ->
        cal.set(year, month, day, 12, 0, 0); cal.set(Calendar.MILLISECOND, 0); onPicked(cal.timeInMillis)
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

private fun pickDateTime(context: android.content.Context, initial: Long?, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
    DatePickerDialog(context, { _, year, month, day ->
        cal.set(year, month, day)
        TimePickerDialog(context, { _, hour, minute ->
            cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            onPicked(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
private fun ListEditorDialog(state: ListDialogState, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember(state) { mutableStateOf(state.editing?.name ?: "") }
    var color by remember(state) { mutableIntStateOf(state.editing?.color?.coerceIn(listColors.indices) ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when { state.editing != null -> "Editar lista"; state.parentId != null -> "Nova sublista"; else -> "Nova lista" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome") }, singleLine = true, colors = detailFieldColors(MaterialTheme.colorScheme.primary)
                )
                if (state.parentId == null) {
                    Text("Cor da lista", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listColors.forEachIndexed { index, colorValue -> ColorOption(colorValue, "Cor de lista ${index + 1}", color == index) { color = index } }
                    }
                    Surface(Modifier.fillMaxWidth(), color = listColors[color]) {
                        Text("Exemplo de tarefa   ◯   Organizar a semana", Modifier.padding(13.dp), color = onAccent(listColors[color]), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun SettingsScreen(
    themeMode: String, setThemeMode: (String) -> Unit, paletteIndex: Int, setPalette: (Int) -> Unit, chooseFolder: () -> Unit,
    backupNow: () -> Unit, restore: () -> Unit, backupPrefs: android.content.SharedPreferences, back: () -> Unit
) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(backupPrefs.getBoolean("enabled", false)) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface) }
            Text("Configurações", Modifier.padding(start = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            DetailSection("APARÊNCIA", MaterialTheme.colorScheme.primary) {
                Text("Tema", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Sistema", "Claro", "Escuro").forEach { mode ->
                        FilterChip(selected = themeMode == mode, onClick = { setThemeMode(mode) }, label = { Text(mode) })
                    }
                }
                Text("Paleta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RitmoTokens.spaceXs)) {
                    appPalettes.forEachIndexed { index, color ->
                        ColorOption(color, "Paleta do app ${index + 1}", paletteIndex == index) { setPalette(index) }
                    }
                }
            }
            DetailSection("BACKUP LOCAL", MaterialTheme.colorScheme.primary) {
                Text("Último backup: ${backupPrefs.getString("last", "Ainda não feito")}", color = MaterialTheme.colorScheme.onSurface)
                backupPrefs.getString("error", "")?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = chooseFolder, modifier = Modifier.fillMaxWidth()) { Text("Escolher pasta para backups") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ativar backup automático", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(auto, {
                        auto = it
                        backupPrefs.edit().putBoolean("enabled", it).apply()
                        if (it) {
                            Backup.scheduleDaily(context)
                            if (backupPrefs.getString("tree", null) == null) backupPrefs.edit().putString("error", "Escolha uma pasta para ativar o backup automático.").apply()
                        }
                    })
                }
                Button(onClick = backupNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Fazer backup agora") }
                OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restaurar backup") }
            }
            Text("Os dados ficam neste aparelho e na pasta que você escolher.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
