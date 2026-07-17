package com.romansgame.shove.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.romansgame.shove.R
import com.romansgame.shove.audio.MusicManager
import com.romansgame.shove.audio.SoundManager
import com.romansgame.shove.core.Balance
import com.romansgame.shove.core.BotAction
import com.romansgame.shove.core.BotPlayer
import com.romansgame.shove.core.GameConfig
import com.romansgame.shove.core.GameEngine
import com.romansgame.shove.core.Phase
import com.romansgame.shove.profile.Prefs

class GameActivity : AppCompatActivity(), GameView.Listener {

    companion object {
        private const val EXTRA_VS_BOT = "vs_bot"
        private const val EXTRA_BOT_LEVEL = "bot_level"

        fun intent(ctx: Context, vsBot: Boolean, botLevel: Int = 1): Intent =
            Intent(ctx, GameActivity::class.java)
                .putExtra(EXTRA_VS_BOT, vsBot)
                .putExtra(EXTRA_BOT_LEVEL, botLevel)
    }

    private lateinit var gameView: GameView
    private val handler = Handler(Looper.getMainLooper())

    private var engine = GameEngine(GameConfig())
    private var vsBot = false
    private var botLevel = 1
    private var bot: BotPlayer? = null
    private var botBusy = false
    private var startingPlayer = 1
    private val score = intArrayOf(0, 0, 0)
    private var gameOverShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vsBot = intent.getBooleanExtra(EXTRA_VS_BOT, false)
        botLevel = intent.getIntExtra(EXTRA_BOT_LEVEL, 1)

        val root = FrameLayout(this)
        gameView = GameView(this)
        gameView.listener = this
        root.addView(gameView)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExit()
            }
        })

        newGame()
    }

    override fun onResume() {
        super.onResume()
        MusicManager.request(this, "music_game", "music_menu")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun playerName(p: Int): String =
        if (vsBot && p == 2) getString(R.string.bot_name) else Prefs.name(this, p)

    private fun newGame() {
        engine = GameEngine(
            GameConfig(
                piecesPerPlayer = Prefs.pieces(this),
                winMode = Prefs.winMode(this),
                startingPlayer = startingPlayer
            )
        )
        bot = if (vsBot) BotPlayer(botLevel) else null
        botBusy = false
        gameOverShown = false
        val avatars = arrayOf(
            null,
            Prefs.loadAvatar(this, 1),
            if (vsBot) null else Prefs.loadAvatar(this, 2)
        )
        gameView.bind(
            engine,
            arrayOf("", playerName(1), playerName(2)),
            avatars
        ) { p -> vsBot && p == 2 }
        handler.post { maybeBotTurn() }
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_text)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ------------------------------------------------------------------
    // GameView.Listener
    // ------------------------------------------------------------------

    override fun onReadyForNextTurn() {
        maybeBotTurn()
    }

    override fun onGameOver(winner: Int) {
        if (gameOverShown) return
        gameOverShown = true
        if (winner == 1 || winner == 2) score[winner]++
        val humanWon = winner != 0 && !(vsBot && winner == 2)
        SoundManager.play(if (humanWon) SoundManager.WIN else SoundManager.LOSE)
        val title =
            if (winner == 0) getString(R.string.msg_draw)
            else getString(R.string.msg_won, playerName(winner))
        val message = getString(
            R.string.score_fmt,
            playerName(1), score[1], score[2], playerName(2)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_rematch) { _, _ ->
                startingPlayer = 3 - startingPlayer
                newGame()
            }
            .setNegativeButton(R.string.btn_menu) { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------------------
    // Bot driving
    // ------------------------------------------------------------------

    private fun maybeBotTurn() {
        val b = bot ?: return
        if (botBusy || engine.phase == Phase.OVER || engine.current != 2) return
        botBusy = true
        if (engine.phase == Phase.SETUP) {
            handler.postDelayed({
                if (!isFinishing && engine.phase == Phase.SETUP && engine.current == 2) {
                    gameView.botAct(BotAction.Place(b.choosePlacement(engine)))
                }
                botBusy = false
            }, Balance.BOT_FIRST_DELAY_MS)
        } else {
            Thread {
                val plan = try {
                    b.chooseTurn(engine.copyState())
                } catch (t: Throwable) {
                    emptyList()
                }
                // A turn is never more than two actions — hard cap as a safety net.
                handler.post { executePlan(plan.take(2), 0) }
            }.start()
        }
    }

    private fun executePlan(plan: List<BotAction>, idx: Int) {
        if (isFinishing || isDestroyed) return
        if (idx >= plan.size || engine.phase == Phase.OVER) {
            botBusy = false
            return
        }
        handler.postDelayed({
            when {
                isFinishing || isDestroyed -> {}
                engine.phase == Phase.OVER -> botBusy = false
                gameView.busy -> executePlan(plan, idx)   // animations still running
                else -> {
                    val ok = gameView.botAct(plan[idx])
                    if (!ok) {
                        // never leave the bot stuck mid-turn
                        if (engine.phase == Phase.PLAY && engine.current == 2 && engine.actionsDone == 1) {
                            gameView.botAct(BotAction.Skip)
                        }
                        botBusy = false
                    } else {
                        executePlan(plan, idx + 1)
                    }
                }
            }
        }, if (idx == 0) Balance.BOT_FIRST_DELAY_MS else Balance.BOT_ACTION_DELAY_MS)
    }
}
