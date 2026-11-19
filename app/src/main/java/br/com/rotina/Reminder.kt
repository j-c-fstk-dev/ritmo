package br.com.rotina

import android.app.*
import android.content.*
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

object Reminders {
    fun schedule(c: Context, task: TaskItem) {
        cancel(c, task.id)
        val at = task.reminderAt ?: return
        if (task.done || task.archived) return
        val intent = Intent(c, ReminderReceiver::class.java).putExtra("id", task.id).putExtra("title", task.title).putExtra("repeat", task.repeat)
        val pi = PendingIntent.getBroadcast(c, task.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = c.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }
    fun cancel(c: Context, id: String) { val pi = PendingIntent.getBroadcast(c, id.hashCode(), Intent(c, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE); if (pi != null) c.getSystemService(AlarmManager::class.java).cancel(pi) }
    suspend fun rescheduleAll(c: Context) { AppDb.get(c).dao().allTasks().forEach { schedule(c, it) } }
}

class ReminderReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val manager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("tarefas", "Lembretes", NotificationManager.IMPORTANCE_DEFAULT))
        val title = intent.getStringExtra("title") ?: "Lembrete"
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "tarefas") else Notification.Builder(c)
        manager.notify(intent.getStringExtra("id").hashCode(), n.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Hora de cuidar disso").setContentText(title).setAutoCancel(true).build())
        val repeat = intent.getStringExtra("repeat") ?: return
        val id = intent.getStringExtra("id") ?: return
        val next = System.currentTimeMillis() + when (repeat) { "Diário" -> 86_400_000L; "Semanal" -> 604_800_000L; "Mensal" -> 2_592_000_000L; else -> return }
        runBlocking { val d = AppDb.get(c).dao(); val t = d.allTasks().firstOrNull { it.id == id } ?: return@runBlocking; d.putTask(t.copy(reminderAt = next)); Backup.markDirty(c); Reminders.schedule(c, t.copy(reminderAt = next)) }
    }
}
class BootReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) androidx.work.OneTimeWorkRequestBuilder<RestoreAlarmsWorker>().build().also { WorkManager.getInstance(context).enqueue(it) } } }
class RestoreAlarmsWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) { override suspend fun doWork(): Result = runCatching { Reminders.rescheduleAll(applicationContext); Result.success() }.getOrElse { Result.retry() } }
