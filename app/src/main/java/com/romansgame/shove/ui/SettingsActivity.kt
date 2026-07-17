package com.romansgame.shove.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.romansgame.shove.R
import com.romansgame.shove.audio.MusicManager
import com.romansgame.shove.audio.SoundManager
import com.romansgame.shove.core.WinMode
import com.romansgame.shove.profile.Prefs

class SettingsActivity : AppCompatActivity() {

    private var initializing = true
    private var pendingAvatarPlayer = 0

    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val player = pendingAvatarPlayer
            pendingAvatarPlayer = 0
            if (uri != null && player != 0) {
                if (Prefs.saveAvatarFromUri(this, player, uri)) {
                    SoundManager.play(SoundManager.PLACE)
                    refreshAvatars()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupNames()
        setupAvatars()
        setupGameOptions()
        setupLanguage()
        setupToggles()
        initializing = false
    }

    override fun onResume() {
        super.onResume()
        MusicManager.request(this, "music_menu")
    }

    private fun setupNames() {
        val name1 = findViewById<EditText>(R.id.name1)
        val name2 = findViewById<EditText>(R.id.name2)
        name1.setText(Prefs.name(this, 1))
        name2.setText(Prefs.name(this, 2))
        name1.doAfterTextChanged { if (!initializing) Prefs.setName(this, 1, it?.toString() ?: "") }
        name2.doAfterTextChanged { if (!initializing) Prefs.setName(this, 2, it?.toString() ?: "") }
    }

    private fun setupAvatars() {
        val a1 = findViewById<ImageView>(R.id.avatar1)
        val a2 = findViewById<ImageView>(R.id.avatar2)
        refreshAvatars()
        val pick = { player: Int ->
            pendingAvatarPlayer = player
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        a1.setOnClickListener { pick(1) }
        a2.setOnClickListener { pick(2) }
        a1.setOnLongClickListener { Prefs.clearAvatar(this, 1); refreshAvatars(); true }
        a2.setOnLongClickListener { Prefs.clearAvatar(this, 2); refreshAvatars(); true }
    }

    private fun refreshAvatars() {
        val a1 = findViewById<ImageView>(R.id.avatar1)
        val a2 = findViewById<ImageView>(R.id.avatar2)
        val b1 = Prefs.loadAvatar(this, 1)
        val b2 = Prefs.loadAvatar(this, 2)
        if (b1 != null) a1.setImageBitmap(b1) else a1.setImageResource(R.drawable.avatar_placeholder_red)
        if (b2 != null) a2.setImageBitmap(b2) else a2.setImageResource(R.drawable.avatar_placeholder_blue)
    }

    private fun setupGameOptions() {
        val rgWin = findViewById<RadioGroup>(R.id.rg_win)
        rgWin.check(
            if (Prefs.winMode(this) == WinMode.LAST_STANDING) R.id.rb_last else R.id.rb_first_blood
        )
        rgWin.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            SoundManager.play(SoundManager.CLICK)
            Prefs.setWinMode(
                this,
                if (checkedId == R.id.rb_last) WinMode.LAST_STANDING else WinMode.FIRST_BLOOD
            )
        }

        val rgPieces = findViewById<RadioGroup>(R.id.rg_pieces)
        rgPieces.check(if (Prefs.pieces(this) == 3) R.id.rb_pieces3 else R.id.rb_pieces2)
        rgPieces.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            SoundManager.play(SoundManager.CLICK)
            Prefs.setPieces(this, if (checkedId == R.id.rb_pieces3) 3 else 2)
        }
    }

    private fun setupLanguage() {
        val rg = findViewById<RadioGroup>(R.id.rg_lang)
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        rg.check(
            when {
                tags.startsWith("uk") -> R.id.rb_lang_uk
                tags.startsWith("en") -> R.id.rb_lang_en
                else -> R.id.rb_lang_system
            }
        )
        rg.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            SoundManager.play(SoundManager.CLICK)
            val locales = when (checkedId) {
                R.id.rb_lang_uk -> LocaleListCompat.forLanguageTags("uk")
                R.id.rb_lang_en -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun setupToggles() {
        val swSounds = findViewById<MaterialSwitch>(R.id.sw_sounds)
        val swMusic = findViewById<MaterialSwitch>(R.id.sw_music)
        val swVibro = findViewById<MaterialSwitch>(R.id.sw_vibration)
        swSounds.isChecked = Prefs.sounds(this)
        swMusic.isChecked = Prefs.music(this)
        swVibro.isChecked = Prefs.vibration(this)
        swSounds.setOnCheckedChangeListener { _, v ->
            Prefs.setSounds(this, v)
            SoundManager.play(SoundManager.CLICK)
        }
        swMusic.setOnCheckedChangeListener { _, v ->
            Prefs.setMusic(this, v)
            MusicManager.refresh(this)
        }
        swVibro.setOnCheckedChangeListener { _, v -> Prefs.setVibration(this, v) }
    }
}
