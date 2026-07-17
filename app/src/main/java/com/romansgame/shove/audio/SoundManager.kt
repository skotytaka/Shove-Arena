package com.romansgame.shove.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.romansgame.shove.profile.Prefs

/**
 * Fire-and-forget sound effects. Files are looked up by NAME in res/raw,
 * so missing files never break the build — they are simply silent.
 * Replace any sfx_*.wav in app/src/main/res/raw with your own to reskin.
 */
object SoundManager {

    const val CLICK = "sfx_click"
    const val SELECT = "sfx_select"
    const val MOVE = "sfx_move"
    const val PUSH = "sfx_push"
    const val FALL = "sfx_fall"
    const val PLACE = "sfx_place"
    const val ABILITY = "sfx_ability"
    const val MAGNET = "sfx_magnet"
    const val SWAP = "sfx_swap"
    const val ANCHOR = "sfx_anchor"
    const val WIN = "sfx_win"
    const val LOSE = "sfx_lose"

    private val ALL = listOf(
        CLICK, SELECT, MOVE, PUSH, FALL, PLACE,
        ABILITY, MAGNET, SWAP, ANCHOR, WIN, LOSE
    )

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var appCtx: Context? = null

    fun init(context: Context) {
        if (pool != null) return
        appCtx = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build()
        pool = p
        val res = context.resources
        for (name in ALL) {
            val resId = res.getIdentifier(name, "raw", context.packageName)
            if (resId != 0) ids[name] = p.load(context, resId, 1)
        }
    }

    fun play(name: String, volume: Float = 1f) {
        val ctx = appCtx ?: return
        if (!Prefs.sounds(ctx)) return
        val id = ids[name] ?: return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }
}
