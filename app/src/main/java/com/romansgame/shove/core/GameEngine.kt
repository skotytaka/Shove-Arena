package com.romansgame.shove.core

/**
 * Pure game logic — no Android imports, fully unit-testable on the JVM.
 *
 * Rules
 * =====
 * Board 5x5. Each player owns 2 (or 3) pieces.
 *
 * SETUP: players alternate placing pieces. A piece may not be placed on the
 * edge, in the center, on an occupied cell, or (classic 2-piece game) in the
 * same row/column as an enemy piece. If that last rule ever leaves a player
 * with no legal cell, it is automatically relaxed for that placement.
 *
 * PLAY: a turn is exactly two actions.
 *   Action 1 — move one of your pieces 1 cell orthogonally.
 *   Action 2 — move a DIFFERENT piece, use one ability, or skip.
 *   (If you only have one piece left, it may move twice.)
 * Moving into an occupied cell pushes the whole chain of pieces 1 cell —
 * friend or foe. A piece pushed over the edge is eliminated, even your own.
 *
 * Abilities (cooldowns in Balance.kt), usable only as the second action:
 *   DASH   — one of your pieces moves 2 cells in a straight line, pushing.
 *   SWAP   — swap one of your pieces with any other piece.
 *   MAGNET — pull an enemy piece 1 cell toward one of your pieces along
 *            a clear straight line (it can never pull a piece off the board).
 *   ANCHOR — one of your pieces cannot be pushed, swapped or pulled until
 *            your next turn. An anchored piece blocks pushes through its line.
 *
 * WIN: FIRST_BLOOD — opponent loses a piece; LAST_STANDING — opponent loses
 * all pieces. If a single action eliminates pieces of both players past the
 * threshold, the acting player wins.
 */
class GameEngine(val config: GameConfig) {

    val size = Balance.BOARD_SIZE
    val board: Array<Array<Piece?>> = Array(size) { arrayOfNulls(size) }
    val pieces = ArrayList<Piece>()

    var phase = Phase.SETUP; private set
    var current = config.startingPlayer; private set

    /** 0 — first action pending, 1 — second action pending. */
    var actionsDone = 0; private set

    /** Piece that made the first move this turn (-1 = none). */
    var movedPieceId = -1; private set

    /** 1 or 2 once the game is decided; 0 otherwise (draw if phase == OVER). */
    var winner = 0; private set

    var turnNumber = 1; private set

    private val abilitiesByPlayer: Map<Int, List<Ability>> = mapOf(
        1 to AbilityType.entries.map { Ability(it) },
        2 to AbilityType.entries.map { Ability(it) }
    )

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    fun inside(r: Int, c: Int) = r in 0 until size && c in 0 until size
    fun inside(cell: Cell) = inside(cell.row, cell.col)
    fun pieceAt(cell: Cell): Piece? = if (inside(cell)) board[cell.row][cell.col] else null
    fun alive(player: Int): List<Piece> = pieces.filter { it.player == player && it.alive }
    fun abilities(player: Int): List<Ability> = abilitiesByPlayer.getValue(player)
    fun ability(player: Int, type: AbilityType): Ability = abilities(player).first { it.type == type }
    fun isCenter(r: Int, c: Int) = r == size / 2 && c == size / 2
    fun isEdge(r: Int, c: Int) = r == 0 || r == size - 1 || c == 0 || c == size - 1

    /** How many pieces the current player still has to place during SETUP. */
    fun toPlace(player: Int): Int =
        config.piecesPerPlayer - pieces.count { it.player == player }

    // ------------------------------------------------------------------
    // Setup phase
    // ------------------------------------------------------------------

    data class Placements(val cells: List<Cell>, val relaxed: Boolean)

    fun legalPlacements(): Placements {
        if (phase != Phase.SETUP) return Placements(emptyList(), false)
        val inner = ArrayList<Cell>()
        for (r in 1 until size - 1) for (c in 1 until size - 1) {
            if (isCenter(r, c)) continue
            if (board[r][c] != null) continue
            inner.add(Cell(r, c))
        }
        if (config.piecesPerPlayer == 2) {
            // Classic rule: not in the same row/column as an enemy piece.
            val strict = inner.filter { cell ->
                pieces.none {
                    it.alive && it.player != current &&
                        (it.row == cell.row || it.col == cell.col)
                }
            }
            if (strict.isNotEmpty()) return Placements(strict, false)
            return Placements(inner, true)   // deadlock protection
        }
        return Placements(inner, false)
    }

    fun place(cell: Cell): ActionResult {
        if (phase != Phase.SETUP || winner != 0) return ActionResult.fail(Msg.NOT_ALLOWED)
        if (!inside(cell)) return ActionResult.fail(Msg.NOT_ALLOWED)
        if (pieceAt(cell) != null) return ActionResult.fail(Msg.CELL_OCCUPIED)
        if (isCenter(cell.row, cell.col)) return ActionResult.fail(Msg.CENTER_FORBIDDEN)
        if (isEdge(cell.row, cell.col)) return ActionResult.fail(Msg.EDGE_FORBIDDEN)
        if (cell !in legalPlacements().cells) return ActionResult.fail(Msg.OPPOSITE_FORBIDDEN)

        val piece = Piece(pieces.size, current)
        piece.row = cell.row; piece.col = cell.col
        board[cell.row][cell.col] = piece
        pieces.add(piece)

        val events = ArrayList<GameEvent>()
        events.add(GameEvent.Placed(piece.id, piece.player, cell))

        if (pieces.size == config.piecesPerPlayer * 2) {
            phase = Phase.PLAY
            current = config.startingPlayer
            startTurn(events, 0)
        } else {
            current = 3 - current
        }
        return ActionResult(true, null, events)
    }

    /**
     * Test/tooling helper: force-place a piece during SETUP ignoring
     * placement rules. Used by unit tests to build exact positions.
     */
    fun forcePlace(player: Int, cell: Cell): Piece {
        require(phase == Phase.SETUP) { "forcePlace only works during SETUP" }
        require(inside(cell) && board[cell.row][cell.col] == null)
        val piece = Piece(pieces.size, player)
        piece.row = cell.row; piece.col = cell.col
        board[cell.row][cell.col] = piece
        pieces.add(piece)
        if (pieces.size == config.piecesPerPlayer * 2) {
            phase = Phase.PLAY
            current = config.startingPlayer
            startTurn(ArrayList(), 0)
        }
        return piece
    }

    // ------------------------------------------------------------------
    // Movement & push chains
    // ------------------------------------------------------------------

    private class ChainInfo(val chain: List<Piece>, val endsOffBoard: Boolean) {
        val blocked: Boolean get() = chain.any { it.anchored }
    }

    /** Pieces standing in an unbroken line starting at (r0,c0)+dir. */
    private fun chainFrom(r0: Int, c0: Int, dir: Direction): ChainInfo {
        val chain = ArrayList<Piece>()
        var r = r0 + dir.dr
        var c = c0 + dir.dc
        while (inside(r, c) && board[r][c] != null) {
            chain.add(board[r][c]!!)
            r += dir.dr; c += dir.dc
        }
        return ChainInfo(chain, !inside(r, c))
    }

    private fun stepLegal(piece: Piece, dir: Direction): Boolean {
        val r = piece.row + dir.dr
        val c = piece.col + dir.dc
        if (!inside(r, c)) return false
        return !chainFrom(piece.row, piece.col, dir).blocked
    }

    /** Executes one validated step of [piece] in [dir], pushing chains. */
    private fun step(piece: Piece, dir: Direction, events: MutableList<GameEvent>) {
        val info = chainFrom(piece.row, piece.col, dir)
        var toMove = info.chain
        if (info.endsOffBoard && toMove.isNotEmpty()) {
            val fallen = toMove.last()
            val fallenFrom = Cell(fallen.row, fallen.col)
            board[fallen.row][fallen.col] = null
            fallen.alive = false
            fallen.row = -1; fallen.col = -1
            events.add(GameEvent.Fell(fallen.id, fallen.player, fallenFrom, dir))
            toMove = toMove.dropLast(1)
        }
        for (p in toMove.asReversed()) {
            val from = Cell(p.row, p.col)
            board[p.row][p.col] = null
            p.row += dir.dr; p.col += dir.dc
            board[p.row][p.col] = p
            events.add(GameEvent.Moved(p.id, from, Cell(p.row, p.col), push = true))
        }
        val from = Cell(piece.row, piece.col)
        board[piece.row][piece.col] = null
        piece.row += dir.dr; piece.col += dir.dc
        board[piece.row][piece.col] = piece
        events.add(GameEvent.Moved(piece.id, from, Cell(piece.row, piece.col), push = false))
    }

    /** May this piece act (move/dash) right now, honoring the two-action rule? */
    private fun pieceMayActThisTurn(piece: Piece): Boolean {
        if (actionsDone == 0) return true
        if (piece.id != movedPieceId) return true
        return alive(current).size == 1   // sole survivor may move twice
    }

    fun legalMoveTargets(from: Cell): List<Cell> {
        if (phase != Phase.PLAY || winner != 0) return emptyList()
        val piece = pieceAt(from) ?: return emptyList()
        if (piece.player != current) return emptyList()
        if (!pieceMayActThisTurn(piece)) return emptyList()
        return Direction.entries.filter { stepLegal(piece, it) }.map { from + it }
    }

    fun tryMove(from: Cell, to: Cell): ActionResult {
        if (phase != Phase.PLAY || winner != 0) return ActionResult.fail(Msg.NOT_ALLOWED)
        val piece = pieceAt(from) ?: return ActionResult.fail(Msg.NO_PIECE)
        if (piece.player != current) return ActionResult.fail(Msg.NOT_YOUR_PIECE)
        if (!pieceMayActThisTurn(piece)) return ActionResult.fail(Msg.ALREADY_MOVED)
        val dir = Direction.between(from, to) ?: return ActionResult.fail(Msg.ONLY_ONE_STEP)
        if (!inside(to)) return ActionResult.fail(Msg.ONLY_ONE_STEP)
        if (chainFrom(piece.row, piece.col, dir).blocked)
            return ActionResult.fail(Msg.BLOCKED_BY_ANCHOR)

        val events = ArrayList<GameEvent>()
        step(piece, dir, events)
        return finishAction(piece.id, events, ability = false)
    }

    // ------------------------------------------------------------------
    // Abilities (second action only; using one ends the turn)
    // ------------------------------------------------------------------

    fun canUseAbility(type: AbilityType): Boolean =
        phase == Phase.PLAY && winner == 0 && actionsDone == 1 && ability(current, type).ready

    /** Null when the ability may be activated; otherwise the reason why not. */
    fun abilityGateMsg(type: AbilityType): Msg? = when {
        phase != Phase.PLAY || winner != 0 -> Msg.NOT_ALLOWED
        actionsDone == 0 -> Msg.ABILITY_SECOND_ONLY
        !ability(current, type).ready -> Msg.ABILITY_NOT_READY
        else -> null
    }

    // --- DASH ---

    fun dashTargets(from: Cell): List<Cell> {
        if (!canUseAbility(AbilityType.DASH)) return emptyList()
        val piece = pieceAt(from) ?: return emptyList()
        if (piece.player != current || !pieceMayActThisTurn(piece)) return emptyList()
        val result = ArrayList<Cell>()
        for (dir in Direction.entries) {
            val land = Cell(from.row + dir.dr * 2, from.col + dir.dc * 2)
            if (!inside(land)) continue
            val sim = copyState()
            val simPiece = sim.pieces[piece.id]
            if (!sim.stepLegal(simPiece, dir)) continue
            sim.step(simPiece, dir, ArrayList())
            if (!sim.stepLegal(simPiece, dir)) continue
            sim.step(simPiece, dir, ArrayList())
            if (simPiece.row == land.row && simPiece.col == land.col) result.add(land)
        }
        return result
    }

    fun dash(from: Cell, to: Cell): ActionResult {
        abilityGateMsg(AbilityType.DASH)?.let { return ActionResult.fail(it) }
        val piece = pieceAt(from) ?: return ActionResult.fail(Msg.NO_PIECE)
        if (piece.player != current) return ActionResult.fail(Msg.NOT_YOUR_PIECE)
        if (!pieceMayActThisTurn(piece)) return ActionResult.fail(Msg.ALREADY_MOVED)
        if (to !in dashTargets(from)) return ActionResult.fail(Msg.NOT_ALLOWED)
        val dir = Direction.entries.first {
            from.row + it.dr * 2 == to.row && from.col + it.dc * 2 == to.col
        }
        val events = ArrayList<GameEvent>()
        events.add(GameEvent.AbilityUsed(current, AbilityType.DASH))
        step(piece, dir, events)
        step(piece, dir, events)
        ability(current, AbilityType.DASH).use()
        return finishAction(piece.id, events, ability = true)
    }

    // --- SWAP ---

    fun swap(a: Cell, b: Cell): ActionResult {
        abilityGateMsg(AbilityType.SWAP)?.let { return ActionResult.fail(it) }
        val pa = pieceAt(a) ?: return ActionResult.fail(Msg.NO_PIECE)
        val pb = pieceAt(b) ?: return ActionResult.fail(Msg.NO_PIECE)
        if (pa === pb) return ActionResult.fail(Msg.NOT_ALLOWED)
        if (pa.player != current && pb.player != current)
            return ActionResult.fail(Msg.SWAP_NEEDS_OWN)
        if (pa.anchored || pb.anchored) return ActionResult.fail(Msg.BLOCKED_BY_ANCHOR)

        board[a.row][a.col] = pb
        board[b.row][b.col] = pa
        pa.row = b.row; pa.col = b.col
        pb.row = a.row; pb.col = a.col

        val events = ArrayList<GameEvent>()
        events.add(GameEvent.AbilityUsed(current, AbilityType.SWAP))
        events.add(GameEvent.Swapped(pa.id, a, pb.id, b))
        ability(current, AbilityType.SWAP).use()
        return finishAction(-1, events, ability = true)
    }

    // --- MAGNET ---

    /**
     * Enemy pieces the current player may pull one cell toward the piece
     * at [from]: same row/column, all cells between empty, at least one
     * empty cell to land on, victim not anchored. Never lethal.
     */
    fun magnetTargets(from: Cell): List<Cell> {
        if (!canUseAbility(AbilityType.MAGNET)) return emptyList()
        val piece = pieceAt(from) ?: return emptyList()
        if (piece.player != current) return emptyList()
        val result = ArrayList<Cell>()
        for (dir in Direction.entries) {
            var r = from.row + dir.dr
            var c = from.col + dir.dc
            var gap = 0
            while (inside(r, c) && board[r][c] == null) {
                gap++
                r += dir.dr
                c += dir.dc
            }
            if (!inside(r, c)) continue           // nothing in this direction
            val victim = board[r][c]!!
            if (victim.player == current) continue
            if (victim.anchored) continue
            if (gap < 1) continue                 // adjacent — no room to pull
            result.add(Cell(r, c))
        }
        return result
    }

    fun magnet(from: Cell, target: Cell): ActionResult {
        abilityGateMsg(AbilityType.MAGNET)?.let { return ActionResult.fail(it) }
        val piece = pieceAt(from) ?: return ActionResult.fail(Msg.NO_PIECE)
        if (piece.player != current) return ActionResult.fail(Msg.NOT_YOUR_PIECE)
        if (target !in magnetTargets(from)) return ActionResult.fail(Msg.NOTHING_TO_PULL)
        val victim = pieceAt(target)!!
        val dir = when {
            target.row == from.row && target.col > from.col -> Direction.LEFT
            target.row == from.row -> Direction.RIGHT
            target.row > from.row -> Direction.UP
            else -> Direction.DOWN
        }
        val old = Cell(victim.row, victim.col)
        board[victim.row][victim.col] = null
        victim.row += dir.dr
        victim.col += dir.dc
        board[victim.row][victim.col] = victim

        val events = ArrayList<GameEvent>()
        events.add(GameEvent.AbilityUsed(current, AbilityType.MAGNET))
        events.add(GameEvent.Moved(victim.id, old, Cell(victim.row, victim.col), push = true))
        ability(current, AbilityType.MAGNET).use()
        return finishAction(-1, events, ability = true)
    }

    // --- ANCHOR ---

    fun anchor(cell: Cell): ActionResult {
        abilityGateMsg(AbilityType.ANCHOR)?.let { return ActionResult.fail(it) }
        val piece = pieceAt(cell) ?: return ActionResult.fail(Msg.NO_PIECE)
        if (piece.player != current) return ActionResult.fail(Msg.NOT_YOUR_PIECE)
        piece.anchored = true
        val events = ArrayList<GameEvent>()
        events.add(GameEvent.AbilityUsed(current, AbilityType.ANCHOR))
        events.add(GameEvent.Anchored(piece.id, cell))
        ability(current, AbilityType.ANCHOR).use()
        return finishAction(-1, events, ability = true)
    }

    // --- Skip the second action ---

    fun skipSecondAction(): ActionResult {
        if (phase != Phase.PLAY || winner != 0 || actionsDone != 1)
            return ActionResult.fail(Msg.NOT_ALLOWED)
        val events = ArrayList<GameEvent>()
        endTurn(events)
        return ActionResult(true, null, events)
    }

    // ------------------------------------------------------------------
    // Turn management & win detection
    // ------------------------------------------------------------------

    private fun finishAction(
        actedPieceId: Int,
        events: MutableList<GameEvent>,
        ability: Boolean
    ): ActionResult {
        val w = checkWin(current)
        if (w != null) {
            winner = w
            phase = Phase.OVER
            events.add(GameEvent.GameOver(w))
            return ActionResult(true, Msg.WIN, events)
        }
        if (!ability && actionsDone == 0) {
            actionsDone = 1
            movedPieceId = actedPieceId
            return ActionResult(true, Msg.SECOND_ACTION, events)
        }
        endTurn(events)
        return ActionResult(true, null, events)
    }

    /** null — game continues; otherwise the winning player. Acting player wins ties. */
    private fun checkWin(mover: Int): Int? {
        val threshold = if (config.winMode == WinMode.FIRST_BLOOD) config.piecesPerPlayer else 1
        val oppDead = alive(3 - mover).size < threshold
        val meDead = alive(mover).size < threshold
        return when {
            oppDead -> mover
            meDead -> 3 - mover
            else -> null
        }
    }

    private fun endTurn(events: MutableList<GameEvent>) {
        abilities(current).forEach { it.tick() }
        current = 3 - current
        actionsDone = 0
        movedPieceId = -1
        turnNumber++
        startTurn(events, 0)
    }

    private fun startTurn(events: MutableList<GameEvent>, depth: Int) {
        // Anchors protect through the opponent's turn and melt when yours begins.
        alive(current).forEach { it.anchored = false }
        events.add(GameEvent.TurnStarted(current, turnNumber))
        if (phase != Phase.PLAY) return
        if (hasAnyMove(current)) return
        if (depth >= 1) {
            // Neither player can move — extremely rare; call it a draw.
            phase = Phase.OVER
            winner = 0
            events.add(GameEvent.GameOver(0))
            return
        }
        events.add(GameEvent.TurnAutoSkipped(current))
        abilities(current).forEach { it.tick() }
        current = 3 - current
        actionsDone = 0
        movedPieceId = -1
        turnNumber++
        startTurn(events, depth + 1)
    }

    private fun hasAnyMove(player: Int): Boolean =
        alive(player).any { p -> Direction.entries.any { stepLegal(p, it) } }

    // ------------------------------------------------------------------
    // Deep copy (for the bot and for dash simulation)
    // ------------------------------------------------------------------

    fun copyState(): GameEngine {
        val e = GameEngine(config)
        e.phase = phase
        e.current = current
        e.actionsDone = actionsDone
        e.movedPieceId = movedPieceId
        e.winner = winner
        e.turnNumber = turnNumber
        for (p in pieces) {
            val np = Piece(p.id, p.player)
            np.row = p.row; np.col = p.col
            np.alive = p.alive; np.anchored = p.anchored
            e.pieces.add(np)
            if (np.alive) e.board[np.row][np.col] = np
        }
        for (player in 1..2) {
            val src = abilities(player)
            val dst = e.abilities(player)
            for (i in src.indices) dst[i].remaining = src[i].remaining
        }
        return e
    }
}
