package io.github.aquateen.bjjtv

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Course(val id: Long, val title: String, val image: String, val authors: List<String>, val total: Int)
data class Chapter(val id: String, val title: String, val time: String)
data class Volume(val title: String, val chapters: List<Chapter>)
data class CourseDetail(val title: String, val hosts: List<String>, val volumes: List<Volume>)

/** Thrown when the saved session token is rejected and the user has to sign in again. */
class Unauthorized : Exception()

object Api {
    private const val BASE = "https://vapi.bjjfanatics.com/api/v1"

    private fun get(path: String, token: String): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        // The site sends the session token as the raw Authorization value (no "Bearer").
        c.setRequestProperty("Authorization", token)
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw Unauthorized()
            if (code !in 200..299) throw IOException("Server returned HTTP $code")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun library(token: String): List<Course> {
        val arr = get("/library/products", token).optJSONArray("products") ?: return emptyList()
        val out = ArrayList<Course>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val authors = p.optJSONArray("authors")
            // The author list also carries store tags such as "best selling"; keep only names.
            val names = (0 until (authors?.length() ?: 0))
                .map { authors!!.optString(it).replace(Regex("(?i)best[ -]?sell(ing|ers?)"), "").trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val total = p.optJSONObject("bunnyNetVideos")?.optInt("qtyVideos") ?: 0
            out.add(Course(p.getLong("id"), p.optString("title"), p.optString("image"), names, total))
        }
        return out
    }

    fun course(id: Long, token: String): CourseDetail {
        val p = get("/library/products/$id", token).getJSONObject("product")
        val bunny = p.optJSONObject("bunnyNetVideos")

        // Video hosts: the product's own pull zone first, then any extra bundle zones.
        val hosts = LinkedHashSet<String>()
        fun addHost(h: String?) {
            if (h.isNullOrBlank() || h == "null") return
            hosts.add(if (h.contains('.')) h else "$h.b-cdn.net")
        }
        addHost(bunny?.optString("pullZoneId"))
        bunny?.optJSONArray("bundlePullZonesIds")?.let { z -> for (i in 0 until z.length()) addHost(z.optString(i)) }

        // Per-video details from the video host listing, keyed by video id.
        data class B(val id: String, val title: String, val time: String, val coll: String, val chapter: Int)
        val bVideos = ArrayList<B>()
        bunny?.optJSONArray("videos")?.let { v ->
            for (i in 0 until v.length()) {
                val o = v.getJSONObject(i)
                bVideos.add(B(o.optString("id"), o.optString("title"), o.optString("time"), o.optString("collectionId"), o.optInt("chapter")))
            }
        }
        val timeById = bVideos.associate { it.id to it.time }

        val volumes = ArrayList<Volume>()
        p.optJSONObject("videoList")?.optJSONArray("volumes")?.let { vols ->
            for (i in 0 until vols.length()) {
                val v = vols.getJSONObject(i)
                val vids = v.optJSONArray("videos")
                val chapters = ArrayList<Chapter>()
                for (j in 0 until (vids?.length() ?: 0)) {
                    val o = vids!!.getJSONObject(j)
                    val vid = o.optString("id")
                    if (vid.isNotBlank()) chapters.add(Chapter(vid, o.optString("title"), timeById[vid] ?: ""))
                }
                if (chapters.isNotEmpty()) volumes.add(Volume(v.optString("title", "Volume ${i + 1}"), chapters))
            }
        }

        // Fallback: build volumes from the host's collections if the volume list was empty.
        if (volumes.isEmpty() && bVideos.isNotEmpty()) {
            val names = HashMap<String, String>()
            bunny?.optJSONArray("collections")?.let { c ->
                for (i in 0 until c.length()) c.getJSONObject(i).let { names[it.optString("id")] = it.optString("name") }
            }
            bVideos.groupBy { it.coll }.forEach { (coll, list) ->
                volumes.add(Volume(names[coll] ?: "Videos", list.sortedBy { it.chapter }.map { Chapter(it.id, it.title, it.time) }))
            }
        }
        return CourseDetail(p.optString("title"), hosts.toList(), volumes)
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("bjjtv", Context.MODE_PRIVATE)

    fun token(c: Context): String? = sp(c).getString("token", null)
    fun setToken(c: Context, t: String?) = sp(c).edit().putString("token", t).apply()

    fun position(c: Context, videoId: String): Long = sp(c).getLong("pos_$videoId", 0L)
    fun setPosition(c: Context, videoId: String, ms: Long) {
        if (ms > 0) sp(c).edit().putLong("pos_$videoId", ms).apply() else sp(c).edit().remove("pos_$videoId").apply()
    }

    /** Chapters of a course that have been watched to the end (tracked on this device). */
    fun watched(c: Context, courseId: Long): Set<String> =
        HashSet(sp(c).getStringSet("watched_$courseId", emptySet()) ?: emptySet())

    fun setWatched(c: Context, courseId: Long, videoId: String, done: Boolean) {
        val set = HashSet(watched(c, courseId))
        if (done) set.add(videoId) else set.remove(videoId)
        sp(c).edit().putStringSet("watched_$courseId", set).apply()
        if (done) setPosition(c, videoId, 0L)
    }

    /** When a course was last played, for the "recently watched" sort. 0 = never. */
    fun lastPlayed(c: Context, courseId: Long): Long = sp(c).getLong("played_$courseId", 0L)
    fun touchPlayed(c: Context, courseId: Long) =
        sp(c).edit().putLong("played_$courseId", System.currentTimeMillis()).apply()

    fun lastVideo(c: Context, courseId: Long): String? = sp(c).getString("last_$courseId", null)
    fun setLastVideo(c: Context, courseId: Long, videoId: String) = sp(c).edit().putString("last_$courseId", videoId).apply()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
