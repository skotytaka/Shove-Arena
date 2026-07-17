package com.romansgame.shove.core

/**
 * Plain data types shared by the engine, the bot and the UI.
 * This package has no Android dependencies and is unit-testable on the JVM.
 */

enum class Direction(val dr: Int, val dc: Int) {
    UP(-1, 0), DOWN(1, 0), LEFT(0, -1), RIGHT(0, 1);

    companion object {
        /** Direction of a single-step move from [from] to [to], or null. */
        fun between(from: Cell, to: Cell): Direction? =
            entries.firstOrNull { from.row + it.dr == to.row && from.col + it.dc == to.col }
    }
}

data class Cell(val row: Int, val col: Int) {
    operator fun plus(d: Direction) = Cell(row + d.dr, col + d.dc)
}

class Piece(val id: Int, val player: Int) {
    var row = -1
    var col = -1
    var alive = true

    /** Protected from pushes, swaps and shifts until the owner's next turn starts. */
    var anchored = false

    val cell: Cell get() = Cell(row, col)
}

enum class AbilityType(val cooldown: Int, val startDelay: Int) {
    /** Ривок — move one of your pieces 2 cells in a straight line, pushing normally. */
    DASH(Balance.DASH_COOLDOWN, Balance.DASH_START_DELAY),

    /** Обмін — swap one of your pieces with any other piece on the board. */
    SWAP(Balance.SWAP_COOLDOWN, Balance.SWAP_START_DELAY),

    /** Магніт — pull an enemy piece one cell toward one of your pieces. */
    MAGNET(Balance.MAGNET_COOLDOWN, Balance.MAGNET_START_DELAY),

    /** Якір — one of your pieces becomes unmovable until your next turn. */
    ANCHOR(Balance.ANCHOR_COOLDOWN, Balance.ANCHOR_START_DELAY)
}

class Ability(val type: AbilityType) {
    var remaining = type.startDelay
    val ready: Boolean get() = remaining == 0
    fun use() { remaining = type.cooldown }
    fun tick() { if (remaining > 0) remaining-- }
}

enum class WinMode {
    /** Lose a single piece — lose the game. */
    FIRST_BLOOD,

    /** Lose all pieces — lose the game. */
    LAST_STANDING
}

enum class Phase { SETUP, PLAY, OVER }

data class GameConfig(
    val piecesPerPlayer: Int = 2,
    val winMode: WinMode = WinMode.FIRST_BLOOD,
    val startingPlayer: Int = 1
)

/** Machine-readable outcome messages; the UI maps them to localized strings. */
enum class Msg {
    CELL_OCCUPIED, CENTER_FORBIDDEN, EDGE_FORBIDDEN, OPPOSITE_FORBIDDEN,
    NO_PIECE, NOT_YOUR_PIECE, ALREADY_MOVED, ONLY_ONE_STEP, BLOCKED_BY_ANCHOR,
    ABILITY_SECOND_ONLY, ABILITY_NOT_READY, NOTHING_TO_PULL, SWAP_NEEDS_OWN,
    NOT_ALLOWED, SECOND_ACTION, WIN
}

/**
 * Everything that visually happened as the result of one action,
 * in the order it happened. The UI turns these into animations.
 */
sealed class GameEvent {
    data class Placed(val pieceId: Int, val player: Int, val at: Cell) : GameEvent()
    data class Moved(val pieceId: Int, val from: Cell, val to: Cell, val push: Boolean) : GameEvent()
    data class Fell(val pieceId: Int, val player: Int, val from: Cell, val dir: Direction) : GameEvent()
    data class Swapped(val idA: Int, val fromA: Cell, val idB: Int, val fromB: Cell) : GameEvent()
    data class Anchored(val pieceId: Int, val at: Cell) : GameEvent()
    data class AbilityUsed(val player: Int, val type: AbilityType) : GameEvent()
    data class TurnStarted(val player: Int, val turnNumber: Int) : GameEvent()
    data class TurnAutoSkipped(val player: Int) : GameEvent()
    data class GameOver(val winner: Int) : GameEvent()   // 0 = draw
}

class ActionResult(
    val ok: Boolean,
    val msg: Msg? = null,
    val events: List<GameEvent> = emptyList()
) {
    companion object {
        fun fail(msg: Msg) = ActionResult(false, msg)
    }
}

/** One atomic thing the bot decided to do. A full turn is a list of these. */
sealed class BotAction {
    data class Place(val at: Cell) : BotAction()
    data class Move(val from: Cell, val to: Cell) : BotAction()
    data class Dash(val from: Cell, val to: Cell) : BotAction()
    data class Swap(val a: Cell, val b: Cell) : BotAction()
    data class Magnet(val from: Cell, val target: Cell) : BotAction()
    data class Anchor(val at: Cell) : BotAction()
    object Skip : BotAction()
}
