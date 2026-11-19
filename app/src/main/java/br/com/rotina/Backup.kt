package br.com.rotina

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupAttachment(val id: String, val taskId: String, val fileName: String, val mimeType: String, val byteSize: Long, val sha256: String, val archivePath: String)
data class BackupSnapshot(val lists: List<TaskList>, val tasks: List<TaskItem>, val tags: List<TaskTag>, val tagRefs: List<TaskTagRef>, val attachments: List<BackupAttachment>)
data class BackupSummary(val lists: Int, val tasks: Int, val attachments: Int, val version: Int)
private data class DatabaseBackupState(val lists: List<TaskList>, val tasks: List<TaskItem>, val tags: List<TaskTag>, val refs: List<TaskTagRef>, val attachments: List<TaskAttachment>)

object Backup {
    const val PREF = "backup"
    private const val FORMAT = "ritmo-backup"
    private const val VERSION = 4
    private const val MANIFEST = "manifest.json"
    private const val BUFFER_SIZE = 64 * 1024

    fun markDirty(context: Context) {
        val prefs = context.getSharedPreferences(PREF, 0)
        prefs.edit().putBoolean("dirty", true).putLong("revision", prefs.getLong("revision", 0L) + 1L).apply()
    }

    fun folderDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Kept for compatibility tests/tools; exported backups use the streaming ZIP v3 format. */
    fun json(lists: List<TaskList>, tasks: List<TaskItem>) = JSONObject().put("format", FORMAT).put("version", 2).put("createdAt", System.currentTimeMillis())
        .put("lists", JSONArray().apply { lists.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("parentId",it.parentId).put("color",it.color)) } })
        .put("tasks", JSONArray().apply { tasks.forEach { put(taskJson(it)) } })

    fun parse(s: String): Pair<List<TaskList>, List<TaskItem>> {
        val parsed = parseLegacy(s)
        return parsed.lists to parsed.tasks
    }

    private fun taskJson(t: TaskItem) = JSONObject().put("id",t.id).put("listId",t.listId).put("parentTaskId",t.parentTaskId)
        .put("title",t.title).put("notes",t.notes).put("done",t.done).put("reminderAt",t.reminderAt)
        .put("repeat",t.repeat).put("order",t.order).put("dueAt",t.dueAt).put("priority",t.priority)

    private fun parseLists(array: JSONArray) = (0 until array.length()).map { i ->
        val x = array.getJSONObject(i)
        TaskList(x.getString("id"), x.getString("name"), x.optString("parentId").takeUnless { it.isBlank() || it == "null" }, x.optInt("color"))
    }

    private fun parseTasks(array: JSONArray, version: Int) = (0 until array.length()).map { i ->
        val x = array.getJSONObject(i)
        TaskItem(
            id = x.getString("id"), listId = x.getString("listId"),
            parentTaskId = x.optString("parentTaskId").takeUnless { it.isBlank() || it == "null" },
            title = x.getString("title"), notes = x.optString("notes"), done = x.optBoolean("done"),
            reminderAt = if (x.isNull("reminderAt")) null else x.getLong("reminderAt"),
            repeat = x.optString("repeat").takeUnless { it.isBlank() || it == "null" }, order = x.optLong("order"),
            dueAt = if (version >= 2 && !x.isNull("dueAt")) x.getLong("dueAt") else null,
            priority = if (version >= 2) x.optInt("priority", 0) else 0,
            taskColor = if (version >= 3 && !x.isNull("taskColor")) x.getInt("taskColor") else null,
            archived = version >= 4 && x.optBoolean("archived", false)
        )
    }

    private fun parseLegacy(text: String): BackupSnapshot {
        val o = JSONObject(text)
        val version = o.getInt("version")
        require(o.getString("format") == FORMAT && version in 1..2) { "Formato de backup JSON não reconhecido." }
        val lists = parseLists(o.getJSONArray("lists"))
        val tasks = parseTasks(o.getJSONArray("tasks"), version)
        val snapshot = BackupSnapshot(lists, tasks, emptyList(), emptyList(), emptyList())
        validateSnapshot(snapshot)
        return snapshot
    }

    private fun manifest(snapshot: BackupSnapshot): JSONObject = JSONObject()
        .put("format", FORMAT).put("version", VERSION).put("createdAt", System.currentTimeMillis())
        .put("lists", JSONArray().apply { snapshot.lists.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("parentId",it.parentId).put("color",it.color)) } })
        .put("tasks", JSONArray().apply { snapshot.tasks.forEach { put(taskJson(it).put("taskColor",it.taskColor).put("archived",it.archived)) } })
        .put("tags", JSONArray().apply { snapshot.tags.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("emoji",it.emoji).put("color",it.color)) } })
        .put("taskTags", JSONArray().apply { snapshot.tagRefs.forEach { put(JSONObject().put("taskId",it.taskId).put("tagId",it.tagId)) } })
        .put("attachments", JSONArray().apply { snapshot.attachments.forEach { put(JSONObject().put("id",it.id).put("taskId",it.taskId).put("fileName",it.fileName).put("mimeType",it.mimeType).put("byteSize",it.byteSize).put("sha256",it.sha256).put("archivePath",it.archivePath)) } })

    private fun parseManifest(text: String): BackupSnapshot {
        val o = JSONObject(text)
        val version = o.getInt("version")
        require(o.getString("format") == FORMAT && version in 3..VERSION) { "Versão ZIP do backup não reconhecida." }
        val tagsArray = o.getJSONArray("tags")
        val tags = (0 until tagsArray.length()).map { i -> tagsArray.getJSONObject(i).let { TaskTag(it.getString("id"), it.getString("name"), it.optString("emoji"), it.optInt("color")) } }
        val refsArray = o.getJSONArray("taskTags")
        val refs = (0 until refsArray.length()).map { i -> refsArray.getJSONObject(i).let { TaskTagRef(it.getString("taskId"), it.getString("tagId")) } }
        val attachmentArray = o.getJSONArray("attachments")
        val attachments = (0 until attachmentArray.length()).map { i -> attachmentArray.getJSONObject(i).let {
            BackupAttachment(it.getString("id"), it.getString("taskId"), it.getString("fileName"), it.getString("mimeType"), it.getLong("byteSize"), it.getString("sha256"), it.getString("archivePath"))
        } }
        val snapshot = BackupSnapshot(parseLists(o.getJSONArray("lists")), parseTasks(o.getJSONArray("tasks"), version), tags, refs, attachments)
        validateSnapshot(snapshot)
        require(attachments.map { it.archivePath }.toSet().size == attachments.size) { "O backup reutiliza caminhos de anexos." }
        require(attachments.all { safeArchivePath(it.archivePath) && it.archivePath == "attachments/${it.id}" && it.sha256.matches(Regex("[0-9a-fA-F]{64}")) && it.byteSize >= 0 }) { "Metadados de anexo inválidos." }
        return snapshot
    }

    private fun validateSnapshot(s: BackupSnapshot) {
        require(s.lists.map { it.id }.toSet().size == s.lists.size && s.tasks.map { it.id }.toSet().size == s.tasks.size) { "O arquivo contém IDs duplicados." }
        require(s.tags.map { it.id }.toSet().size == s.tags.size) { "O arquivo contém IDs de etiquetas duplicados." }
        require(s.lists.all { it.parentId == null || (it.parentId != it.id && s.lists.any { parent -> parent.id == it.parentId }) }) { "Há sublistas sem lista principal." }
        require(s.tasks.all { t -> s.lists.any { it.id == t.listId } && (t.parentTaskId == null || (t.parentTaskId != t.id && s.tasks.any { p -> p.id == t.parentTaskId && p.listId == t.listId && p.parentTaskId == null })) }) { "O arquivo contém vínculos de tarefas inválidos." }
        require(s.tagRefs.map { it.taskId to it.tagId }.toSet().size == s.tagRefs.size && s.tagRefs.all { ref -> s.tasks.any { it.id == ref.taskId } && s.tags.any { it.id == ref.tagId } }) { "O arquivo contém vínculos de etiquetas inválidos." }
        require(s.attachments.map { it.id }.toSet().size == s.attachments.size && s.attachments.all { a -> s.tasks.any { it.id == a.taskId } }) { "O arquivo contém vínculos de anexos inválidos." }
    }

    private fun safeArchivePath(path: String): Boolean = !path.startsWith('/') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    suspend fun summary(c: Context, uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val temp = copyUriToTemp(c, uri)
        try {
            if (isZip(temp)) validateArchiveData(temp).summary else {
                val snapshot = parseLegacy(temp.readText(Charsets.UTF_8))
                BackupSummary(snapshot.lists.size, snapshot.tasks.size, 0, JSONObject(temp.readText(Charsets.UTF_8)).getInt("version"))
            }
        } finally { temp.delete() }
    }

    suspend fun save(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val prefs = c.getSharedPreferences(PREF, 0)
        val revisionAtStart = prefs.getLong("revision", 0L)
        val dao = AppDb.get(c).dao()
        val state = AppDb.get(c).withTransaction {
            DatabaseBackupState(dao.allLists(), dao.allTasks(), dao.allTags(), dao.allTaskTagRefs(), dao.allAttachments())
        }
        val lists = state.lists
        val tasks = state.tasks
        val tags = state.tags
        val refs = state.refs
        val attachments = state.attachments
        val snapshot = BackupSnapshot(lists, tasks, tags, refs, attachments.map { a ->
            BackupAttachment(a.id, a.taskId, a.fileName, a.mimeType, a.byteSize, a.sha256, "attachments/${a.id}")
        })
        validateSnapshot(snapshot)
        val output = c.contentResolver.openOutputStream(uri, "wt") ?: error("Não foi possível abrir o arquivo de backup.")
        ZipOutputStream(BufferedOutputStream(output, BUFFER_SIZE)).use { zip ->
            zip.setLevel(1)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(snapshot).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            attachments.forEach { attachment ->
                val file = attachmentFile(c, attachment.relativePath)
                require(file.isFile && file.length() == attachment.byteSize) { "Anexo ausente ou alterado: ${attachment.fileName}" }
                zip.putNextEntry(ZipEntry("attachments/${attachment.id}"))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                }
                require(size == attachment.byteSize && digest.digest().toHex() == attachment.sha256.lowercase(Locale.ROOT)) { "Anexo alterado: ${attachment.fileName}" }
                zip.closeEntry()
            }
        }
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
        val update = prefs.edit().putString("last", stamp).putString("error", "")
        if (prefs.getLong("revision", 0L) == revisionAtStart) update.putBoolean("dirty", false)
        update.apply()
        stamp
    }

    suspend fun restore(c: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val temp = copyUriToTemp(c, uri)
        val staged = File(c.cacheDir, "restore-${UUID.randomUUID()}")
        staged.mkdirs()
        val installedFiles = mutableListOf<File>()
        try {
            val snapshot = if (isZip(temp)) {
                val checked = validateArchiveData(temp)
                extractValidated(temp, checked.snapshot, staged)
            } else parseLegacy(temp.readText(Charsets.UTF_8))

            val dao = AppDb.get(c).dao()
            val previousAttachments = dao.allAttachments()
            val attachmentRows = if (snapshot.attachments.isEmpty()) emptyList() else {
                val dir = File(c.filesDir, "attachments").apply { mkdirs() }
                snapshot.attachments.map { a ->
                    val stagedFile = File(staged, a.id)
                    val destName = "${UUID.randomUUID()}-${safeFileName(a.fileName)}"
                    val dest = File(dir, destName)
                    stagedFile.copyTo(dest, overwrite = false)
                    installedFiles += dest
                    TaskAttachment(a.id, a.taskId, a.fileName, a.mimeType, "attachments/$destName", a.byteSize, a.sha256)
                }
            }
            val db = AppDb.get(c)
            db.withTransaction {
                dao.clearAttachments(); dao.clearTaskTagRefs(); dao.clearTags(); dao.clearTasks(); dao.clearLists()
                snapshot.lists.forEach { dao.putList(it) }
                snapshot.tasks.forEach { dao.putTask(it) }
                snapshot.tags.forEach { dao.putTag(it) }
                snapshot.tagRefs.forEach { dao.putTaskTag(it) }
                attachmentRows.forEach { dao.putAttachment(it) }
            }
            previousAttachments.forEach { attachmentFile(c, it.relativePath).delete() }
            Reminders.rescheduleAll(c)
            snapshot.lists.size to snapshot.tasks.size
        } catch (error: Exception) {
            installedFiles.forEach { it.delete() }
            throw error
        } finally {
            temp.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    private data class ValidatedArchive(val snapshot: BackupSnapshot, val summary: BackupSummary)

    private fun validateArchiveData(file: File): ValidatedArchive {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            require(entries.map { it.name }.toSet().size == entries.size) { "O arquivo ZIP contém nomes repetidos." }
            val manifestEntry = zip.getEntry(MANIFEST) ?: error("Manifesto não encontrado no backup.")
            val snapshot = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { parseManifest(it.readText()) }
            val expected = setOf(MANIFEST) + snapshot.attachments.map { it.archivePath }.toSet()
            require(entries.map { it.name }.toSet() == expected) { "O backup tem anexos ausentes ou arquivos não reconhecidos." }
            snapshot.attachments.forEach { attachment ->
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(zip.getEntry(attachment.archivePath)).buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        digest.update(buffer, 0, count)
                    }
                }
                require(size == attachment.byteSize && digest.digest().toHex() == attachment.sha256.lowercase(Locale.ROOT)) { "Anexo corrompido: ${attachment.fileName}" }
            }
            return ValidatedArchive(snapshot, BackupSummary(snapshot.lists.size, snapshot.tasks.size, snapshot.attachments.size, snapshotVersion(file)))
        }
    }

    private fun snapshotVersion(file: File): Int = ZipFile(file).use { zip ->
        val entry = zip.getEntry(MANIFEST) ?: return@use VERSION
        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()).optInt("version", VERSION) }
    }

    private fun extractValidated(file: File, snapshot: BackupSnapshot, targetDir: File): BackupSnapshot {
        ZipFile(file).use { zip ->
            snapshot.attachments.forEach { attachment ->
                val out = File(targetDir, attachment.id)
                zip.getInputStream(zip.getEntry(attachment.archivePath)).buffered(BUFFER_SIZE).use { input ->
                    FileOutputStream(out).buffered(BUFFER_SIZE).use { output -> input.copyTo(output, BUFFER_SIZE) }
                }
                require(out.length() == attachment.byteSize) { "Falha ao extrair ${attachment.fileName}." }
            }
        }
        return snapshot
    }

    private fun copyUriToTemp(c: Context, uri: Uri): File {
        val tmp = File(c.cacheDir, "backup-${UUID.randomUUID()}.tmp")
        c.contentResolver.openInputStream(uri)?.buffered(BUFFER_SIZE)?.use { input ->
            FileOutputStream(tmp).buffered(BUFFER_SIZE).use { input.copyTo(it, BUFFER_SIZE) }
        } ?: error("Não foi possível abrir o arquivo selecionado.")
        require(tmp.length() > 0) { "O arquivo de backup está vazio." }
        return tmp
    }

    private fun isZip(file: File): Boolean = FileInputStream(file).use { input -> input.read() == 0x50 && input.read() == 0x4b }

    private fun attachmentFile(c: Context, relativePath: String): File {
        require(safeArchivePath(relativePath) && relativePath.startsWith("attachments/")) { "Caminho de anexo inválido." }
        val root = File(c.filesDir, "attachments").canonicalFile
        val file = File(c.filesDir, relativePath).canonicalFile
        require(file.parentFile == root) { "Caminho de anexo fora da pasta local." }
        return file
    }

    private fun safeFileName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "arquivo" }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    fun scheduleDaily(c: Context) {
        val req = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("backup-diario", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class AutomaticBackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(Backup.PREF, 0)
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean("dirty", false)) return Result.success()
        val raw = prefs.getString("tree", null) ?: return fail(prefs, "Escolha novamente a pasta de backup.")
        return try {
            val tree = Uri.parse(raw)
            val doc = DocumentsContract.createDocument(applicationContext.contentResolver, Backup.folderDocument(tree), "application/zip", "ritmo-${System.currentTimeMillis()}.ritmo")
                ?: error("Não foi possível criar o arquivo.")
            Backup.save(applicationContext, doc)
            Result.success()
        } catch (error: Exception) { fail(prefs, error.message ?: "Falha ao salvar backup.") }
    }
    private fun fail(prefs: android.content.SharedPreferences, reason: String): Result {
        prefs.edit().putString("error", reason).apply()
        return Result.retry()
    }
}
