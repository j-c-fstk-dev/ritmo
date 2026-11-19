package br.com.rotina

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lists") data class TaskList(@PrimaryKey val id: String, val name: String, val parentId: String? = null, val color: Int = 0)
@Entity(tableName = "tasks", indices = [Index("listId"), Index("parentTaskId")]) data class TaskItem(
    @PrimaryKey val id: String, val listId: String, val parentTaskId: String? = null, val title: String,
    val notes: String = "", val done: Boolean = false, val reminderAt: Long? = null, val repeat: String? = null,
    val order: Long = System.currentTimeMillis(), val dueAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val priority: Int = 0, val taskColor: Int? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false
)

@Entity(tableName = "tags")
data class TaskTag(@PrimaryKey val id: String, val name: String, val emoji: String, val color: Int)

@Entity(
    tableName = "task_tag_refs",
    primaryKeys = ["taskId", "tagId"],
    indices = [Index("taskId"), Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = TaskItem::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskTag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class TaskTagRef(val taskId: String, val tagId: String)

@Entity(
    tableName = "attachments",
    indices = [Index("taskId")],
    foreignKeys = [ForeignKey(entity = TaskItem::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)]
)
data class TaskAttachment(
    @PrimaryKey val id: String, val taskId: String, val fileName: String,
    val mimeType: String, val relativePath: String, val byteSize: Long, val sha256: String
)

data class ListLocation(val rootId: String, val tabId: String)

/** Resolves persisted navigation only from stable IDs; list names are deliberately irrelevant. */
fun resolveListLocation(lists: List<TaskList>, lastRootId: String?, lastTabId: String?): ListLocation? {
    val roots = lists.filter { it.parentId == null }
    if (roots.isEmpty()) return null
    val root = roots.firstOrNull { it.id == lastRootId } ?: roots.first()
    val tab = lists.firstOrNull { it.id == lastTabId && it.parentId == root.id }?.id ?: root.id
    return ListLocation(root.id, tab)
}

fun locationForList(lists: List<TaskList>, listId: String): ListLocation? {
    val item = lists.firstOrNull { it.id == listId } ?: return null
    val root = if (item.parentId == null) item else lists.firstOrNull { it.id == item.parentId } ?: return null
    return ListLocation(root.id, item.id)
}

@Dao interface TaskDao {
    @Query("SELECT * FROM lists ORDER BY rowid") fun lists(): Flow<List<TaskList>>
    @Query("SELECT * FROM lists ORDER BY rowid") suspend fun allLists(): List<TaskList>
    @Query("SELECT * FROM tasks ORDER BY `order`") suspend fun allTasks(): List<TaskItem>
    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1") fun taskFlow(id: String): Flow<TaskItem?>
    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1") suspend fun task(id: String): TaskItem?
    @Query("SELECT * FROM tasks WHERE listId = :listId AND parentTaskId IS NULL ORDER BY `order`") fun tasks(listId: String): Flow<List<TaskItem>>
    @Query("SELECT MAX(`order`) FROM tasks WHERE listId = :listId AND parentTaskId IS :parentTaskId") suspend fun maxOrder(listId: String, parentTaskId: String?): Long?
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY `order`") suspend fun children(parentId: String): List<TaskItem>
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY `order`") fun childrenFlow(parentId: String): Flow<List<TaskItem>>
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id") fun tags(): Flow<List<TaskTag>>
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id") suspend fun allTags(): List<TaskTag>
    @Query("SELECT tags.* FROM tags INNER JOIN task_tag_refs ON tags.id = task_tag_refs.tagId WHERE task_tag_refs.taskId = :taskId ORDER BY tags.name COLLATE NOCASE") fun tagsForTask(taskId: String): Flow<List<TaskTag>>
    @Query("SELECT * FROM task_tag_refs") suspend fun allTaskTagRefs(): List<TaskTagRef>
    @Query("SELECT * FROM task_tag_refs") fun taskTagRefs(): Flow<List<TaskTagRef>>
    @Query("SELECT tagId FROM task_tag_refs WHERE taskId = :taskId") fun tagIds(taskId: String): Flow<List<String>>
    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY fileName") fun attachments(taskId: String): Flow<List<TaskAttachment>>
    @Query("SELECT * FROM attachments") suspend fun allAttachments(): List<TaskAttachment>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putList(list: TaskList)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(task: TaskItem)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTag(tag: TaskTag)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putTaskTag(ref: TaskTagRef)
    @Query("DELETE FROM task_tag_refs WHERE taskId = :taskId AND tagId = :tagId") suspend fun deleteTaskTag(taskId: String, tagId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttachment(attachment: TaskAttachment)
    @Query("DELETE FROM task_tag_refs WHERE taskId = :taskId") suspend fun clearTaskTags(taskId: String)
    @Query("DELETE FROM task_tag_refs WHERE tagId = :tagId") suspend fun clearTagRefs(tagId: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTag(id: String)
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteAttachment(id: String)
    @Query("UPDATE tasks SET `order` = :order WHERE id = :id") suspend fun setTaskOrder(id: String, order: Long)
    @Query("DELETE FROM lists WHERE id = :id") suspend fun deleteList(id: String)
    @Query("DELETE FROM lists WHERE id = :id OR parentId = :id") suspend fun deleteListTree(id: String)
    @Query("DELETE FROM tasks WHERE listId IN (SELECT id FROM lists WHERE id = :id OR parentId = :id)") suspend fun deleteTasksInListTree(id: String)
    @Query("DELETE FROM tasks WHERE id = :id OR parentTaskId = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM task_tag_refs") suspend fun clearTaskTagRefs()
    @Query("DELETE FROM attachments") suspend fun clearAttachments()
    @Query("DELETE FROM tags") suspend fun clearTags()
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM lists") suspend fun clearLists()
}

@Database(entities = [TaskList::class, TaskItem::class, TaskTag::class, TaskTagRef::class, TaskAttachment::class], version = 4, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TaskDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dueAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN taskColor INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, emoji TEXT NOT NULL, color INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS task_tag_refs (taskId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(taskId, tagId), FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_tag_refs_taskId ON task_tag_refs(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_tag_refs_tagId ON task_tag_refs(tagId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, fileName TEXT NOT NULL, mimeType TEXT NOT NULL, relativePath TEXT NOT NULL, byteSize INTEGER NOT NULL, sha256 TEXT NOT NULL, FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_taskId ON attachments(taskId)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: AppDb? = null
        fun get(c: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c, AppDb::class.java, "ritmo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
