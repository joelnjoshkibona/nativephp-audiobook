package com.blutrixx.plugins.audiobook

import android.content.Context
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import org.json.JSONObject

/** Bridge functions the PHP side calls (see nativephp.json). */
object AudioBookFunctions {
    /** The bridge may hand an array over as a Kotlin list, a JSONArray or a JSON string. */
    private fun asList(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is JSONArray -> List(v.length()) { v.get(it) }
        is String -> try { asList(JSONArray(v)) } catch (e: Exception) { emptyList() }
        else -> emptyList()
    }

    private fun str(p: Map<String, Any>, k: String): String? = (p[k] as? String)?.takeIf { it.isNotBlank() }

    private fun num(p: Map<String, Any>, k: String): Number? = when (val v = p[k]) { is Number -> v; is String -> v.toDoubleOrNull(); else -> null }

    /** Keep a book on the phone: where its audio is on the server, its title and its pages; with sync, start fetching the pages now. */
    class Prepare(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = str(parameters, "book") ?: return BridgeResponse.error("INVALID", "Missing book")
            val remote = str(parameters, "remote") ?: return BridgeResponse.error("INVALID", "Missing remote")
            val base = str(parameters, "base") ?: return BridgeResponse.error("INVALID", "Missing base")
            if (!base.startsWith("https://") && !base.startsWith("http://")) return BridgeResponse.error("INVALID", "The base must be a web address")
            val pages = asList(parameters["pages"]).mapNotNull { (it as? Number)?.toInt() }.filter { it > 0 }.distinct().sorted()
            if (pages.isEmpty()) return BridgeResponse.error("INVALID", "No pages")
            val old = AudioBookStore.book(context, id)
            if (old != null && old.optString("remote") != remote) AudioBookStore.removeBook(context, id)   // another voice or text: the old audio goes
            val b = (if (old != null && old.optString("remote") == remote) old else JSONObject())
                .put("id", id).put("remote", remote).put("base", base).put("key", str(parameters, "key") ?: "")
                .put("title", str(parameters, "title") ?: "Audiobook").put("total", num(parameters, "total")?.toInt() ?: pages.last())
                .put("pages", JSONArray(pages))
            AudioBookStore.saveBook(context, b)
            val sync = parameters["sync"]
            if (sync == null || sync == true || sync == "true" || (sync as? Number)?.toInt() == 1) AudioBookService.send(context, AudioBookService.ACTION_SYNC) { putExtra("book", id) }
            return BridgeResponse.success(mapOf("book" to id, "pages" to pages.size, "downloaded" to AudioBookStore.downloaded(context, id).size))
        }
    }

    /** Play a book from a page (and a place in it, in ms) at a speed. */
    class Play(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = str(parameters, "book") ?: return BridgeResponse.error("INVALID", "Missing book")
            if (AudioBookStore.book(context, id) == null) return BridgeResponse.error("NOT_PREPARED", "This audiobook is not on the phone")
            AudioBookService.send(context, AudioBookService.ACTION_PLAY) {
                putExtra("book", id)
                putExtra("page", num(parameters, "page")?.toInt() ?: 0)
                putExtra("pos", num(parameters, "pos")?.toInt() ?: 0)
                num(parameters, "speed")?.let { putExtra("speed", it.toFloat()) }
            }
            return BridgeResponse.success(mapOf("playing" to true))
        }
    }

    /** pause, resume, toggle, next, prev, stop, speed (with speed), seek (with page and pos) */
    class Control(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val action = when (str(parameters, "action")) {
                "pause" -> AudioBookService.ACTION_PAUSE
                "resume" -> AudioBookService.ACTION_RESUME
                "toggle" -> AudioBookService.ACTION_TOGGLE
                "next" -> AudioBookService.ACTION_NEXT
                "prev" -> AudioBookService.ACTION_PREV
                "stop" -> AudioBookService.ACTION_STOP
                "speed" -> AudioBookService.ACTION_SPEED
                "seek" -> AudioBookService.ACTION_SEEK
                else -> return BridgeResponse.error("INVALID", "Unknown action")
            }
            // nothing is running: a stop or pause has nothing to do (and must not start the service just to end it)
            if (AudioBookService.live == null && action in listOf(AudioBookService.ACTION_STOP, AudioBookService.ACTION_PAUSE)) return BridgeResponse.success(mapOf("done" to true))
            AudioBookService.send(context, action) {
                num(parameters, "speed")?.let { putExtra("speed", it.toFloat()) }
                num(parameters, "page")?.let { putExtra("page", it.toInt()) }
                num(parameters, "pos")?.let { putExtra("pos", it.toInt()) }
            }
            return BridgeResponse.success(mapOf("done" to true))
        }
    }

    /** Where the player is, what a book has on the phone, and what was heard (with drain, the heard list is handed over once). */
    class State(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val s = AudioBookService.live?.snapshot() ?: AudioBookStore.state(context).put("running", false).put("playing", false)
            val out = mutableMapOf<String, Any>("player" to s.toString())
            str(parameters, "book")?.let { id ->
                val b = AudioBookStore.book(context, id)
                out["book"] = JSONObject().put("prepared", b != null).put("remote", b?.optString("remote") ?: "").put("pages", b?.optJSONArray("pages")?.length() ?: 0)
                    .put("ready", b?.optInt("ready") ?: 0).put("server", b?.optString("server") ?: "").put("failed", b?.optInt("failed") ?: 0)
                    .put("downloaded", AudioBookStore.downloaded(context, id).size).put("bytes", AudioBookStore.bytes(context, id)).toString()
            }
            val drain = parameters["drain"]
            out["heard"] = AudioBookStore.heard(context, drain == true || drain == "true" || (drain as? Number)?.toInt() == 1).toString()
            return BridgeResponse.success(out)
        }
    }

    /** Remove a book's audio from the phone (stops it if it is playing). */
    class Delete(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = str(parameters, "book") ?: return BridgeResponse.error("INVALID", "Missing book")
            if (AudioBookService.live != null) AudioBookService.send(context, AudioBookService.ACTION_FORGET) { putExtra("book", id) }
            else AudioBookStore.removeBook(context, id)
            return BridgeResponse.success(mapOf("deleted" to true))
        }
    }
}
