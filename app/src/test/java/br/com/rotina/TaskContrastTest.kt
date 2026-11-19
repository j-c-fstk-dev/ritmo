package br.com.rotina

import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContrastTest {
    private val palette = RitmoPalettes.tasks
    private val tagPalette = palette
    private val listAccents = RitmoPalettes.listArgb
    private val lightSurface = 0xffffffff.toInt()
    private val darkSurface = 0xff202824.toInt()

    @Test fun taskTextAndFunctionalIndicatorsMeetContrastInBothThemes() {
        listOf(lightSurface, darkSurface).forEach { themeSurface ->
            palette.forEach { color ->
                listOf(false, true).forEach { done ->
                    val card = taskSurfaceArgb(color, themeSurface, done)
                    val text = contentArgb(card)
                    assertTrue("text contrast=${contrastRatio(text, card)} background=$card", contrastRatio(text, card) >= 4.5)
                    val icon = contentArgb(card, 3.0)
                    assertTrue("icon contrast=${contrastRatio(icon, card)} background=$card", contrastRatio(icon, card) >= 3.0)
                    // Expanded notes and controls are placed on the rendered card as well.
                    val note = contentArgb(card)
                    assertTrue(contrastRatio(note, card) >= 4.5)
                    listAccents.forEach { accent ->
                        val safeAccent = accentArgbOn(card, accent)
                        assertTrue("accent contrast=${contrastRatio(safeAccent, card)}", contrastRatio(safeAccent, card) >= 3.0)
                        assertTrue(contrastRatio(contentArgb(safeAccent), safeAccent) >= 4.5)
                        val draggedCard = compositeArgb(safeAccent, card, .20f)
                        assertTrue(contrastRatio(contentArgb(draggedCard), draggedCard) >= 4.5)
                        assertTrue(contrastRatio(accentArgbOn(draggedCard, accent), draggedCard) >= 3.0)
                    }
                    tagPalette.forEach { tagColor ->
                        val tagBackground = compositeArgb(tagColor, card, .18f)
                        assertTrue("tag contrast=${contrastRatio(contentArgb(tagBackground), tagBackground)}", contrastRatio(contentArgb(tagBackground), tagBackground) >= 4.5)
                    }
                    listOf(0xff4f91c7.toInt(), 0xffe9a43b.toInt(), 0xffd9534f.toInt()).forEach { flag ->
                        val safeFlag = accentArgbOn(card, flag, 3.0)
                        assertTrue(contrastRatio(safeFlag, card) >= 3.0)
                    }
                }
            }
        }
    }

    @Test fun captureAndSystemBarsHaveReadableTextInLightAndDarkThemes() {
        listOf(lightSurface, darkSurface, 0xfff5f4ef.toInt(), 0xff141916.toInt()).forEach { background ->
            assertTrue(contrastRatio(contentArgb(background), background) >= 4.5)
            if (background == lightSurface || background == darkSurface) {
                val fieldText = contentArgb(background)
                assertTrue(contrastRatio(fieldText, background) >= 4.5)
                val fieldBorder = accentArgbOn(background, 0xff286b5e.toInt(), 3.0)
                assertTrue(contrastRatio(fieldBorder, background) >= 3.0)
            }
        }
    }
}
