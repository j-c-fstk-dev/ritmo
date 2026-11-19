package br.com.rotina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListNavigationTest {
    @Test
    fun sameNamesRemainDistinctAndLastListIsResolvedById() {
        val first = TaskList("root-a", "Pessoal", color = 0)
        val second = TaskList("root-b", "Pessoal", color = 2)
        val sub = TaskList("sub-b", "Semana", parentId = second.id)
        val idsBeforeNavigation = listOf(first, second, sub).map { it.id }

        assertEquals(ListLocation(second.id, sub.id), resolveListLocation(listOf(first, second, sub), second.id, sub.id))
        assertEquals(ListLocation(first.id, first.id), locationForList(listOf(first, second, sub), first.id))
        assertEquals(ListLocation(second.id, sub.id), locationForList(listOf(first, second, sub), sub.id))
        assertEquals(idsBeforeNavigation, listOf(first, second, sub).map { it.id })
    }

    @Test
    fun missingRememberedSublistFallsBackToItsRoot() {
        val root = TaskList("root", "Pessoal")
        val otherRoot = TaskList("other", "Pessoal")
        assertEquals(ListLocation(root.id, root.id), resolveListLocation(listOf(root, otherRoot), root.id, "deleted-sublist"))
        assertNull(resolveListLocation(emptyList(), "old-id", "old-tab"))
    }

    @Test
    fun versionOneBackupKeepsIdsAndDefaultsNewFields() {
        val legacy = """
            {"format":"ritmo-backup","version":1,
             "lists":[{"id":"pessoal-a","name":"Pessoal","parentId":null,"color":0},
                      {"id":"pessoal-b","name":"Pessoal","parentId":null,"color":1}],
             "tasks":[{"id":"task-1","listId":"pessoal-b","parentTaskId":null,"title":"Manter esta tarefa","notes":"nota","done":false,"reminderAt":null,"repeat":null,"order":17}]}
        """.trimIndent()

        val (lists, tasks) = Backup.parse(legacy)
        assertEquals(listOf("pessoal-a", "pessoal-b"), lists.map { it.id })
        assertEquals(2, lists.size)
        assertEquals("pessoal-b", tasks.single().listId)
        assertNull(tasks.single().dueAt)
        assertEquals(0, tasks.single().priority)
    }

    @Test
    fun versionTwoBackupRoundTripsDueDateAndPriority() {
        val list = TaskList("list-id", "Planejamento", color = 1)
        val task = TaskItem("task-id", list.id, title = "Separar datas", dueAt = 1_800_000_000_000, priority = 3)
        val (lists, tasks) = Backup.parse(Backup.json(listOf(list), listOf(task)).toString())

        assertEquals(list.id, lists.single().id)
        assertEquals(task.dueAt, tasks.single().dueAt)
        assertEquals(task.priority, tasks.single().priority)
    }
}
