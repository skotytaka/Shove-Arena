package com.romansgame.shove.audio

import android.content.Context
import android.media.MediaPlayer
import com.romansgame.shove.profile.Prefs

/**
 * Background music with drop-in support:
 * put files named `music_menu.mp3` and `music_game.mp3` (or .ogg) into
 * app/src/main/res/raw and rebuild — no code changes needed.
 * While the files are absent the game is simply silent.
 */
object MusicManager {

    private var player: MediaPlayer? = null
    private var currentResId = 0
    private var desired: Array<out String> = emptyArray()
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** Ask for a track by name; extra names are fallbacks (first that exists wins). */
    fun request(context: Context, vararg trackNames: String) {
        appCtx = context.applicationContext
        desired = trackNames
        if (!Prefs.music(context)) {
            stop()
            return
        }
        val resId = firstExisting(context, trackNames)
        if (resId == 0) {
            stop()
            return
        }
        if (resId == currentResId && player?.isPlaying == true) return
        stop()
        player = MediaPlayer.create(context, resId)?.apply {
            isLooping = true
            setVolume(0.55f, 0.55f)
            start()
        }
        currentResId = if (player != null) resId else 0
    }

    /** Re-evaluate after the music toggle changed in settings. */
    fun refresh(context: Context) {
        if (!Prefs.music(context)) stop()
        else if (desired.isNotEmpty()) request(context, *desired)
    }

    fun pause() {
        try {
            player?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    fun resume() {
        val ctx = appCtx ?: return
        if (!Prefs.music(ctx)) return
        try {
            player?.start() ?: if (desired.isNotEmpty()) request(ctx, *desired) else Unit
        } catch (_: IllegalStateException) {
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (_: IllegalStateException) {
        }
        player = null
        currentResId = 0
    }

    private fun firstExisting(context: Context, names: Array<out String>): Int {
        for (n in names) {
            val id = context.resources.getIdentifier(n, "raw", context.packageName)
            if (id != 0) return id
        }
        return 0
    }
}
