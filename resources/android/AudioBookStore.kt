package com.blutrixx.plugins.audiobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the player keeps on the phone, so it works with no PHP and no web view running:
 *  - each book: where its audio is on the server (base URL, the server's book id, the key), its title and its pages in order;
 *  - the player: which book, which page, where in the page, the speed;
 *  - what was heard: one entry per page played (to its end or not), taken by the app when it comes back to count the listening.
 * The audio is in the app's own folder: <external files>/audiobooks/<book>/<page>.mp3 (no permission needed, removed with the app).
 */
internal object AudioBookStore {
    private const val PREFS = "blutrixx_audiobook"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)

    fun saveBook(ctx: Context, b: JSONObject) { prefs(ctx).edit().putString("book:" + safe(b.getString("id")), b.toString()).apply() }

    fun book(ctx: Context, id: String): JSONObject? = prefs(ctx).getString("book:" + safe(id), null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    fun books(ctx: Context): List<JSONObject> = prefs(ctx).all.filterKeys { it.startsWith("book:") }.values.mapNotNull { v -> (v as? String)?.let { try { JSONObject(it) } catch (e: Exception) { null } } }

    fun removeBook(ctx: Context, id: String) {
        prefs(ctx).edit().remove("book:" + safe(id)).apply()
        dir(ctx, id).deleteRecursively()
    }

    fun pages(b: JSONObject): IntArray { val a = b.optJSONArray("pages") ?: return IntArray(0); return IntArray(a.length()) { a.getInt(it) } }

    fun state(ctx: Context): JSONObject = prefs(ctx).getString("state", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject()

    fun saveState(ctx: Context, s: JSONObject) { prefs(ctx).edit().putString("state", s.toString()).apply() }

    @Synchronized
    fun addHeard(ctx: Context, book: String, page: Int, ms: Long, done: Boolean) {
        if (ms < 1000 && !done) return
        val a = try { JSONArray(prefs(ctx).getString("heard", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("book", book).put("n", page).put("ms", ms).put("done", done).put("at", System.currentTimeMillis()))
        while (a.length() > 2000) a.remove(0)   // the app takes them often; this only guards against a phone that never opens it
        prefs(ctx).edit().putString("heard", a.toString()).apply()
    }

    @Synchronized
    fun heard(ctx: Context, drain: Boolean): JSONArray {
        val a = try { JSONArray(prefs(ctx).getString("heard", "[]")) } catch (e: Exception) { JSONArray() }
        if (drain) prefs(ctx).edit().putString("heard", "[]").apply()
        return a
    }

    fun dir(ctx: Context, book: String): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "audiobooks/" + safe(book))

    fun file(ctx: Context, book: String, page: Int): File = File(dir(ctx, book), "$page.mp3")

    /** pages of a book that are on the phone */
    fun downloaded(ctx: Context, book: String): List<Int> =
        dir(ctx, book).listFiles { f -> f.name.endsWith(".mp3") && f.length() > 0 }?.mapNotNull { it.name.removeSuffix(".mp3").toIntOrNull() }?.sorted() ?: emptyList()

    fun bytes(ctx: Context, book: String): Long = dir(ctx, book).listFiles()?.sumOf { it.length() } ?: 0L
}
