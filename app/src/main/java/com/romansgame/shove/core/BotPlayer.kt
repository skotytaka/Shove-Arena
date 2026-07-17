package com.romansgame.shove.core

import java.util.Random
import kotlin.math.abs

/**
 * Computer opponent. Pure Kotlin, thread-safe as long as it gets its own
 * engine copy (GameActivity always passes engine.copyState()).
 *
 * Levels: 0 — easy (loose play, avoids only outright blunders),
 *         1 — medium (greedy: best immediate evaluation),
 *         2 — hard (own turn + opponent's best reply).
 */
class BotPlayer(private val level: Int, seed: Long = System.nanoTime()) {

    private val rnd = Random(seed)

    // ------------------------------------------------------------------
    // Setup phase
    // ------------------------------------------------------------------

    fun choosePlacement(engine: GameEngine): Cell {
        val options = engine.legalPlacements().cells
        val me = engine.current
        var best = options[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (cell in options) {
            var s = rnd.nextDouble() * 10
            for (p in engine.pieces) {
                if (!p.alive) continue
                val d = abs(p.row - cell.row) + abs(p.col - cell.col)
                // keep distance from enemies, spread own pieces a bit
                s += if (p.player == me) d * 1.2 else d * 2.0
            }
            if (s > bestScore) { bestScore = s; best = cell }
        }
        return best
    }

    // ------------------------------------------------------------------
    // One full turn
    // ------------------------------------------------------------------

    fun chooseTurn(engine: GameEngine): List<BotAction> {
        val me = engine.current
        val plans = ArrayList<Pair<List<BotAction>, Double>>()

        for (a1 in firstActions(engine)) {
            val e1 = engine.copyState()
            if (!apply(e1, a1)) continue
            if (e1.winner == me) return listOf(a1)                    // instant win
            if (e1.phase == Phase.OVER) {                              // suicide/draw
                plans.add(Pair(listOf(a1), evaluate(e1, me)))
                continue
            }
            for (a2 in secondActions(e1)) {
                val e2 = e1.copyState()
                if (!apply(e2, a2)) continue
                if (e2.winner == me) return listOf(a1, a2)
                plans.add(Pair(listOf(a1, a2), evaluate(e2, me)))
            }
        }
        if (plans.isEmpty()) return emptyList()   // engine auto-skips stuck turns

        // Two cautious bots can dodge each other forever; after a long game
        // each level drops one notch to force a result.
        val effectiveLevel =
            if (engine.turnNumber > 40 && level > 0) level - 1 else level

        return when (effectiveLevel) {
            0 -> pickEasy(plans)
            1 -> pickGreedy(plans)
            else -> pickHard(engine, plans)
        }
    }

    // ------------------------------------------------------------------
    // Action enumeration
    // ------------------------------------------------------------------

    private fun firstActions(e: GameEngine): List<BotAction> {
        val res = ArrayList<BotAction>()
        for (p in e.alive(e.current)) {
            val from = p.cell
            for (to in e.legalMoveTargets(from)) res.add(BotAction.Move(from, to))
        }
        return res
    }

    private fun secondActions(e: GameEngine): List<BotAction> {
        val res = ArrayList<BotAction>()
        res.add(BotAction.Skip)
        for (p in e.alive(e.current)) {
            val from = p.cell
            for (to in e.legalMoveTargets(from)) res.add(BotAction.Move(from, to))
        }
        if (e.canUseAbility(AbilityType.DASH)) {
            for (p in e.alive(e.current)) {
                val from = p.cell
                for (to in e.dashTargets(from)) res.add(BotAction.Dash(from, to))
            }
        }
        if (e.canUseAbility(AbilityType.SWAP)) {
            val mine = e.alive(e.current)
            val all = e.pieces.filter { it.alive }
            for (m in mine) {
                if (m.anchored) continue
                for (o in all) {
                    if (m.id == o.id || o.anchored) continue
                    if (o.player == e.current && o.id < m.id) continue   // dedupe own pairs
                    res.add(BotAction.Swap(m.cell, o.cell))
                }
            }
        }
        if (e.canUseAbility(AbilityType.MAGNET)) {
            for (p in e.alive(e.current)) {
                val from = p.cell
                for (t in e.magnetTargets(from)) res.add(BotAction.Magnet(from, t))
            }
        }
        if (e.canUseAbility(AbilityType.ANCHOR)) {
            for (p in e.alive(e.current)) res.add(BotAction.Anchor(p.cell))
        }
        return res
    }

    private fun apply(e: GameEngine, a: BotAction): Boolean = when (a) {
        is BotAction.Place -> e.place(a.at).ok
        is BotAction.Move -> e.tryMove(a.from, a.to).ok
        is BotAction.Dash -> e.dash(a.from, a.to).ok
        is BotAction.Swap -> e.swap(a.a, a.b).ok
        is BotAction.Magnet -> e.magnet(a.from, a.target).ok
        is BotAction.Anchor -> e.anchor(a.at).ok
        BotAction.Skip -> e.skipSecondAction().ok
    }

    // ------------------------------------------------------------------
    // Plan selection per difficulty
    // ------------------------------------------------------------------

    private fun pickEasy(plans: List<Pair<List<BotAction>, Double>>): List<BotAction> {
        val safe = plans.filter { it.second > -1e8 }
        val pool = safe.ifEmpty { plans }
        var best = pool[rnd.nextInt(pool.size)]
        repeat(2) {
            val cand = pool[rnd.nextInt(pool.size)]
            if (cand.second > best.second) best = cand
        }
        return best.first
    }

    private fun pickGreedy(plans: List<Pair<List<BotAction>, Double>>): List<BotAction> =
        plans.maxByOrNull { it.second + rnd.nextDouble() * 30 }!!.first

    private fun pickHard(
        engine: GameEngine,
        plans: List<Pair<List<BotAction>, Double>>
    ): List<BotAction> {
        val me = engine.current
        val top = plans.sortedByDescending { it.second }.take(40)
        var bestPlan = top[0].first
        var bestScore = Double.NEGATIVE_INFINITY
        for ((plan, _) in top) {
            val e2 = engine.copyState()
            var ok = true
            for (a in plan) if (!apply(e2, a)) { ok = false; break }
            if (!ok) continue
            val score = if (e2.phase == Phase.OVER) evaluate(e2, me)
                        else -bestReplyScore(e2)
            val jittered = score + rnd.nextDouble() * 5
            if (jittered > bestScore) { bestScore = jittered; bestPlan = plan }
        }
        return bestPlan
    }

    /** Best achievable evaluation for the side to move in [e] (their greedy reply). */
    private fun bestReplyScore(e: GameEngine): Double {
        val opp = e.current
        var best = Double.NEGATIVE_INFINITY
        for (a1 in firstActions(e)) {
            val e1 = e.copyState()
            if (!apply(e1, a1)) continue
            if (e1.winner == opp) return 1e9
            if (e1.phase == Phase.OVER) {
                best = maxOf(best, evaluate(e1, opp))
                continue
            }
            for (a2 in secondActions(e1)) {
                val e2 = e1.copyState()
                if (!apply(e2, a2)) continue
                if (e2.winner == opp) return 1e9
                best = maxOf(best, evaluate(e2, opp))
            }
        }
        if (best == Double.NEGATIVE_INFINITY) best = evaluate(e, opp)
        return best
    }

    // ------------------------------------------------------------------
    // Position evaluation
    // ------------------------------------------------------------------

    fun evaluate(e: GameEngine, me: Int): Double {
        if (e.phase == Phase.OVER) {
            return when (e.winner) {
                me -> 1e9
                0 -> 0.0
                else -> -1e9
            }
        }
        val opp = 3 - me
        var s = (e.alive(me).size - e.alive(opp).size) * 200000.0
        val lethal = if (e.config.winMode == WinMode.FIRST_BLOOD) 2.2 else 1.0
        for (p in e.alive(me)) s += pieceScore(e, p, lethal)
        for (p in e.alive(opp)) s -= pieceScore(e, p, lethal)
        for (a in e.abilities(me)) s += if (a.ready) 18.0 else -3.0 * a.remaining
        for (a in e.abilities(opp)) s -= if (a.ready) 18.0 else -3.0 * a.remaining
        return s
    }

    private fun pieceScore(e: GameEngine, p: Piece, lethal: Double): Double {
        val last = e.size - 1
        val ring = minOf(p.row, last - p.row, p.col, last - p.col)
        var s = when (ring) { 0 -> 0.0; 1 -> 55.0; else -> 70.0 }
        val onEdge = ring == 0
        val corner = (p.row == 0 || p.row == last) && (p.col == 0 || p.col == last)
        if (onEdge) s -= 35.0 * lethal
        if (corner) s -= 25.0 * lethal
        val enemy = 3 - p.player
        for (dir in Direction.entries) {
            if (fallLine(e, p, dir)) {
                s -= 12.0 * lethal   // an exposed direction
                val attacker = e.pieceAt(Cell(p.row - dir.dr, p.col - dir.dc))
                if (attacker != null && attacker.player == enemy && !p.anchored) {
                    s -= 240.0 * lethal   // can be pushed off next turn
                }
                val a2 = e.pieceAt(Cell(p.row - 2 * dir.dr, p.col - 2 * dir.dc))
                if (attacker == null && a2 != null && a2.player == enemy &&
                    e.ability(enemy, AbilityType.DASH).ready && !p.anchored
                ) {
                    s -= 170.0 * lethal   // dash into the gap, then push me off
                }
            } else if (!p.anchored && e.ability(enemy, AbilityType.DASH).ready) {
                // Dash kill through one empty cell behind me:
                // adjacent attacker dashes, shoving me two cells over the edge.
                val gap = Cell(p.row + dir.dr, p.col + dir.dc)
                val beyond = Cell(p.row + 2 * dir.dr, p.col + 2 * dir.dc)
                if (e.inside(gap) && e.pieceAt(gap) == null && !e.inside(beyond)) {
                    val attacker = e.pieceAt(Cell(p.row - dir.dr, p.col - dir.dc))
                    if (attacker != null && attacker.player == enemy) {
                        s -= 200.0 * lethal
                    }
                }
            }
        }
        if (onEdge && !p.anchored && e.ability(enemy, AbilityType.SHIFT).ready) {
            s -= 140.0 * lethal       // shift threat on the outer ring
        }
        if (p.anchored) s += 30.0
        return s
    }

    /** True if pushing [p] one step in [dir] would carry it off the board. */
    private fun fallLine(e: GameEngine, p: Piece, dir: Direction): Boolean {
        var r = p.row + dir.dr
        var c = p.col + dir.dc
        while (e.inside(r, c)) {
            if (e.board[r][c] == null) return false
            r += dir.dr; c += dir.dc
        }
        return true
    }
}
