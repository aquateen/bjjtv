package io.github.aquateen.bjjtv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.concurrent.Executors

/** An image that is always as tall as it is wide, so whole covers fit without cropping. */
class SquareImageView(c: Context) : AppCompatImageView(c) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) = super.onMeasure(widthSpec, widthSpec)
}

/** Home screen: the account's library, with search, sorting and an instructor filter. */
class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var sortButton: Button
    private lateinit var instructorButton: Button
    private lateinit var searchButton: Button

    private var courses: List<Course> = emptyList()   // everything, in the site's order
    private var shown: List<Course> = emptyList()     // after search / filter / sort
    private var query = ""
    private var instructor: String? = null
    private var sort = 0
    private var openedCourse = -1L
    private val sortNames = arrayOf("Recently watched", "Title A–Z", "Instructor A–Z")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.token(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), 0)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "My Library"
            setTextColor(Color.WHITE)
            textSize = 24f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        searchButton = Button(this).apply { setOnClickListener { askSearch() } }
        instructorButton = Button(this).apply { setOnClickListener { askInstructor() } }
        sortButton = Button(this).apply {
            setOnClickListener {
                sort = (sort + 1) % sortNames.size
                refresh()
            }
        }
        header.addView(searchButton)
        header.addView(instructorButton)
        header.addView(sortButton)
        header.addView(Button(this).apply {
            text = "Sign out"
            setOnClickListener { signOut() }
        })
        root.addView(header)

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(40))
            adapter = CourseAdapter()
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
        load()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a course: progress and "recently watched" order may have changed.
        if (courses.isNotEmpty()) {
            refresh()
            // Keep the highlight on the course that was just open, wherever the sort moved it.
            val idx = shown.indexOfFirst { it.id == openedCourse }
            if (idx >= 0) {
                grid.scrollToPosition(idx)
                focusTile(idx, 12)
            }
        }
    }

    // The tile only exists once the grid has laid itself out after the scroll, so retry briefly.
    private fun focusTile(idx: Int, triesLeft: Int) {
        grid.postDelayed({
            val view = grid.findViewHolderForAdapterPosition(idx)?.itemView
            if (view != null) view.requestFocus() else if (triesLeft > 0) focusTile(idx, triesLeft - 1)
        }, 40)
    }

    private fun load() {
        status.text = "Loading your library…"
        val token = Prefs.token(this) ?: return
        io.execute {
            try {
                val list = Api.library(token)
                runOnUiThread {
                    courses = list
                    refresh()
                    grid.post { grid.requestFocus() }
                }
            } catch (e: Unauthorized) {
                runOnUiThread { reLogin(token) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't load the library: ${e.message}. Press Back and reopen to retry." }
            }
        }
    }

    /** Applies search, instructor filter and sort, then redraws the grid and the buttons. */
    private fun refresh() {
        if (!::grid.isInitialized) return
        var list = courses
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { c -> c.title.lowercase().contains(q) || c.authors.any { it.lowercase().contains(q) } }
        }
        instructor?.let { name -> list = list.filter { it.authors.contains(name) } }
        list = when (sort) {
            1 -> list.sortedBy { it.title.lowercase() }
            2 -> list.sortedBy { (it.authors.firstOrNull() ?: "~").lowercase() }
            // Courses played on this device first (newest first); the rest keep the site's order.
            else -> list.sortedByDescending { Prefs.lastPlayed(this, it.id) }
        }
        shown = list

        searchButton.text = if (query.isBlank()) "Search" else "Search: $query"
        instructorButton.text = instructor ?: "All instructors"
        sortButton.text = "Sort: ${sortNames[sort]}"
        if (courses.isNotEmpty()) {
            status.text = when {
                list.isEmpty() -> "Nothing matches. Clear the search or instructor filter to see everything."
                list.size == courses.size -> "${courses.size} courses"
                else -> "${list.size} of ${courses.size} courses"
            }
        }
        grid.adapter?.notifyDataSetChanged()
    }

    private fun askSearch() {
        val input = EditText(this).apply {
            setText(query)
            hint = "Title or instructor"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Search your library")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                query = input.text.toString()
                refresh()
            }
            .setNeutralButton("Clear") { _, _ ->
                query = ""
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun askInstructor() {
        val names = courses.flatMap { it.authors }.distinct().sortedBy { it.lowercase() }
        val labels = (listOf("All instructors") + names.map { n -> "$n (${courses.count { it.authors.contains(n) }})" }).toTypedArray()
        val current = instructor?.let { names.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Instructor")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                instructor = if (which == 0) null else names[which - 1]
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun reLogin(stale: String) {
        Prefs.setToken(this, null)
        startActivity(Intent(this, LoginActivity::class.java).putExtra("stale", stale))
        finish()
    }

    private fun signOut() {
        Prefs.setToken(this, null)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private inner class Holder(
        val box: LinearLayout, val image: ImageView, val title: TextView, val author: TextView, val progress: TextView
    ) : RecyclerView.ViewHolder(box)

    private inner class CourseAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) }
            }
            val image = SquareImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(20, 20, 20))
            }
            box.addView(image, LinearLayout.LayoutParams(-1, -2))
            val title = TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setLines(2)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(title)
            val author = TextView(this@MainActivity).apply {
                setTextColor(Color.LTGRAY)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            box.addView(author)
            val progress = TextView(this@MainActivity).apply {
                textSize = 12f
                maxLines = 1
            }
            box.addView(progress)
            box.setOnFocusChangeListener { v: View, focused: Boolean ->
                v.setBackgroundColor(if (focused) Color.rgb(40, 70, 150) else Color.TRANSPARENT)
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
            return Holder(box, image, title, author, progress)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = shown[position]
            h.title.text = c.title
            h.author.text = c.authors.joinToString(", ")
            h.image.load(c.image) { crossfade(true) }

            val done = Prefs.watched(this@MainActivity, c.id).size
            val started = done > 0 || Prefs.lastPlayed(this@MainActivity, c.id) > 0
            when {
                c.total > 0 && done >= c.total -> {
                    h.progress.text = "✓ Finished"
                    h.progress.setTextColor(Color.rgb(110, 200, 120))
                }
                started -> {
                    h.progress.text = if (c.total > 0) "▶ $done of ${c.total} watched" else "▶ In progress"
                    h.progress.setTextColor(Color.rgb(255, 190, 70))
                }
                else -> {
                    h.progress.text = if (c.total > 0) "${c.total} chapters" else ""
                    h.progress.setTextColor(Color.GRAY)
                }
            }
            h.box.setOnClickListener {
                openedCourse = c.id
                startActivity(
                    Intent(this@MainActivity, CourseActivity::class.java)
                        .putExtra("id", c.id)
                        .putExtra("title", c.title)
                )
            }
        }
    }
}
