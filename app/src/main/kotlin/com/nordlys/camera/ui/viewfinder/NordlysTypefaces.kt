package com.nordlys.camera.ui.viewfinder

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.nordlys.camera.R

/** Platform typefaces for text drawn straight onto canvases (dial values, glyph words). */
object NordlysTypefaces {
    var regular: Typeface = Typeface.DEFAULT
        private set
    var semibold: Typeface = Typeface.DEFAULT_BOLD
        private set
    var bold: Typeface = Typeface.DEFAULT_BOLD
        private set
    var light: Typeface = Typeface.DEFAULT
        private set

    fun init(context: Context) {
        runCatching {
            regular = ResourcesCompat.getFont(context, R.font.selawk) ?: regular
            semibold = ResourcesCompat.getFont(context, R.font.selawksb) ?: semibold
            bold = ResourcesCompat.getFont(context, R.font.selawkb) ?: bold
            light = ResourcesCompat.getFont(context, R.font.selawkl) ?: light
        }
    }
}
