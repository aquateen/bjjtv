package io.github.aquateen.bjjtv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * One course: its volumes and chapters, with watched / in-progress markers.
 * Selecting a chapter plays from there onwards; holding OK toggles its watched mark.
 */
class CourseActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var courseId = 0L
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private var detail: CourseDetail? = null

    // Flattened rows: a volume header (chapter == null) or a chapter with its play index.
    private class Row(val volume: Volume?, val chapter: Chapter?, val playIndex: Int, val number: Int)
    private var rows: List<Row> = emptyList()
    private var all: List<Chapter> = emptyList()
    private var watched: Set<String> = emptySet()
    private var returningFromPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = intent.getLongExtra("id", 0L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(20), dp(48), 0)
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            setTextColor(Color.WHITE)
            textSize = 20f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(status)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CourseActivity)
            itemAnimator = null   // change animations swap row views, which drops the highlight
            clipToPadding = false
            setPadding(0, 0, 0, dp(40))
            adapter = RowAdapter()
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the markers after coming back from the player, and put the highlight on the
        // chapter that was playing (the player may have moved on several chapters by itself).
        redraw()
        if (returningFromPlayer) {
            returningFromPlayer = false
            focusChapter(Prefs.lastVideo(this, courseId))
        }
    }

    /** Scrolls to a chapter and highlights it; falls back to the first chapter. */
    private fun focusChapter(videoId: String?) {
        val idx = rows.indexOfFirst { it.chapter != null && it.chapter.id == videoId }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.chapter != null }
        if (idx < 0) return
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(120))
        focusRow(idx, 12)
    }

    // The row only exists once the list has laid itself out after the scroll, so retry briefly.
    private fun focusRow(idx: Int, triesLeft: Int) {
        list.postDelayed({
            val view = list.findViewHolderForAdapterPosition(idx)?.itemView
            if (view != null) view.requestFocus() else if (triesLeft > 0) focusRow(idx, triesLeft - 1)
        }, 40)
    }

    private fun redraw() {
        watched = Prefs.watched(this, courseId)
        val d = detail
        if (d != null && all.isNotEmpty() && d.hosts.isNotEmpty()) {
            val done = all.count { it.id in watched }
            status.text = "${d.volumes.size} volumes  ·  $done of ${all.size} chapters watched  ·  hold OK on a chapter to mark it watched or unwatched"
        }
        // Re-bind rows in place (rather than rebuilding the list) so the highlighted row keeps focus.
        list.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    private fun load() {
        status.text = "Loading…"
        val token = Prefs.token(this) ?: return finish()
        io.execute {
            try {
                val d = Api.course(courseId, token)
                val r = ArrayList<Row>()
                val flat = ArrayList<Chapter>()
                for (v in d.volumes) {
                    r.add(Row(v, null, -1, 0))
                    v.chapters.forEachIndexed { i, c ->
                        r.add(Row(null, c, flat.size, i + 1))
                        flat.add(c)
                    }
                }
                runOnUiThread {
                    detail = d
                    rows = r
                    all = flat
                    when {
                        flat.isEmpty() -> status.text = "No videos found for this course."
                        d.hosts.isEmpty() -> status.text = "This course has no video host listed, so it can't be played here."
                    }
                    watched = Prefs.watched(this, courseId)
                    list.adapter?.notifyDataSetChanged()
                    redraw()
                    // Start on the chapter played last, or the first chapter.
                    focusChapter(Prefs.lastVideo(this, courseId))
                }
            } catch (e: Unauthorized) {
                runOnUiThread {
                    Prefs.setToken(this, null)
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .putExtra("stale", token)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't load this course: ${e.message}" }
            }
        }
    }

    private fun play(index: Int) {
        val d = detail ?: return
        if (d.hosts.isEmpty()) return
        returningFromPlayer = true
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("courseId", courseId)
                .putExtra("hosts", d.hosts.toTypedArray())
                .putExtra("ids", all.map { it.id }.toTypedArray())
                .putExtra("titles", all.map { it.title }.toTypedArray())
                .putExtra("index", index)
        )
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "00:06:36" -> "6:36", "01:02:03" stays "1:02:03". */
    private fun shortTime(t: String): String {
        var s = t.removePrefix("00:")
        if (s.length > 1 && s[0] == '0' && s[1] != ':') s = s.substring(1)
        return s
    }

    private inner class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    private inner class ChapterHolder(val box: LinearLayout, val mark: TextView, val title: TextView, val info: TextView) :
        RecyclerView.ViewHolder(box)

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].chapter == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val t = TextView(this@CourseActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                    setTextColor(Color.rgb(120, 160, 255))
                    textSize = 16f
                    setPadding(0, dp(18), 0, dp(6))
                }
                return HeaderHolder(t)
            }
            val box = LinearLayout(this@CourseActivity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(12), dp(9), dp(16), dp(9))
                setOnFocusChangeListener { v: View, focused: Boolean ->
                    v.setBackgroundColor(if (focused) Color.rgb(40, 70, 150) else Color.TRANSPARENT)
                }
            }
            val mark = TextView(this@CourseActivity).apply {
                textSize = 16f
                gravity = Gravity.CENTER
            }
            box.addView(mark, LinearLayout.LayoutParams(dp(32), -2))
            val title = TextView(this@CourseActivity).apply {
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            box.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val info = TextView(this@CourseActivity).apply {
                textSize = 14f
                maxLines = 1
                setPadding(dp(16), 0, 0, 0)
            }
            box.addView(info)
            return ChapterHolder(box, mark, title, info)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val c = row.chapter
            if (c == null) {
                val v = row.volume!!
                val done = v.chapters.count { it.id in watched }
                (holder as HeaderHolder).text.text = "${v.title}   ·   $done of ${v.chapters.size} watched"
                return
            }
            val h = holder as ChapterHolder
            val isWatched = c.id in watched
            val pos = Prefs.position(this@CourseActivity, c.id)
            val length = shortTime(c.time)
            h.title.text = "${row.number}. ${c.title}"
            when {
                isWatched -> {
                    h.mark.text = "✓"
                    h.mark.setTextColor(Color.rgb(110, 200, 120))
                    h.title.setTextColor(Color.GRAY)
                    h.info.setTextColor(Color.GRAY)
                    h.info.text = length
                }
                pos > 0 -> {
                    h.mark.text = "▶"
                    h.mark.setTextColor(Color.rgb(255, 190, 70))
                    h.title.setTextColor(Color.WHITE)
                    h.info.setTextColor(Color.rgb(255, 190, 70))
                    h.info.text = if (length.isBlank()) "resume at ${clock(pos)}" else "resume at ${clock(pos)} of $length"
                }
                else -> {
                    h.mark.text = ""
                    h.title.setTextColor(Color.WHITE)
                    h.info.setTextColor(Color.LTGRAY)
                    h.info.text = length
                }
            }
            h.box.setOnClickListener { play(row.playIndex) }
            h.box.setOnLongClickListener {
                Prefs.setWatched(this@CourseActivity, courseId, c.id, !isWatched)
                redraw()
                true
            }
        }
    }
}
