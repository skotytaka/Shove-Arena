package com.romansgame.shove.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import com.romansgame.shove.R
import com.romansgame.shove.audio.SoundManager
import com.romansgame.shove.core.Ability
import com.romansgame.shove.core.AbilityType
import com.romansgame.shove.core.ActionResult
import com.romansgame.shove.core.Balance
import com.romansgame.shove.core.BotAction
import com.romansgame.shove.core.Cell
import com.romansgame.shove.core.Direction
import com.romansgame.shove.core.GameConfig
import com.romansgame.shove.core.GameEngine
import com.romansgame.shove.core.GameEvent
import com.romansgame.shove.core.Msg
import com.romansgame.shove.core.Phase
import com.romansgame.shove.profile.Prefs
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole game screen: board, HUD, ability bar — custom drawn,
 * with tween animations driven from the engine's event stream.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** Fired whenever all animations finished and the game expects input. */
        fun onReadyForNextTurn()

        /** Fired once after the final animation of a decided game. */
        fun onGameOver(winner: Int)
    }

    var listener: Listener? = null

    var engine = GameEngine(GameConfig())
        private set

    private var names = arrayOf("", "P1", "P2")
    private var avatars = arrayOfNulls<Bitmap>(3)
    private var botControlled: (Int) -> Boolean = { false }

    // ------------------------------------------------------------------
    // Colors / paints
    // ------------------------------------------------------------------

    private fun col(id: Int) = ContextCompat.getColor(context, id)

    private val cBg = col(R.color.cream)
    private val cPanel = col(R.color.cream_dark)
    private val cBoard = col(R.color.wood)
    private val cBoardDark = col(R.color.wood_dark)
    private val cCell = col(R.color.cell_tan)
    private val cP1 = col(R.color.p1_red)
    private val cP2 = col(R.color.p2_blue)
    private val cGold = col(R.color.gold)
    private val cText = col(R.color.text_main)
    private val cTextSoft = col(R.color.text_soft)
    private val cHintGreen = col(R.color.hint_green)
    private val cHintOrange = col(R.color.hint_orange)
    private val cDisabled = col(R.color.disabled_gray)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardSide = 0f
    private var cellSize = 0f
    private var gutter = 0f
    private val hudRect = RectF()
    private var msgBaseline = 0f
    private val abilityRects = Array(4) { RectF() }
    private val actionBtnRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBoard()
        // snap idle sprites to their cells
        for (p in engine.pieces) {
            val spr = sprites[p.id] ?: continue
            if (p.alive) {
                val c = cellCenter(p.cell)
                spr.x = c.x; spr.y = c.y
            }
        }
    }

    private fun layoutBoard() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = dp(10f)
        val vgap = dp(8f)
        val hudH = dp(72f)
        val msgH = dp(30f)
        gutter = dp(28f)
        val abilityH = dp(80f)
        val actionH = dp(46f)

        var top = paddingTop + pad
        hudRect.set(paddingLeft + pad, top, w - paddingRight - pad, top + hudH)
        top += hudH + vgap
        msgBaseline = top + msgH * 0.7f
        top += msgH + vgap

        val maxW = w - paddingLeft - paddingRight - 2 * (pad + gutter)
        val bottomNeed = abilityH + actionH + 3 * vgap + gutter + pad + paddingBottom
        val maxH = h - (top + gutter) - bottomNeed
        boardSide = min(maxW, maxH).coerceAtLeast(dp(120f))
        boardLeft = paddingLeft + (w - paddingLeft - paddingRight - boardSide) / 2f
        boardTop = top + gutter
        cellSize = boardSide / engine.size

        val abilityTop = boardTop + boardSide + gutter + vgap
        val btnGap = dp(8f)
        val totalW = w - paddingLeft - paddingRight - 2 * pad
        val btnW = (totalW - 3 * btnGap) / 4f
        for (i in 0 until 4) {
            val l = paddingLeft + pad + i * (btnW + btnGap)
            abilityRects[i].set(l, abilityTop, l + btnW, abilityTop + abilityH)
        }
        val abW = totalW * 0.6f
        val abL = paddingLeft + pad + (totalW - abW) / 2f
        actionBtnRect.set(abL, abilityTop + abilityH + vgap, abL + abW, abilityTop + abilityH + vgap + actionH)
    }

    private fun cellCenter(cell: Cell) = PointF(
        boardLeft + (cell.col + 0.5f) * cellSize,
        boardTop + (cell.row + 0.5f) * cellSize
    )

    private fun cellAt(x: Float, y: Float): Cell? {
        if (x < boardLeft || y < boardTop) return null
        val c = ((x - boardLeft) / cellSize).toInt()
        val r = ((y - boardTop) / cellSize).toInt()
        return if (r in 0 until engine.size && c in 0 until engine.size) Cell(r, c) else null
    }

    // ------------------------------------------------------------------
    // Sprites & animation machinery
    // ------------------------------------------------------------------

    private class Sprite(val pieceId: Int, val player: Int) {
        var x = 0f
        var y = 0f
        var scale = 1f
        var alpha = 1f
        var rot = 0f
        var visible = false
    }

    private val sprites = HashMap<Int, Sprite>()

    private fun obtainSprite(id: Int, player: Int): Sprite =
        sprites.getOrPut(id) { Sprite(id, player) }

    private abstract inner class Anim(val duration: Long) {
        var startTime = -1L
        fun ease(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }
        open fun onStart() {}
        abstract fun apply(tRaw: Float)
        open fun onEnd() {}
    }

    private inner class MoveAnim(val spr: Sprite, val from: PointF, val to: PointF) :
        Anim(Balance.ANIM_MOVE_MS) {
        override fun onStart() { spr.visible = true }
        override fun apply(tRaw: Float) {
            val t = ease(tRaw)
            spr.x = from.x + (to.x - from.x) * t
            spr.y = from.y + (to.y - from.y) * t
        }
    }

    private inner class PlaceAnim(val spr: Sprite, val at: PointF) :
        Anim(Balance.ANIM_PLACE_MS) {
        override fun onStart() {
            spr.x = at.x; spr.y = at.y
            spr.visible = true; spr.scale = 0.2f
        }
        override fun apply(tRaw: Float) {
            val t = ease(tRaw)
            spr.scale = (0.2f + 0.8f * t) * (1f + 0.15f * sin(t * PI).toFloat())
        }
        override fun onEnd() { spr.scale = 1f }
    }

    private inner class FallAnim(val spr: Sprite, val from: PointF, val dir: Direction) :
        Anim(Balance.ANIM_FALL_MS) {
        override fun apply(tRaw: Float) {
            spr.x = from.x + dir.dc * cellSize * 1.4f * tRaw
            spr.y = from.y + dir.dr * cellSize * 1.4f * tRaw
            spr.rot = 300f * tRaw * (if (spr.pieceId % 2 == 0) 1 else -1)
            spr.alpha = 1f - tRaw * tRaw
            spr.scale = 1f - 0.55f * tRaw
        }
        override fun onEnd() {
            spr.visible = false
            spr.alpha = 1f; spr.scale = 1f; spr.rot = 0f
            burst(
                from.x + dir.dc * cellSize * 1.2f,
                from.y + dir.dr * cellSize * 1.2f,
                if (spr.player == 1) cP1 else cP2
            )
        }
    }

    private inner class SwapAnim(
        val spr: Sprite, val from: PointF, val to: PointF, val lift: Float
    ) : Anim(Balance.ANIM_SWAP_MS) {
        override fun onStart() { spr.visible = true }
        override fun apply(tRaw: Float) {
            val t = ease(tRaw)
            val arc = sin(t * PI).toFloat()
            spr.x = from.x + (to.x - from.x) * t
            spr.y = from.y + (to.y - from.y) * t - lift * arc
            spr.scale = 1f + 0.18f * arc
        }
        override fun onEnd() { spr.scale = 1f }
    }

    private inner class PulseAnim(val spr: Sprite) : Anim(320L) {
        override fun apply(tRaw: Float) {
            spr.scale = 1f + 0.3f * sin(tRaw * PI).toFloat()
        }
        override fun onEnd() { spr.scale = 1f }
    }

    private class Scene(val anims: List<Anim>, val sounds: List<String>, val haptic: Int?)

    private val sceneQueue = ArrayDeque<Scene>()
    private var activeScene: Scene? = null
    private var pendingGameOver = false

    val busy: Boolean get() = activeScene != null || sceneQueue.isNotEmpty()

    private fun pump(now: Long) {
        if (activeScene == null) {
            val next = sceneQueue.removeFirstOrNull() ?: return
            next.anims.forEach { it.startTime = now; it.onStart() }
            next.sounds.forEach { SoundManager.play(it) }
            next.haptic?.let { if (Prefs.vibration(context)) performHapticFeedback(it) }
            activeScene = next
        }
        val scene = activeScene ?: return
        var allDone = true
        for (a in scene.anims) {
            val t = ((now - a.startTime).toFloat() / a.duration).coerceIn(0f, 1f)
            a.apply(t)
            if (t < 1f) allDone = false
        }
        if (allDone) {
            scene.anims.forEach { it.onEnd() }
            activeScene = null
            if (sceneQueue.isEmpty()) post { onQueueEmpty() } else pump(now)
        }
    }

    private fun onQueueEmpty() {
        if (pendingGameOver) {
            pendingGameOver = false
            startConfetti()
            listener?.onGameOver(engine.winner)
        } else {
            listener?.onReadyForNextTurn()
        }
        invalidate()
    }

    private fun enqueueEvents(events: List<GameEvent>) {
        var anims = ArrayList<Anim>()
        var sounds = LinkedHashSet<String>()
        var haptic: Int? = null

        fun flush() {
            if (anims.isNotEmpty() || sounds.isNotEmpty()) {
                sceneQueue.add(Scene(anims, sounds.toList(), haptic))
                anims = ArrayList()
                sounds = LinkedHashSet()
                haptic = null
            }
        }

        for (ev in events) when (ev) {
            is GameEvent.Placed -> {
                val spr = obtainSprite(ev.pieceId, ev.player)
                anims.add(PlaceAnim(spr, cellCenter(ev.at)))
                sounds.add(SoundManager.PLACE)
                flush()
            }
            is GameEvent.Moved -> {
                val spr = sprites[ev.pieceId] ?: continue
                anims.add(MoveAnim(spr, cellCenter(ev.from), cellCenter(ev.to)))
                if (ev.push) {
                    sounds.add(SoundManager.PUSH)
                    haptic = HapticFeedbackConstants.KEYBOARD_TAP
                } else {
                    sounds.add(SoundManager.MOVE)
                    flush()
                }
            }
            is GameEvent.Fell -> {
                val spr = sprites[ev.pieceId] ?: continue
                anims.add(FallAnim(spr, cellCenter(ev.from), ev.dir))
                sounds.add(SoundManager.FALL)
                haptic = HapticFeedbackConstants.LONG_PRESS
            }
            is GameEvent.Swapped -> {
                val a = sprites[ev.idA] ?: continue
                val b = sprites[ev.idB] ?: continue
                anims.add(SwapAnim(a, cellCenter(ev.fromA), cellCenter(ev.fromB), cellSize * 0.5f))
                anims.add(SwapAnim(b, cellCenter(ev.fromB), cellCenter(ev.fromA), -cellSize * 0.5f))
                flush()
            }
            is GameEvent.Anchored -> {
                sprites[ev.pieceId]?.let { anims.add(PulseAnim(it)) }
                flush()
            }
            is GameEvent.AbilityUsed -> sounds.add(
                when (ev.type) {
                    AbilityType.SHIFT -> SoundManager.SHIFT
                    AbilityType.SWAP -> SoundManager.SWAP
                    AbilityType.ANCHOR -> SoundManager.ANCHOR
                    else -> SoundManager.ABILITY
                }
            )
            is GameEvent.TurnStarted -> flush()
            is GameEvent.TurnAutoSkipped ->
                showTransient(context.getString(R.string.msg_turn_skipped, name(ev.player)))
            is GameEvent.GameOver -> {
                flush()
                pendingGameOver = true
            }
        }
        flush()
        if (activeScene == null && sceneQueue.isEmpty()) {
            post { onQueueEmpty() }
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------
    // Particles
    // ------------------------------------------------------------------

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val color: Int, val size: Float, var life: Float, val maxLife: Float,
        val square: Boolean, var rot: Float, val vrot: Float
    )

    private val particles = ArrayList<Particle>()
    private val rnd = java.util.Random()

    private fun burst(x: Float, y: Float, color: Int) {
        repeat(14) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = dp(40f) + rnd.nextFloat() * dp(120f)
            particles.add(
                Particle(
                    x, y,
                    kotlin.math.cos(ang) * speed, kotlin.math.sin(ang) * speed - dp(40f),
                    color, dp(3f) + rnd.nextFloat() * dp(3f),
                    0.7f, 0.7f, false, 0f, 0f
                )
            )
        }
        postInvalidateOnAnimation()
    }

    private fun startConfetti() {
        val colors = intArrayOf(cP1, cP2, cGold, cHintGreen, cBoard)
        repeat(110) {
            particles.add(
                Particle(
                    rnd.nextFloat() * width,
                    -rnd.nextFloat() * height * 0.3f,
                    (rnd.nextFloat() - 0.5f) * dp(60f),
                    dp(80f) + rnd.nextFloat() * dp(160f),
                    colors[rnd.nextInt(colors.size)],
                    dp(4f) + rnd.nextFloat() * dp(4f),
                    2.4f, 2.4f, true,
                    rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 480f
                )
            )
        }
        postInvalidateOnAnimation()
    }

    private var lastFrame = 0L

    private fun updateParticles(now: Long) {
        if (particles.isEmpty()) { lastFrame = now; return }
        val dt = ((now - lastFrame).coerceIn(0L, 50L)) / 1000f
        lastFrame = now
        val g = dp(340f)
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vy += g * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vrot * dt
            p.life -= dt
            if (p.life <= 0f || p.y > height + dp(20f)) it.remove()
        }
    }

    // ------------------------------------------------------------------
    // Interaction state
    // ------------------------------------------------------------------

    private enum class Mode { NORMAL, DASH_PIECE, DASH_TARGET, SWAP_FIRST, SWAP_SECOND, SHIFT, ANCHOR }

    private var mode = Mode.NORMAL
    private var selected: Cell? = null
    private var swapFirst: Cell? = null
    private var hintCells: List<Cell> = emptyList()

    private var transientText: String? = null
    private var transientUntil = 0L

    private fun showTransient(text: String) {
        transientText = text
        transientUntil = AnimationUtils.currentAnimationTimeMillis() + 2000L
        invalidate()
    }

    private fun name(p: Int) = names.getOrElse(p) { "?" }

    fun bind(
        engine: GameEngine,
        names: Array<String>,
        avatars: Array<Bitmap?>,
        botControlled: (Int) -> Boolean
    ) {
        this.engine = engine
        this.names = names
        this.avatars = avatars
        this.botControlled = botControlled
        sprites.clear()
        sceneQueue.clear()
        activeScene = null
        particles.clear()
        pendingGameOver = false
        mode = Mode.NORMAL
        selected = null; swapFirst = null; hintCells = emptyList()
        transientText = null
        layoutBoard()
        for (p in engine.pieces) if (p.alive) {
            val spr = obtainSprite(p.id, p.player)
            val c = cellCenter(p.cell)
            spr.x = c.x; spr.y = c.y; spr.visible = true
        }
        invalidate()
    }

    /** Applies a bot decision with the same animations a human action gets. */
    fun botAct(action: BotAction): Boolean {
        val r: ActionResult = when (action) {
            is BotAction.Place -> engine.place(action.at)
            is BotAction.Move -> engine.tryMove(action.from, action.to)
            is BotAction.Dash -> engine.dash(action.from, action.to)
            is BotAction.Swap -> engine.swap(action.a, action.b)
            is BotAction.Shift -> engine.shift(action.isRow, action.index, action.dir)
            is BotAction.Anchor -> engine.anchor(action.at)
            BotAction.Skip -> engine.skipSecondAction()
        }
        if (r.ok) enqueueEvents(r.events)
        invalidate()
        return r.ok
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            handleTap(event.x, event.y)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (engine.phase == Phase.OVER) return
        if (busy) return
        if (botControlled(engine.current)) return

        for (i in 0 until 4) {
            if (abilityRects[i].contains(x, y)) {
                onAbilityTapped(AbilityType.entries[i])
                return
            }
        }
        if (actionButtonLabel() != null && actionBtnRect.contains(x, y)) {
            onActionButton()
            return
        }
        if (mode == Mode.SHIFT) {
            arrowAt(x, y)?.let { (isRow, index, dir) ->
                applyResult(engine.shift(isRow, index, dir))
                return
            }
        }
        val cell = cellAt(x, y) ?: return
        when (mode) {
            Mode.NORMAL -> tapNormal(cell)
            Mode.DASH_PIECE -> tapDashPiece(cell)
            Mode.DASH_TARGET -> tapDashTarget(cell)
            Mode.SWAP_FIRST -> tapSwapFirst(cell)
            Mode.SWAP_SECOND -> tapSwapSecond(cell)
            Mode.ANCHOR -> tapAnchor(cell)
            Mode.SHIFT -> { /* handled above */ }
        }
        invalidate()
    }

    private fun tapNormal(cell: Cell) {
        if (engine.phase == Phase.SETUP) {
            applyResult(engine.place(cell))
            return
        }
        val piece = engine.pieceAt(cell)
        val sel = selected
        if (sel == null) {
            when {
                piece == null -> {}
                piece.player != engine.current -> showErr(Msg.NOT_YOUR_PIECE)
                engine.legalMoveTargets(cell).isEmpty() -> showErr(Msg.ALREADY_MOVED)
                else -> {
                    selected = cell
                    hintCells = engine.legalMoveTargets(cell)
                    SoundManager.play(SoundManager.SELECT)
                }
            }
            return
        }
        if (cell == sel) {
            selected = null; hintCells = emptyList()
            return
        }
        if (piece != null && piece.player == engine.current &&
            engine.legalMoveTargets(cell).isNotEmpty()
        ) {
            selected = cell
            hintCells = engine.legalMoveTargets(cell)
            SoundManager.play(SoundManager.SELECT)
            return
        }
        applyResult(engine.tryMove(sel, cell))
    }

    private fun tapDashPiece(cell: Cell) {
        val piece = engine.pieceAt(cell) ?: return
        if (piece.player != engine.current) { showErr(Msg.NOT_YOUR_PIECE); return }
        val targets = engine.dashTargets(cell)
        if (targets.isEmpty()) { showErr(Msg.ALREADY_MOVED); return }
        selected = cell
        hintCells = targets
        mode = Mode.DASH_TARGET
        SoundManager.play(SoundManager.SELECT)
    }

    private fun tapDashTarget(cell: Cell) {
        val sel = selected ?: return
        if (cell == sel) { mode = Mode.DASH_PIECE; selected = null; hintCells = dashablePieces(); return }
        val piece = engine.pieceAt(cell)
        if (piece != null && piece.player == engine.current && engine.dashTargets(cell).isNotEmpty()) {
            selected = cell
            hintCells = engine.dashTargets(cell)
            SoundManager.play(SoundManager.SELECT)
            return
        }
        if (cell in hintCells) applyResult(engine.dash(sel, cell))
    }

    private fun tapSwapFirst(cell: Cell) {
        if (engine.pieceAt(cell) == null) return
        swapFirst = cell
        mode = Mode.SWAP_SECOND
        SoundManager.play(SoundManager.SELECT)
    }

    private fun tapSwapSecond(cell: Cell) {
        val first = swapFirst ?: return
        if (cell == first) { swapFirst = null; mode = Mode.SWAP_FIRST; return }
        if (engine.pieceAt(cell) == null) return
        if (!applyResult(engine.swap(first, cell))) {
            swapFirst = null
            mode = Mode.SWAP_FIRST
        }
    }

    private fun tapAnchor(cell: Cell) {
        val piece = engine.pieceAt(cell) ?: return
        if (piece.player != engine.current) { showErr(Msg.NOT_YOUR_PIECE); return }
        applyResult(engine.anchor(cell))
    }

    private fun dashablePieces(): List<Cell> =
        engine.alive(engine.current).map { it.cell }.filter { engine.dashTargets(it).isNotEmpty() }

    private fun onAbilityTapped(type: AbilityType) {
        SoundManager.play(SoundManager.CLICK)
        val gate = engine.abilityGateMsg(type)
        if (gate != null) { showErr(gate); return }
        selected = null; swapFirst = null
        when (type) {
            AbilityType.DASH -> { mode = Mode.DASH_PIECE; hintCells = dashablePieces() }
            AbilityType.SWAP -> { mode = Mode.SWAP_FIRST; hintCells = engine.pieces.filter { it.alive && !it.anchored }.map { it.cell } }
            AbilityType.SHIFT -> { mode = Mode.SHIFT; hintCells = emptyList() }
            AbilityType.ANCHOR -> { mode = Mode.ANCHOR; hintCells = engine.alive(engine.current).map { it.cell } }
        }
        invalidate()
    }

    private fun onActionButton() {
        SoundManager.play(SoundManager.CLICK)
        if (mode != Mode.NORMAL) {
            mode = Mode.NORMAL
            selected = null; swapFirst = null; hintCells = emptyList()
            invalidate()
            return
        }
        if (engine.actionsDone == 1) applyResult(engine.skipSecondAction())
    }

    private fun applyResult(r: ActionResult): Boolean {
        if (!r.ok) {
            r.msg?.let { showErr(it) }
            return false
        }
        mode = Mode.NORMAL
        selected = null; swapFirst = null; hintCells = emptyList()
        enqueueEvents(r.events)
        invalidate()
        return true
    }

    private fun showErr(msg: Msg) {
        showTransient(msgText(msg))
    }

    private fun msgText(msg: Msg): String = context.getString(
        when (msg) {
            Msg.CELL_OCCUPIED -> R.string.err_cell_occupied
            Msg.CENTER_FORBIDDEN -> R.string.err_center
            Msg.EDGE_FORBIDDEN -> R.string.err_edge
            Msg.OPPOSITE_FORBIDDEN -> R.string.err_opposite
            Msg.NO_PIECE -> R.string.err_no_piece
            Msg.NOT_YOUR_PIECE -> R.string.err_not_yours
            Msg.ALREADY_MOVED -> R.string.err_already_moved
            Msg.ONLY_ONE_STEP -> R.string.err_one_step
            Msg.BLOCKED_BY_ANCHOR -> R.string.err_anchor
            Msg.ABILITY_SECOND_ONLY -> R.string.err_ability_second
            Msg.ABILITY_NOT_READY -> R.string.err_ability_cd
            Msg.NOTHING_TO_SHIFT -> R.string.err_nothing_shift
            Msg.SWAP_NEEDS_OWN -> R.string.err_swap_own
            else -> R.string.err_not_allowed
        }
    )

    private fun statusText(): String {
        val now = AnimationUtils.currentAnimationTimeMillis()
        transientText?.let { if (now < transientUntil) return it else transientText = null }
        return when {
            engine.phase == Phase.OVER ->
                if (engine.winner == 0) context.getString(R.string.msg_draw)
                else context.getString(R.string.msg_won, name(engine.winner))
            botControlled(engine.current) -> context.getString(R.string.bot_thinking)
            engine.phase == Phase.SETUP -> {
                if (engine.legalPlacements().relaxed) context.getString(R.string.msg_place_relaxed)
                else context.getString(R.string.msg_place, name(engine.current), engine.toPlace(engine.current))
            }
            mode == Mode.DASH_PIECE -> context.getString(R.string.hint_dash_piece)
            mode == Mode.DASH_TARGET -> context.getString(R.string.hint_dash_target)
            mode == Mode.SWAP_FIRST -> context.getString(R.string.hint_swap_first)
            mode == Mode.SWAP_SECOND -> context.getString(R.string.hint_swap_second)
            mode == Mode.SHIFT -> context.getString(R.string.hint_shift)
            mode == Mode.ANCHOR -> context.getString(R.string.hint_anchor)
            engine.actionsDone == 0 -> context.getString(R.string.msg_first_action, name(engine.current))
            else -> context.getString(R.string.msg_second_action, name(engine.current))
        }
    }

    private fun actionButtonLabel(): String? = when {
        engine.phase != Phase.PLAY -> null
        botControlled(engine.current) -> null
        mode != Mode.NORMAL -> context.getString(R.string.btn_cancel)
        engine.actionsDone == 1 -> context.getString(R.string.btn_skip)
        else -> null
    }

    // ------------------------------------------------------------------
    // Shift arrows
    // ------------------------------------------------------------------

    private fun arrowAt(x: Float, y: Float): Triple<Boolean, Int, Direction>? {
        val s = engine.size
        // columns: above / below the board
        if (x >= boardLeft && x < boardLeft + boardSide) {
            val c = ((x - boardLeft) / cellSize).toInt().coerceIn(0, s - 1)
            if (y in (boardTop - gutter)..boardTop) return Triple(false, c, Direction.UP)
            if (y in (boardTop + boardSide)..(boardTop + boardSide + gutter))
                return Triple(false, c, Direction.DOWN)
        }
        // rows: left / right of the board
        if (y >= boardTop && y < boardTop + boardSide) {
            val r = ((y - boardTop) / cellSize).toInt().coerceIn(0, s - 1)
            if (x in (boardLeft - gutter)..boardLeft) return Triple(true, r, Direction.LEFT)
            if (x in (boardLeft + boardSide)..(boardLeft + boardSide + gutter))
                return Triple(true, r, Direction.RIGHT)
        }
        return null
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, dir: Direction, enabled: Boolean) {
        val h = gutter * 0.42f
        val w = gutter * 0.42f
        val path = Path()
        when (dir) {
            Direction.UP -> {
                path.moveTo(cx, cy - h / 2); path.lineTo(cx - w, cy + h / 2); path.lineTo(cx + w, cy + h / 2)
            }
            Direction.DOWN -> {
                path.moveTo(cx, cy + h / 2); path.lineTo(cx - w, cy - h / 2); path.lineTo(cx + w, cy - h / 2)
            }
            Direction.LEFT -> {
                path.moveTo(cx - h / 2, cy); path.lineTo(cx + h / 2, cy - w); path.lineTo(cx + h / 2, cy + w)
            }
            Direction.RIGHT -> {
                path.moveTo(cx + h / 2, cy); path.lineTo(cx - h / 2, cy - w); path.lineTo(cx - h / 2, cy + w)
            }
        }
        path.close()
        fill.color = if (enabled) cGold else cDisabled
        fill.alpha = if (enabled) 255 else 70
        canvas.drawPath(path, fill)
        fill.alpha = 255
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = AnimationUtils.currentAnimationTimeMillis()
        pump(now)
        updateParticles(now)

        canvas.drawColor(cBg)
        drawHud(canvas, now)
        drawMessage(canvas)
        drawBoard(canvas, now)
        drawSprites(canvas)
        drawAbilityBar(canvas)
        drawActionButton(canvas)
        drawParticles(canvas)

        val needsFrames = busy || particles.isNotEmpty() ||
            selected != null || transientText != null ||
            (engine.phase == Phase.PLAY && engine.pieces.any { it.alive && it.anchored })
        if (needsFrames) postInvalidateOnAnimation()
    }

    private fun drawHud(canvas: Canvas, now: Long) {
        val half = (hudRect.width() - dp(10f)) / 2f
        for (player in 1..2) {
            val left = if (player == 1) hudRect.left else hudRect.right - half
            val rect = RectF(left, hudRect.top, left + half, hudRect.bottom)
            fill.color = cPanel
            canvas.drawRoundRect(rect, dp(14f), dp(14f), fill)
            val active = engine.phase != Phase.OVER && engine.current == player
            if (active) {
                stroke.color = cGold
                stroke.strokeWidth = dp(2.5f)
                stroke.alpha = (170 + 85 * sin(now / 260.0)).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, dp(14f), dp(14f), stroke)
                stroke.alpha = 255
            }
            // avatar
            val ar = dp(20f)
            val ax = rect.left + dp(10f) + ar
            val ay = rect.centerY()
            drawAvatar(canvas, ax, ay, ar, player)
            // name
            textPaint.color = cText
            textPaint.textSize = sp(14f)
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            textPaint.textAlign = Paint.Align.LEFT
            val nameX = ax + ar + dp(8f)
            canvas.drawText(name(player), nameX, ay - dp(2f), textPaint)
            // alive pips
            val total = engine.config.piecesPerPlayer
            val aliveCount = engine.alive(player).size
            val pr = dp(4.5f)
            for (i in 0 until total) {
                val px = nameX + pr + i * (pr * 2 + dp(5f))
                val py = ay + dp(10f)
                if (i < aliveCount) {
                    fill.color = if (player == 1) cP1 else cP2
                    canvas.drawCircle(px, py, pr, fill)
                } else {
                    stroke.color = cDisabled
                    stroke.strokeWidth = dp(1.5f)
                    canvas.drawCircle(px, py, pr, stroke)
                }
            }
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawAvatar(canvas: Canvas, cx: Float, cy: Float, r: Float, player: Int) {
        val bmp = avatars.getOrNull(player)
        if (bmp != null) {
            val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, null, RectF(cx - r, cy - r, cx + r, cy + r), fill)
            canvas.restore()
            stroke.color = cBoardDark
            stroke.strokeWidth = dp(1.5f)
            canvas.drawCircle(cx, cy, r, stroke)
            return
        }
        fill.color = if (player == 1) cP1 else cP2
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = cBoardDark
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, r, stroke)
        if (botControlled(player)) {
            // little robot face
            fill.color = Color.WHITE
            val er = r * 0.18f
            canvas.drawCircle(cx - r * 0.32f, cy - r * 0.1f, er, fill)
            canvas.drawCircle(cx + r * 0.32f, cy - r * 0.1f, er, fill)
            stroke.color = Color.WHITE
            stroke.strokeWidth = dp(2f)
            canvas.drawLine(cx - r * 0.3f, cy + r * 0.35f, cx + r * 0.3f, cy + r * 0.35f, stroke)
            canvas.drawLine(cx, cy - r, cx, cy - r * 1.3f, stroke)
            canvas.drawCircle(cx, cy - r * 1.35f, dp(2f), stroke)
        } else {
            textPaint.color = Color.WHITE
            textPaint.textSize = r * 1.1f
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(player.toString(), cx, cy + r * 0.38f, textPaint)
        }
    }

    private fun drawMessage(canvas: Canvas) {
        textPaint.color = if (transientText != null) cP1 else cText
        textPaint.textSize = sp(15f)
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        var text = statusText()
        // shrink to fit
        val maxW = width - paddingLeft - paddingRight - dp(24f)
        while (textPaint.measureText(text) > maxW && textPaint.textSize > sp(10f)) {
            textPaint.textSize = textPaint.textSize - 1f
        }
        canvas.drawText(text, (width / 2).toFloat(), msgBaseline, textPaint)
    }

    private fun drawBoard(canvas: Canvas, now: Long) {
        // wooden frame
        val frame = RectF(
            boardLeft - dp(8f), boardTop - dp(8f),
            boardLeft + boardSide + dp(8f), boardTop + boardSide + dp(8f)
        )
        fill.color = cBoard
        canvas.drawRoundRect(frame, dp(12f), dp(12f), fill)

        val s = engine.size
        val humanTurn = !botControlled(engine.current) && engine.phase != Phase.OVER && !busy
        val placements = if (engine.phase == Phase.SETUP && humanTurn)
            engine.legalPlacements().cells else emptyList()

        for (r in 0 until s) for (c in 0 until s) {
            val l = boardLeft + c * cellSize
            val t = boardTop + r * cellSize
            val rect = RectF(l + dp(1f), t + dp(1f), l + cellSize - dp(1f), t + cellSize - dp(1f))
            val cellHere = Cell(r, c)
            fill.color = when {
                cellHere == selected || cellHere == swapFirst -> cGold
                else -> cCell
            }
            canvas.drawRect(rect, fill)
            stroke.color = cBoardDark
            stroke.strokeWidth = dp(1.5f)
            canvas.drawRect(rect, stroke)
            if (engine.isCenter(r, c)) {
                stroke.strokeWidth = dp(2.5f)
                val inset = cellSize * 0.28f
                canvas.drawLine(l + inset, t + inset, l + cellSize - inset, t + cellSize - inset, stroke)
                canvas.drawLine(l + cellSize - inset, t + inset, l + inset, t + cellSize - inset, stroke)
            }
        }

        // hints
        if (humanTurn) {
            val hints = if (engine.phase == Phase.SETUP) placements else hintCells
            for (cell in hints) {
                val ctr = cellCenter(cell)
                val occupied = engine.pieceAt(cell) != null
                fill.color = if (occupied) cHintOrange else cHintGreen
                fill.alpha = 235
                val rad = if (mode == Mode.DASH_PIECE || mode == Mode.SWAP_FIRST ||
                    mode == Mode.SWAP_SECOND || mode == Mode.ANCHOR
                ) cellSize * 0.44f else cellSize * 0.13f
                if (rad > cellSize * 0.3f) {
                    stroke.color = fill.color
                    stroke.strokeWidth = dp(3f)
                    canvas.drawCircle(ctr.x, ctr.y, rad, stroke)
                } else {
                    canvas.drawCircle(ctr.x, ctr.y, rad, fill)
                }
                fill.alpha = 255
            }
        }

        // selection pulse ring
        selected?.let {
            val ctr = cellCenter(it)
            stroke.color = cGold
            stroke.strokeWidth = dp(3.5f)
            stroke.alpha = (150 + 105 * sin(now / 180.0)).toInt().coerceIn(0, 255)
            canvas.drawCircle(ctr.x, ctr.y, cellSize * 0.46f, stroke)
            stroke.alpha = 255
        }

        // shift arrows
        if (mode == Mode.SHIFT && humanTurn) {
            for (i in 0 until s) {
                val cx = boardLeft + (i + 0.5f) * cellSize
                val cy = boardTop + (i + 0.5f) * cellSize
                drawArrow(canvas, cx, boardTop - gutter * 0.5f, Direction.UP, engine.canShift(false, i, Direction.UP))
                drawArrow(canvas, cx, boardTop + boardSide + gutter * 0.5f, Direction.DOWN, engine.canShift(false, i, Direction.DOWN))
                drawArrow(canvas, boardLeft - gutter * 0.5f, cy, Direction.LEFT, engine.canShift(true, i, Direction.LEFT))
                drawArrow(canvas, boardLeft + boardSide + gutter * 0.5f, cy, Direction.RIGHT, engine.canShift(true, i, Direction.RIGHT))
            }
        }
    }

    private fun drawSprites(canvas: Canvas) {
        val radius = cellSize * 0.36f
        for (piece in engine.pieces) {
            val spr = sprites[piece.id] ?: continue
            if (!spr.visible) continue
            val r = radius * spr.scale
            canvas.save()
            canvas.rotate(spr.rot, spr.x, spr.y)
            // shadow
            fill.color = Color.BLACK
            fill.alpha = (36 * spr.alpha).toInt()
            canvas.drawCircle(spr.x + dp(2f), spr.y + dp(3f), r, fill)
            // body
            fill.color = if (spr.player == 1) cP1 else cP2
            fill.alpha = (255 * spr.alpha).toInt()
            canvas.drawCircle(spr.x, spr.y, r, fill)
            // rim
            stroke.color = cBoardDark
            stroke.strokeWidth = dp(2.5f)
            stroke.alpha = (255 * spr.alpha).toInt()
            canvas.drawCircle(spr.x, spr.y, r, stroke)
            // glossy highlight
            fill.color = Color.WHITE
            fill.alpha = (70 * spr.alpha).toInt()
            canvas.drawCircle(spr.x - r * 0.3f, spr.y - r * 0.35f, r * 0.32f, fill)
            // player number
            textPaint.color = Color.WHITE
            textPaint.alpha = (255 * spr.alpha).toInt()
            textPaint.textSize = r * 0.95f
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(spr.player.toString(), spr.x, spr.y + r * 0.34f, textPaint)
            textPaint.alpha = 255
            // anchor marker
            if (piece.alive && piece.anchored) {
                stroke.color = cGold
                stroke.strokeWidth = dp(3f)
                stroke.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
                canvas.drawCircle(spr.x, spr.y, r + dp(4f), stroke)
                stroke.pathEffect = null
            }
            canvas.restore()
            fill.alpha = 255
            stroke.alpha = 255
        }
    }

    private fun drawAbilityBar(canvas: Canvas) {
        if (engine.phase != Phase.PLAY) return
        val player = engine.current
        val usable = engine.actionsDone == 1 && !botControlled(player)
        for (i in 0 until 4) {
            val type = AbilityType.entries[i]
            val ab: Ability = engine.ability(player, type)
            val rect = abilityRects[i]
            val enabled = usable && ab.ready
            fill.color = cPanel
            fill.alpha = if (enabled) 255 else 140
            canvas.drawRoundRect(rect, dp(14f), dp(14f), fill)
            fill.alpha = 255
            stroke.color = if (enabled) cBoardDark else cDisabled
            stroke.strokeWidth = dp(2f)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), stroke)

            val iconCx = rect.centerX()
            val iconCy = rect.top + rect.height() * 0.38f
            val iconR = min(rect.width(), rect.height()) * 0.2f
            val iconColor = if (enabled) cBoardDark else cDisabled
            drawAbilityIcon(canvas, type, iconCx, iconCy, iconR, iconColor)

            textPaint.color = if (enabled) cText else cDisabled
            textPaint.textSize = sp(11f)
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(abilityLabel(type), rect.centerX(), rect.bottom - dp(9f), textPaint)

            if (!ab.ready) {
                fill.color = cBoardDark
                fill.alpha = 60
                canvas.drawRoundRect(rect, dp(14f), dp(14f), fill)
                fill.alpha = 255
                textPaint.color = Color.WHITE
                textPaint.textSize = sp(17f)
                fill.color = cBoardDark
                canvas.drawCircle(rect.right - dp(13f), rect.top + dp(13f), dp(11f), fill)
                canvas.drawText(
                    ab.remaining.toString(),
                    rect.right - dp(13f), rect.top + dp(13f) + sp(6f), textPaint
                )
            }
        }
    }

    private fun abilityLabel(type: AbilityType): String = context.getString(
        when (type) {
            AbilityType.DASH -> R.string.ability_dash
            AbilityType.SWAP -> R.string.ability_swap
            AbilityType.SHIFT -> R.string.ability_shift
            AbilityType.ANCHOR -> R.string.ability_anchor
        }
    )

    private fun drawAbilityIcon(canvas: Canvas, type: AbilityType, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = dp(2.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        when (type) {
            AbilityType.DASH -> {
                val p = Path()
                p.moveTo(cx - r * 1.2f, cy - r * 0.8f); p.lineTo(cx - r * 0.2f, cy); p.lineTo(cx - r * 1.2f, cy + r * 0.8f)
                p.moveTo(cx - r * 0.1f, cy - r * 0.8f); p.lineTo(cx + r * 0.9f, cy); p.lineTo(cx - r * 0.1f, cy + r * 0.8f)
                canvas.drawPath(p, stroke)
            }
            AbilityType.SWAP -> {
                val p = Path()
                p.moveTo(cx - r, cy - r * 0.4f); p.lineTo(cx + r * 0.7f, cy - r * 0.4f)
                p.moveTo(cx + r * 0.3f, cy - r * 0.85f); p.lineTo(cx + r * 0.75f, cy - r * 0.4f); p.lineTo(cx + r * 0.3f, cy + r * 0.05f)
                p.moveTo(cx + r, cy + r * 0.5f); p.lineTo(cx - r * 0.7f, cy + r * 0.5f)
                p.moveTo(cx - r * 0.3f, cy + r * 0.05f); p.lineTo(cx - r * 0.75f, cy + r * 0.5f); p.lineTo(cx - r * 0.3f, cy + r * 0.95f)
                canvas.drawPath(p, stroke)
            }
            AbilityType.SHIFT -> {
                val p = Path()
                for (i in -1..1) {
                    val yy = cy + i * r * 0.65f
                    p.moveTo(cx - r, yy); p.lineTo(cx + r * 0.45f, yy)
                }
                p.moveTo(cx + r * 0.15f, cy - r * 0.45f); p.lineTo(cx + r * 0.85f, cy); p.lineTo(cx + r * 0.15f, cy + r * 0.45f)
                canvas.drawPath(p, stroke)
            }
            AbilityType.ANCHOR -> {
                val p = Path()
                p.moveTo(cx, cy - r); p.lineTo(cx, cy + r * 0.55f)
                p.addCircle(cx, cy - r * 0.65f, r * 0.28f, Path.Direction.CW)
                p.moveTo(cx - r * 0.8f, cy + r * 0.05f)
                p.quadTo(cx - r * 0.75f, cy + r * 0.85f, cx, cy + r * 0.55f)
                p.quadTo(cx + r * 0.75f, cy + r * 0.85f, cx + r * 0.8f, cy + r * 0.05f)
                canvas.drawPath(p, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawActionButton(canvas: Canvas) {
        val label = actionButtonLabel() ?: return
        fill.color = if (mode != Mode.NORMAL) cHintOrange else cBoard
        canvas.drawRoundRect(actionBtnRect, dp(23f), dp(23f), fill)
        stroke.color = cBoardDark
        stroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(actionBtnRect, dp(23f), dp(23f), stroke)
        textPaint.color = Color.WHITE
        textPaint.textSize = sp(15f)
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(label, actionBtnRect.centerX(), actionBtnRect.centerY() + sp(5f), textPaint)
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            fill.color = p.color
            fill.alpha = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            if (p.square) {
                canvas.save()
                canvas.rotate(p.rot, p.x, p.y)
                canvas.drawRect(p.x - p.size, p.y - p.size * 0.6f, p.x + p.size, p.y + p.size * 0.6f, fill)
                canvas.restore()
            } else {
                canvas.drawCircle(p.x, p.y, p.size, fill)
            }
        }
        fill.alpha = 255
    }
}
