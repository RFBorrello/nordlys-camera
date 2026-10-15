package com.nordlys.camera.data

import android.content.Context
import com.nordlys.camera.model.AspectSetting
import com.nordlys.camera.model.CaptureMode
import com.nordlys.camera.model.DualCapture
import com.nordlys.camera.model.FlashSetting
import com.nordlys.camera.model.FocusSetting
import com.nordlys.camera.model.GridType
import com.nordlys.camera.model.ManualState
import com.nordlys.camera.model.SceneSetting
import com.nordlys.camera.model.WbSetting

/** Viewfinder preferences that survive restarts, like the reference's per-mode settings. */
data class CameraPrefs(
    val grid: GridType = GridType.NONE,
    val aspect: AspectSetting = AspectSetting.FOUR_THREE,
    val dual: DualCapture = DualCapture.OFF,
    val living: Boolean = false,
    val location: Boolean = false,
    val advanced: Boolean = false,
    val timerSec: Int = 0,
    val bracketCount: Int = 1,
    val bracketEv: Float = 1f,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val front: Boolean = false,
    val manual: ManualState = ManualState(),
)

class CameraPrefsStore(context: Context) {
    private val sp = context.getSharedPreferences("viewfinder", Context.MODE_PRIVATE)

    fun load(): CameraPrefs = runCatching {
        val focus = when (val f = sp.getFloat("focus", -1f)) {
            -1f -> FocusSetting.Auto
            -2f -> FocusSetting.Infinity
            else -> FocusSetting.Manual(f)
        }
        CameraPrefs(
            grid = enumOr(sp.getString("grid", null), GridType.NONE),
            aspect = enumOr(sp.getString("aspect", null), AspectSetting.FOUR_THREE),
            dual = enumOr(sp.getString("dual", null), DualCapture.OFF),
            living = sp.getBoolean("living", false),
            location = sp.getBoolean("location", false),
            advanced = sp.getBoolean("advanced", false),
            timerSec = sp.getInt("timer", 0),
            bracketCount = sp.getInt("bracketCount", 1),
            bracketEv = sp.getFloat("bracketEv", 1f),
            mode = enumOr(sp.getString("mode", null), CaptureMode.PHOTO),
            front = sp.getBoolean("front", false),
            manual = ManualState(
                iso = sp.getInt("iso", -1).takeIf { it > 0 },
                shutterNs = sp.getLong("shutter", -1L).takeIf { it > 0 },
                evThirds = sp.getInt("ev", 0),
                wb = enumOr(sp.getString("wb", null), WbSetting.AUTO),
                focus = focus,
                flash = enumOr(sp.getString("flash", null), FlashSetting.AUTO),
                scene = enumOr(sp.getString("scene", null), SceneSetting.AUTO),
            ),
        )
    }.getOrDefault(CameraPrefs())

    fun save(p: CameraPrefs) {
        sp.edit()
            .putString("grid", p.grid.name)
            .putString("aspect", p.aspect.name)
            .putString("dual", p.dual.name)
            .putBoolean("living", p.living)
            .putBoolean("location", p.location)
            .putBoolean("advanced", p.advanced)
            .putInt("timer", p.timerSec)
            .putInt("bracketCount", p.bracketCount)
            .putFloat("bracketEv", p.bracketEv)
            .putString("mode", p.mode.name)
            .putBoolean("front", p.front)
            .putInt("iso", p.manual.iso ?: -1)
            .putLong("shutter", p.manual.shutterNs ?: -1L)
            .putInt("ev", p.manual.evThirds)
            .putString("wb", p.manual.wb.name)
            .putFloat(
                "focus",
                when (val f = p.manual.focus) {
                    FocusSetting.Auto -> -1f
                    FocusSetting.Infinity -> -2f
                    is FocusSetting.Manual -> f.position
                },
            )
            .putString("flash", p.manual.flash.name)
            .putString("scene", p.manual.scene.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
