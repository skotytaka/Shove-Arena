package com.romansgame.shove.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.romansgame.shove.R
import com.romansgame.shove.audio.MusicManager
import com.romansgame.shove.audio.SoundManager

class MainMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        val root = findViewById<View>(R.id.menu_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<View>(R.id.btn_two_players).setOnClickListener {
            SoundManager.play(SoundManager.CLICK)
            startActivity(GameActivity.intent(this, vsBot = false))
        }
        findViewById<View>(R.id.btn_vs_bot).setOnClickListener {
            SoundManager.play(SoundManager.CLICK)
            pickDifficulty()
        }
        findViewById<View>(R.id.btn_rules).setOnClickListener {
            SoundManager.play(SoundManager.CLICK)
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            SoundManager.play(SoundManager.CLICK)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        animateEntry()
    }

    override fun onResume() {
        super.onResume()
        MusicManager.request(this, "music_menu")
    }

    private fun pickDifficulty() {
        val items = arrayOf(
            getString(R.string.diff_easy),
            getString(R.string.diff_medium),
            getString(R.string.diff_hard)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.difficulty_title)
            .setItems(items) { _, which ->
                startActivity(GameActivity.intent(this, vsBot = true, botLevel = which))
            }
            .show()
    }

    private fun animateEntry() {
        val ids = intArrayOf(
            R.id.logo_view, R.id.menu_title, R.id.menu_subtitle,
            R.id.btn_two_players, R.id.btn_vs_bot, R.id.btn_rules, R.id.btn_settings
        )
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            v.alpha = 0f
            v.translationY = 60f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L * i)
                .setDuration(360L)
                .start()
        }
    }
}
