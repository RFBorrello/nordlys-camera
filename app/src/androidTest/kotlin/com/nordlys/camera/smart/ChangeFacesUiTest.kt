package com.nordlys.camera.smart

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nordlys.camera.ui.smart.SmartEditorScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the smart editor's change-faces flow on a dumped sequence
 * (files/sequences/<id>): open the tool, tap the detected face, pick it from
 * another frame, save. Screenshots and the saved JPEG land in files/faces-out/.
 *
 *   adb shell am instrument -w -e seq <id> -e from 0 -e class com.nordlys.camera.smart.ChangeFacesUiTest \
 *     com.nordlys.camera.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ChangeFacesUiTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun tapFaceAndTakeItFromAnotherFrame() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("seq")
        assumeTrue("pass -e seq <id>", id != null)
        val from = args.getString("from")?.toInt() ?: 0
        val root = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val dir = File(root, "sequences/$id")
        val out = File(root, "faces-out").apply { mkdirs() }
        val rotation = File(dir, "meta.txt").readLines().first { it.startsWith("rotation=") }.substringAfter('=').toInt()
        val frames = dir.listFiles { f -> f.name.endsWith(".i420") }!!
            .sortedBy { it.name.substringAfter('f').substringBefore('-').toInt() }
            .map { f ->
                val (w, h) = Regex("(\\d+)x(\\d+)").find(f.name)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
                val b = f.readBytes()
                val c = w * h / 4
                YuvFrame(w, h, b.copyOfRange(0, w * h), b.copyOfRange(w * h, w * h + c), b.copyOfRange(w * h + c, w * h + 2 * c))
            }
        // where the editor will find the face, computed the same way it does
        val face = runBlocking {
            val st = SmartCompositor.stabilise(frames, frames.size / 2)
            FaceFinder.across(st.map { it.toPreviewBitmap(rotation, 640)!! })
        }.firstOrNull()
        assumeTrue("no face in this sequence", face != null)

        var saved: ByteArray? = null
        rule.setContent {
            SmartEditorScreen(frames = frames, rotation = rotation, onSave = { saved = it; true }, onClose = {})
        }
        fun idle() = rule.waitUntil(20_000) { rule.onAllNodesWithText("processing…").fetchSemanticsNodes().isEmpty() }

        idle()
        rule.onNodeWithText("change faces").performScrollTo().performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Tap a face, then pick how it looks from the strip below.").fetchSemanticsNodes().isNotEmpty()
        }
        idle()
        shot(out, "$id-ui-1-faces")

        // the preview is the upright frame fitted into the box
        val box = rule.onNodeWithTag("smart-preview").fetchSemanticsNode().size
        val upright90 = rotation % 180 == 90
        val pw = (if (upright90) frames[0].height else frames[0].width).toFloat()
        val ph = (if (upright90) frames[0].width else frames[0].height).toFloat()
        val scale = minOf(box.width / pw, box.height / ph)
        val iw = pw * scale
        val ih = ph * scale
        val left = (box.width - iw) / 2f
        val top = (box.height - ih) / 2f
        val f = face!!
        rule.onNodeWithTag("smart-preview").performTouchInput {
            click(Offset(left + f.u * iw, top + f.v * ih + f.eyes * iw * 0.45f))
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Pick the frame this face should come from.").fetchSemanticsNodes().isNotEmpty()
        }
        shot(out, "$id-ui-2-picking")

        rule.onNodeWithTag("face-chip-$from").performClick()
        rule.waitForIdle()
        idle()
        Thread.sleep(500)
        shot(out, "$id-ui-3-changed")

        rule.onNodeWithText("save").performClick()
        rule.waitUntil(20_000) { saved != null }
        File(out, "$id-ui-saved.jpg").writeBytes(saved!!)
    }

    private fun shot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
