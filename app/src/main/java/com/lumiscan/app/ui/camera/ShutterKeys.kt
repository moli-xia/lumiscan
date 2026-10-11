package com.lumiscan.app.ui.camera

/** Lets the volume keys act as a shutter while a camera screen is showing. */
object ShutterKeys {
    @Volatile
    var onShutter: (() -> Unit)? = null
}
