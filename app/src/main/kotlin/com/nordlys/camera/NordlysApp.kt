package com.nordlys.camera

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes in-process crash faults to app storage so faults surfacing after a
 * grant can be reported from the device without ADB (share from the app).
 */
class NordlysApp : Application(), coil.ImageLoaderFactory {

    companion object { const val CRASH_FILE = "crash.log"; const val CRASH_SEEN = "crash.seen" }

    override fun onCreate() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val body = buildString {
                    appendLine("thread: ${thread.name}")
                    appendLine(sw.toString())
                }
                File(filesDir, CRASH_FILE).writeText(body)
                // mark unseen so the app offers the report on the next launch
                File(filesDir, CRASH_SEEN).delete()
            }
            prior?.uncaughtException(thread, e)
        }
        super.onCreate()
        com.nordlys.camera.ui.viewfinder.NordlysTypefaces.init(this)
    }

    /** Library thumbnails for clips come from their frames. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .components { add(coil.decode.VideoFrameDecoder.Factory()) }
        .build()
}
