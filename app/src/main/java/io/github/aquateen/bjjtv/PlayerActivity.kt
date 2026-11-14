package io.github.aquateen.bjjtv

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays a course's chapters as one playlist, starting at the chosen chapter.
 * Each video is a standard HLS stream at https://<host>/<videoId>/playlist.m3u8.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var view: PlayerView
    private var player: ExoPlayer? = null

    private var courseId = 0L
    private lateinit var hosts: Array<String>
    private lateinit var ids: Array<String>
    private lateinit var titles: Array<String>

    private var hostIndex = 0
    private var index = 0
    private var positionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        courseId = intent.getLongExtra("courseId", 0L)
        hosts = intent.getStringArrayExtra("hosts") ?: emptyArray()
        ids = intent.getStringArrayExtra("ids") ?: emptyArray()
        titles = intent.getStringArrayExtra("titles") ?: emptyArray()
        if (hosts.isEmpty() || ids.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)).coerceIn(0, ids.size - 1)
        positionMs = savedInstanceState?.getLong("pos") ?: Prefs.position(this, ids[index])

        view = PlayerView(this)
        view.setShowNextButton(true)
        view.setShowPreviousButton(true)
        setContentView(view)
        view.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (::view.isInitialized) startPlayer()
    }

    override fun onStop() {
        savePosition()
        player?.release()
        player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putLong("pos", positionMs)
    }

    private fun startPlayer() {
        player?.release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://bjjfanatics.com/"))
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()

        val host = hosts[hostIndex]
        val items = ids.indices.map { i ->
            MediaItem.Builder()
                .setUri("https://$host/${ids[i]}/playlist.m3u8")
                .setMediaId(ids[i])
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrElse(i) { "" }).build())
                .build()
        }
        p.setMediaItems(items, index, positionMs)

        p.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (old.mediaItemIndex == new.mediaItemIndex) return
                val oldId = old.mediaItem?.mediaId ?: return
                // A chapter that played through to the end starts from the top next time.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    Prefs.setWatched(this@PlayerActivity, courseId, oldId, true)
                } else {
                    Prefs.setPosition(this@PlayerActivity, oldId, old.positionMs)
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                index = p.currentMediaItemIndex
                item?.mediaId?.let { Prefs.setLastVideo(this@PlayerActivity, courseId, it) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Prefs.setWatched(this@PlayerActivity, courseId, ids[p.currentMediaItemIndex], true)
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Bundles can spread videos over several hosts: try the next one before giving up.
                index = p.currentMediaItemIndex
                positionMs = p.currentPosition.coerceAtLeast(0L)
                if (hostIndex + 1 < hosts.size) {
                    hostIndex++
                    startPlayer()
                } else {
                    Toast.makeText(this@PlayerActivity, "Couldn't play this video (${error.errorCodeName}).", Toast.LENGTH_LONG).show()
                }
            }
        })

        Prefs.setLastVideo(this, courseId, ids[index])
        Prefs.touchPlayed(this, courseId)
        p.playWhenReady = true
        p.prepare()
        view.player = p
        player = p
    }

    private fun savePosition() {
        val p = player ?: return
        index = p.currentMediaItemIndex
        positionMs = p.currentPosition.coerceAtLeast(0L)
        if (p.playbackState != Player.STATE_ENDED && p.playerError == null) {
            val duration = p.duration
            if (duration > 0 && positionMs >= duration * 9 / 10) {
                // Stopped in the last 10%: count the chapter as watched.
                Prefs.setWatched(this, courseId, ids[index], true)
                positionMs = 0L
            } else {
                Prefs.setPosition(this, ids[index], positionMs)
            }
        }
    }
}
