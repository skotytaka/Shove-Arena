package com.romansgame.shove.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.romansgame.shove.R
import com.romansgame.shove.core.WinMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Settings + player profiles (names & avatars).
 *
 * Avatars resolve in this order:
 *  1. an image the player picked in Settings (stored in filesDir),
 *  2. a drawable named `avatar_player1` / `avatar_player2` if you add one
 *     to app/src/main/res/drawable,
 *  3. a colored fallback circle drawn by the game.
 */
object Prefs {

    private const val FILE = "shove_prefs"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- names ----------------

    fun name(ctx: Context, player: Int): String {
        val def = ctx.getString(if (player == 1) R.string.default_p1 else R.string.default_p2)
        val v = sp(ctx).getString("name$player", null)
        return if (v.isNullOrBlank()) def else v
    }

    fun setName(ctx: Context, player: Int, value: String) {
        sp(ctx).edit().putString("name$player", value.trim()).apply()
    }

    // ---------------- game rules ----------------

    fun winMode(ctx: Context): WinMode =
        if (sp(ctx).getString("win_mode", "first") == "last") WinMode.LAST_STANDING
        else WinMode.FIRST_BLOOD

    fun setWinMode(ctx: Context, mode: WinMode) {
        sp(ctx).edit().putString("win_mode", if (mode == WinMode.LAST_STANDING) "last" else "first").apply()
    }

    fun pieces(ctx: Context): Int = sp(ctx).getInt("pieces", 2).coerceIn(2, 3)

    fun setPieces(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("pieces", value.coerceIn(2, 3)).apply()
    }

    // ---------------- toggles ----------------

    fun sounds(ctx: Context) = sp(ctx).getBoolean("sounds", true)
    fun setSounds(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("sounds", v).apply()

    fun music(ctx: Context) = sp(ctx).getBoolean("music", true)
    fun setMusic(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("music", v).apply()

    fun vibration(ctx: Context) = sp(ctx).getBoolean("vibration", true)
    fun setVibration(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("vibration", v).apply()

    // ---------------- avatars ----------------

    private fun avatarFile(ctx: Context, player: Int) = File(ctx.filesDir, "avatar_p$player.png")

    fun loadAvatar(ctx: Context, player: Int): Bitmap? {
        val f = avatarFile(ctx, player)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        }
        val resId = ctx.resources.getIdentifier("avatar_player$player", "drawable", ctx.packageName)
        if (resId != 0) {
            return BitmapFactory.decodeResource(ctx.resources, resId)
        }
        return null
    }

    fun clearAvatar(ctx: Context, player: Int) {
        avatarFile(ctx, player).delete()
    }

    /** Copies the picked image into app storage as a 256px center-cropped PNG. */
    fun saveAvatarFromUri(ctx: Context, player: Int, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return false
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            val target = 256
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val side = min(raw.width, raw.height)
            val x = (raw.width - side) / 2
            val y = (raw.height - side) / 2
            val square = Bitmap.createBitmap(raw, x, y, side, side)
            val scaled = Bitmap.createScaledBitmap(square, target, target, true)

            FileOutputStream(avatarFile(ctx, player)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            if (square !== raw) raw.recycle()
            if (scaled !== square) square.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }
}
