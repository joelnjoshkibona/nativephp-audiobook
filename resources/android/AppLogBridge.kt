package com.blutrixx.plugins.audiobook

/**
 * The app's log, if the device-utils plugin is in the app (by reflection: no dependency on it). Never the book's words or its title: only
 * page numbers, counts and what happened.
 */
internal object AppLogBridge {
    private val write = try { Class.forName("com.blutrixx.device.utils.AppLog").getMethod("write", Char::class.javaPrimitiveType, String::class.java, String::class.java) } catch (e: Throwable) { null }
    fun w(level: Char, message: String) { try { write?.invoke(null, level, "audiobook", message) } catch (e: Throwable) { } }
}
