package dev.axzy.stock.mobile.core.storage

import android.content.Context

/**
 * The application context, for the storage that needs one. `MainActivity` sets it before anything is composed, so
 * the `expect` functions of common code can be called without arguments, as on iOS.
 */
object AndroidAppContext {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    val appContext: Context
        get() = checkNotNull(context) { "AndroidAppContext.init was not called before the app started" }
}
