package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun undoAndRedoTraverseSnapshotsInOrder() {
        val initial = testProject()
        val first = initial.copy(name = "Primer cambio")
        val second = first.copy(name = "Segundo cambio")
        val empty = EditHistory(initial)

        assertFalse(empty.canUndo)
        assertFalse(empty.canRedo)
        assertEquals(empty, empty.undo())
        assertEquals(empty, empty.redo())

        val latest = empty.push(first).push(second)
        val once = latest.undo()
        val twice = once.undo()

        assertEquals(first, once.present)
        assertTrue(once.canUndo)
        assertTrue(once.canRedo)
        assertEquals(initial, twice.present)
        assertFalse(twice.canUndo)
        assertEquals(first, twice.redo().present)
        assertEquals(second, twice.redo().redo().present)
        assertFalse(twice.redo().redo().canRedo)
    }

    @Test
    fun equalPushPreservesRedoButNewEditDiscardsIt() {
        val initial = testProject()
        val undone = EditHistory(initial).push(initial.copy(name = "Primera versión")).undo()
        val branch = initial.copy(name = "Nueva versión")

        assertEquals(undone, undone.push(initial))

        val history = undone.push(branch)

        assertEquals(branch, history.present)
        assertFalse(history.canRedo)
        assertEquals(initial, history.undo().present)
    }

    @Test
    fun onlyConsecutiveEditsWithSameNonNullKeyMerge() {
        val initial = testProject()
        val firstVolume = initial.copy(name = "Volumen 1")
        val secondVolume = initial.copy(name = "Volumen 2")
        val opacity = initial.copy(name = "Opacidad")
        val thirdVolume = initial.copy(name = "Volumen 3")
        val ungrouped = initial.copy(name = "Cambio individual")
        val final = initial.copy(name = "Último cambio")
        val history = EditHistory(initial)
            .push(firstVolume, "volume")
            .push(secondVolume, "volume")
            .push(opacity, "opacity")
            .push(thirdVolume, "volume")
            .push(ungrouped)
            .push(final)

        val undone = generateSequence(history) { current ->
            if (current.canUndo) current.undo() else null
        }.drop(1).map { it.present }.toList()

        assertEquals(listOf(ungrouped, thirdVolume, opacity, secondVolume, initial), undone)
    }

    @Test
    fun commitGroupMakesNextEditWithSameKeyIndependentlyUndoable() {
        val initial = testProject()
        val first = initial.copy(name = "Primer ajuste")
        val second = initial.copy(name = "Segundo ajuste")
        val history = EditHistory(initial).push(first, "volume").commitGroup().push(second, "volume")

        assertEquals(first, history.undo().present)
        assertEquals(initial, history.undo().undo().present)
    }

    @Test
    fun historyLimitRetainsMostRecentUndoSteps() {
        val initial = testProject()
        val first = initial.copy(name = "Primero")
        val second = initial.copy(name = "Segundo")
        val third = initial.copy(name = "Tercero")
        val history = EditHistory(initial, limit = 2).push(first).push(second).push(third)

        assertEquals(second, history.undo().present)
        val oldest = history.undo().undo()
        assertEquals(first, oldest.present)
        assertFalse(oldest.canUndo)
        assertEquals(third, oldest.redo().redo().present)
    }
}
