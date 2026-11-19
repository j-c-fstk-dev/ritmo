package br.com.rotina

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Model
import org.vosk.Recognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** On-device pt-BR dictation. The acoustic model is downloaded once and retained in app storage. */
object OfflineDictation {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
    private const val MODEL_DIR = "vosk-model-small-pt-0.3"
    private const val MAX_MODEL_BYTES = 180L * 1024L * 1024L

    private val sessionLock = Mutex()

    suspend fun run(
        context: Context,
        stop: AtomicBoolean,
        onStatus: (String) -> Unit,
        onText: (String, Boolean) -> Unit
    ) = sessionLock.withLock {
        withContext(Dispatchers.IO) {
            suspend fun status(message: String) = withContext(Dispatchers.Main) { onStatus(message) }
            suspend fun text(value: String, final: Boolean) = withContext(Dispatchers.Main) { onText(value, final) }

            val modelDir = ensureModel(context, stop, ::status) ?: return@withContext
            if (stop.get()) return@withContext
            val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Este aparelho não disponibilizou o microfone." }
            Model(modelDir.absolutePath).use { model ->
                Recognizer(model, 16_000.0f).use { recognizer ->
                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        (minBuffer * 2).coerceAtLeast(4096)
                    )
                    try {
                        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Não foi possível iniciar o microfone." }
                        if (stop.get()) return@withContext
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Não foi possível iniciar o microfone." }
                        status("Ouvindo…")
                        val buffer = ByteArray(4096)
                        var previousPartial = ""
                        while (!stop.get()) {
                            currentCoroutineContext().ensureActive()
                            val count = recorder.read(buffer, 0, buffer.size)
                            check(count >= 0) { "Não foi possível ler o áudio do microfone." }
                            if (count == 0) continue
                            if (recognizer.acceptWaveForm(buffer, count)) {
                                val spoken = JSONObject(recognizer.result).optString("text").trim()
                                if (spoken.isNotEmpty()) text(spoken, true)
                                else if (previousPartial.isNotEmpty()) text("", false)
                                previousPartial = ""
                            } else {
                                val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                                if (partial != previousPartial) text(partial, false)
                                previousPartial = partial
                            }
                        }
                        val last = JSONObject(recognizer.finalResult).optString("text").trim()
                        if (last.isNotEmpty()) text(last, true)
                        else if (previousPartial.isNotEmpty()) text("", false)
                        status("Ditado pronto para revisar")
                    } finally {
                        runCatching { recorder.stop() }
                        recorder.release()
                    }
                }
            }
        }
    }

    private suspend fun ensureModel(context: Context, stop: AtomicBoolean, status: suspend (String) -> Unit): File? {
        val modelsRoot = File(context.filesDir, "voice-models").apply { mkdirs() }
        val installed = File(modelsRoot, MODEL_DIR)
        if (File(installed, "final.mdl").isFile) return installed

        status("Baixando modelo de voz em português (cerca de 31 MB)…")
        val archive = File(context.cacheDir, "vosk-pt-model.zip.part")
        val temp = File(modelsRoot, "$MODEL_DIR.installing").apply { deleteRecursively(); mkdirs() }
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Não foi possível baixar o modelo de voz (HTTP ${connection.responseCode})." }
            val expected = connection.contentLengthLong
            var received = 0L
            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                FileOutputStream(archive).buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (stop.get()) return null
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= 80L * 1024L * 1024L) { "O download do modelo excedeu o tamanho esperado." }
                        output.write(buffer, 0, count)
                        if (expected > 0 && received % (512 * 1024) < count) status("Baixando modelo de voz… ${(received * 100 / expected).coerceIn(0, 100)}%")
                    }
                }
            }
            check(received > 0) { "O arquivo do modelo de voz veio vazio." }
            var extracted = 0L
            ZipInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (stop.get()) return null
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val relative = if (entry.isDirectory) name.removeSuffix("/") else name
                    check(!relative.startsWith('/') && relative.split('/').none { it == ".." || it.isBlank() }) { "O arquivo do modelo contém um caminho inválido." }
                    val parts = relative.split('/')
                    val insideModel = if (parts.firstOrNull() == MODEL_DIR) parts.drop(1).joinToString("/") else relative
                    if (insideModel.isNotEmpty()) {
                        val outputFile = File(temp, insideModel).canonicalFile
                        check(outputFile.toPath().startsWith(temp.canonicalFile.toPath())) { "O arquivo do modelo contém um caminho inválido." }
                        if (entry.isDirectory) outputFile.mkdirs() else {
                            outputFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (stop.get()) return null
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extracted += count
                                    check(extracted <= MAX_MODEL_BYTES) { "O modelo extraído é maior do que o esperado." }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(File(temp, "final.mdl").isFile) { "O modelo de português não passou na validação." }
            installed.deleteRecursively()
            check(temp.renameTo(installed)) { "Não foi possível instalar o modelo de voz." }
            return installed
        } finally {
            connection.disconnect()
            archive.delete()
            temp.deleteRecursively()
        }
    }
}
