package com.romansgame.shove.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the game rules. Run from the IDE (right-click, Run)
 * or with:  gradlew test
 */
class EngineTest {

    private fun engine(
        pieces: Int = 2,
        winMode: WinMode = WinMode.FIRST_BLOOD,
        start: Int = 1
    ) = GameEngine(GameConfig(pieces, winMode, start))

    /** Clears ability start-delays so a test can use abilities from turn 1. */
    private fun unlockAbilities(e: GameEngine) {
        for (p in 1..2) e.abilities(p).forEach { it.remaining = 0 }
    }

    private fun applyBot(e: GameEngine, a: BotAction): Boolean = when (a) {
        is BotAction.Place -> e.place(a.at).ok
        is BotAction.Move -> e.tryMove(a.from, a.to).ok
        is BotAction.Dash -> e.dash(a.from, a.to).ok
        is BotAction.Swap -> e.swap(a.a, a.b).ok
        is BotAction.Magnet -> e.magnet(a.from, a.target).ok
        is BotAction.Anchor -> e.anchor(a.at).ok
        BotAction.Skip -> e.skipSecondAction().ok
    }

    private fun checkInvariants(e: GameEngine) {
        var onBoard = 0
        for (r in 0 until e.size) for (c in 0 until e.size) {
            val p = e.board[r][c] ?: continue
            onBoard++
            assertTrue(p.alive)
            assertEquals(r, p.row)
            assertEquals(c, p.col)
        }
        assertEquals(e.pieces.count { it.alive }, onBoard)
        if (e.phase == Phase.OVER && e.winner != 0) {
            val threshold =
                if (e.config.winMode == WinMode.FIRST_BLOOD) e.config.piecesPerPlayer else 1
            assertTrue(e.alive(3 - e.winner).size < threshold)
        }
    }

    // ------------------------------------------------------------------

    @Test
    fun placementRules() {
        val e = engine()
        assertFalse(e.place(Cell(0, 0)).ok)          // edge
        assertFalse(e.place(Cell(2, 2)).ok)          // center
        assertTrue(e.place(Cell(1, 1)).ok)           // P1
        assertFalse(e.place(Cell(1, 3)).ok)          // P2: same row as enemy
        assertFalse(e.place(Cell(3, 1)).ok)          // P2: same column
        assertTrue(e.place(Cell(3, 3)).ok)           // P2 fine
        assertFalse(e.place(Cell(1, 1)).ok)          // occupied
        assertTrue(e.place(Cell(1, 2)).ok)           // P1
        assertTrue(e.place(Cell(2, 3)).ok)           // P2 -> PLAY
        assertEquals(Phase.PLAY, e.phase)
        assertEquals(1, e.current)
    }

    @Test
    fun placementDeadlockRelaxes() {
        val e = engine()
        assertTrue(e.place(Cell(1, 2)).ok)   // P1
        assertTrue(e.place(Cell(2, 1)).ok)   // P2
        assertTrue(e.place(Cell(3, 3)).ok)   // P1 - now P2 has no strict cell
        val pl = e.legalPlacements()
        assertTrue(pl.relaxed)
        assertTrue(pl.cells.isNotEmpty())
        assertTrue(e.place(pl.cells[0]).ok)
        assertEquals(Phase.PLAY, e.phase)
    }

    @Test
    fun pushChainOffBoard() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 1))
        e.forcePlace(2, Cell(2, 2))
        e.forcePlace(2, Cell(2, 3))
        e.forcePlace(1, Cell(2, 4))
        // P1 pushes the whole row right; its own piece at (2,4) falls off
        val r = e.tryMove(Cell(2, 1), Cell(2, 2))
        assertTrue(r.ok)
        assertEquals(1, e.alive(1).size)
        assertEquals(2, e.alive(2).size)
        assertEquals(Phase.PLAY, e.phase)     // LAST_STANDING: game continues
        assertEquals(1, e.pieceAt(Cell(2, 2))!!.player)
        assertEquals(2, e.pieceAt(Cell(2, 3))!!.player)
        assertEquals(2, e.pieceAt(Cell(2, 4))!!.player)
        assertNull(e.pieceAt(Cell(2, 1)))
        checkInvariants(e)
    }

    @Test
    fun firstBloodWinEndsGameOnFirstAction() {
        val e = engine(2, WinMode.FIRST_BLOOD)
        e.forcePlace(1, Cell(2, 2))
        e.forcePlace(1, Cell(3, 1))
        e.forcePlace(2, Cell(2, 3))
        e.forcePlace(2, Cell(2, 4))
        val r = e.tryMove(Cell(2, 2), Cell(2, 3))
        assertTrue(r.ok)
        assertEquals(Phase.OVER, e.phase)
        assertEquals(1, e.winner)
        checkInvariants(e)
    }

    @Test
    fun moverWinsSimultaneousElimination() {
        // A dash that knocks out one piece of EACH player: the acting player wins.
        val e = engine(3, WinMode.FIRST_BLOOD)
        e.forcePlace(1, Cell(1, 1))   // will make the first move
        e.forcePlace(1, Cell(2, 2))   // dasher
        e.forcePlace(1, Cell(2, 4))   // own sacrifice
        e.forcePlace(2, Cell(2, 3))
        e.forcePlace(2, Cell(0, 0))
        e.forcePlace(2, Cell(4, 0))
        unlockAbilities(e)
        assertTrue(e.tryMove(Cell(1, 1), Cell(1, 0)).ok)
        // dash right: step 1 pushes own (2,4) off, step 2 pushes the enemy off
        assertTrue(Cell(2, 4) in e.dashTargets(Cell(2, 2)))
        assertTrue(e.dash(Cell(2, 2), Cell(2, 4)).ok)
        assertEquals(Phase.OVER, e.phase)
        assertEquals(1, e.winner)
        checkInvariants(e)
    }

    @Test
    fun anchorBlocksPushSwapAndMelts() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 1))
        e.forcePlace(1, Cell(4, 4))
        e.forcePlace(2, Cell(2, 2))
        e.forcePlace(2, Cell(0, 0))
        unlockAbilities(e)
        // P1: move, then anchor (2,1)
        assertTrue(e.tryMove(Cell(4, 4), Cell(4, 3)).ok)
        assertTrue(e.anchor(Cell(2, 1)).ok)
        assertEquals(2, e.current)
        assertTrue(e.pieceAt(Cell(2, 1))!!.anchored)
        // P2 cannot push into the anchored piece
        assertFalse(e.tryMove(Cell(2, 2), Cell(2, 1)).ok)
        // P2 makes a legal first move, then swap against the anchor must fail
        assertTrue(e.tryMove(Cell(0, 0), Cell(0, 1)).ok)
        assertFalse(e.swap(Cell(2, 2), Cell(2, 1)).ok)
        assertTrue(e.skipSecondAction().ok)
        // anchor melts at the start of P1's turn
        assertEquals(1, e.current)
        assertFalse(e.pieceAt(Cell(2, 1))!!.anchored)
        checkInvariants(e)
    }

    @Test
    fun dashTargetsAndCooldownTiming() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 2))
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(2, Cell(4, 4))
        e.forcePlace(2, Cell(4, 3))
        unlockAbilities(e)
        assertTrue(e.dashTargets(Cell(2, 2)).isEmpty())    // only as second action
        assertTrue(e.tryMove(Cell(1, 1), Cell(1, 0)).ok)
        val targets = e.dashTargets(Cell(2, 2))
        assertEquals(4, targets.size)
        assertTrue(e.dash(Cell(2, 2), Cell(2, 4)).ok)
        // tick at end of own turn already happened
        assertEquals(Balance.DASH_COOLDOWN - 1, e.ability(1, AbilityType.DASH).remaining)
        assertFalse(e.ability(1, AbilityType.DASH).ready)
        checkInvariants(e)
    }

    @Test
    fun soleSurvivorMayMoveTwice() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 2))
        e.forcePlace(1, Cell(0, 1))
        e.forcePlace(2, Cell(2, 1))
        e.forcePlace(2, Cell(4, 4))
        // P1 turn 1
        assertTrue(e.tryMove(Cell(2, 2), Cell(2, 3)).ok)
        assertTrue(e.skipSecondAction().ok)
        // P2 approaches P1's edge piece
        assertTrue(e.tryMove(Cell(2, 1), Cell(1, 1)).ok)
        assertTrue(e.skipSecondAction().ok)
        // P1 turn 2
        assertTrue(e.tryMove(Cell(2, 3), Cell(2, 2)).ok)
        assertTrue(e.skipSecondAction().ok)
        // P2 pushes P1's (0,1) off the top edge
        assertTrue(e.tryMove(Cell(1, 1), Cell(0, 1)).ok)
        assertEquals(1, e.alive(1).size)
        assertTrue(e.skipSecondAction().ok)
        // P1's only piece may act twice
        assertTrue(e.tryMove(Cell(2, 2), Cell(2, 1)).ok)
        assertTrue(e.tryMove(Cell(2, 1), Cell(2, 0)).ok)
        assertEquals(2, e.current)
        checkInvariants(e)
    }

    @Test
    fun abilityGatingAndTwoActionRule() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(1, Cell(3, 3))
        e.forcePlace(2, Cell(1, 3))
        e.forcePlace(2, Cell(3, 1))
        assertFalse(e.anchor(Cell(1, 1)).ok)                       // not as first action
        assertEquals(Msg.ABILITY_SECOND_ONLY, e.abilityGateMsg(AbilityType.ANCHOR))
        assertTrue(e.tryMove(Cell(1, 1), Cell(1, 0)).ok)
        assertFalse(e.tryMove(Cell(1, 0), Cell(0, 0)).ok)          // same piece again
        assertTrue(e.tryMove(Cell(3, 3), Cell(3, 4)).ok)           // other piece ends turn
        assertEquals(2, e.current)
        checkInvariants(e)
    }

    @Test
    fun swapRules() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(1, Cell(3, 3))
        e.forcePlace(2, Cell(1, 3))
        e.forcePlace(2, Cell(3, 1))
        unlockAbilities(e)
        assertTrue(e.tryMove(Cell(1, 1), Cell(2, 1)).ok)
        assertFalse(e.swap(Cell(1, 3), Cell(3, 1)).ok)             // both enemy
        assertTrue(e.swap(Cell(2, 1), Cell(1, 3)).ok)              // mine + enemy
        assertEquals(1, e.pieceAt(Cell(1, 3))!!.player)
        assertEquals(2, e.pieceAt(Cell(2, 1))!!.player)
        assertEquals(2, e.current)
        checkInvariants(e)
    }

    @Test
    fun magnetPullsTowardCaster() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 0))
        e.forcePlace(1, Cell(4, 4))
        e.forcePlace(2, Cell(2, 3))
        e.forcePlace(2, Cell(0, 0))
        unlockAbilities(e)
        assertTrue(e.magnetTargets(Cell(2, 0)).isEmpty())   // only as second action
        assertTrue(e.tryMove(Cell(4, 4), Cell(4, 3)).ok)
        assertTrue(Cell(2, 3) in e.magnetTargets(Cell(2, 0)))
        assertTrue(e.magnet(Cell(2, 0), Cell(2, 3)).ok)
        assertEquals(2, e.pieceAt(Cell(2, 2))!!.player)     // pulled one cell closer
        assertNull(e.pieceAt(Cell(2, 3)))
        assertEquals(2, e.current)                          // ability ended the turn
        assertEquals(Balance.MAGNET_COOLDOWN - 1, e.ability(1, AbilityType.MAGNET).remaining)
        checkInvariants(e)
    }

    @Test
    fun magnetRespectsAdjacencyBlockersAndAnchor() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(2, 0))
        e.forcePlace(1, Cell(4, 4))
        e.forcePlace(2, Cell(2, 1))
        e.forcePlace(2, Cell(2, 4))
        unlockAbilities(e)
        assertTrue(e.tryMove(Cell(4, 4), Cell(4, 3)).ok)
        val targets = e.magnetTargets(Cell(2, 0))
        assertFalse(Cell(2, 1) in targets)   // adjacent - no room to pull
        assertFalse(Cell(2, 4) in targets)   // line of sight blocked by (2,1)

        val e2 = engine(2, WinMode.LAST_STANDING)
        e2.forcePlace(1, Cell(2, 0))
        e2.forcePlace(1, Cell(4, 4))
        e2.forcePlace(2, Cell(2, 3))
        e2.forcePlace(2, Cell(0, 0))
        unlockAbilities(e2)
        e2.pieceAt(Cell(2, 3))!!.anchored = true
        assertTrue(e2.tryMove(Cell(4, 4), Cell(4, 3)).ok)
        assertFalse(Cell(2, 3) in e2.magnetTargets(Cell(2, 0)))
        assertFalse(e2.magnet(Cell(2, 0), Cell(2, 3)).ok)   // anchored - can't be pulled
        checkInvariants(e2)
    }

    @Test
    fun turnIsExactlyTwoActions() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(1, Cell(3, 3))
        e.forcePlace(2, Cell(1, 3))
        e.forcePlace(2, Cell(3, 1))
        unlockAbilities(e)
        // two moves end the turn immediately - no third action possible
        assertTrue(e.tryMove(Cell(1, 1), Cell(1, 0)).ok)
        assertEquals(1, e.current)
        assertTrue(e.tryMove(Cell(3, 3), Cell(3, 4)).ok)
        assertEquals(2, e.current)                          // turn passed to P2
        assertFalse(e.tryMove(Cell(1, 0), Cell(1, 1)).ok)   // P1 can't act anymore
        assertFalse(e.anchor(Cell(3, 4)).ok)
        assertFalse(e.dash(Cell(1, 0), Cell(1, 2)).ok)
        // move + ability also equals exactly two actions
        assertTrue(e.tryMove(Cell(1, 3), Cell(1, 4)).ok)
        assertTrue(e.anchor(Cell(1, 4)).ok)
        assertEquals(1, e.current)                          // turn ended right away
        assertFalse(e.anchor(Cell(3, 1)).ok)                // no third action ever
        checkInvariants(e)
    }

    @Test
    fun cooldownFullCycle() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(1, Cell(3, 3))
        e.forcePlace(2, Cell(1, 3))
        e.forcePlace(2, Cell(3, 1))

        fun p1MoveAndAnchorOrSkip(useAnchor: Boolean) {
            val piece = e.alive(1).first()
            val from = piece.cell
            val to = e.legalMoveTargets(from).first()
            assertTrue(e.tryMove(from, to).ok)
            if (useAnchor) assertTrue(e.anchor(e.alive(1).first().cell).ok)
            else assertTrue(e.skipSecondAction().ok)
        }

        fun p2Pass() {
            val piece = e.alive(2).first()
            val from = piece.cell
            val to = e.legalMoveTargets(from).first()
            assertTrue(e.tryMove(from, to).ok)
            assertTrue(e.skipSecondAction().ok)
        }

        p1MoveAndAnchorOrSkip(useAnchor = true)
        assertEquals(2, e.ability(1, AbilityType.ANCHOR).remaining)
        p2Pass()
        p1MoveAndAnchorOrSkip(useAnchor = false)
        p2Pass()
        p1MoveAndAnchorOrSkip(useAnchor = false)
        p2Pass()
        assertEquals(1, e.current)
        assertTrue(e.ability(1, AbilityType.ANCHOR).ready)
        checkInvariants(e)
    }

    @Test
    fun abilitiesRespectStartDelays() {
        val e = engine(2, WinMode.LAST_STANDING)
        e.forcePlace(1, Cell(1, 1))
        e.forcePlace(1, Cell(3, 3))
        e.forcePlace(2, Cell(1, 3))
        e.forcePlace(2, Cell(3, 1))
        assertEquals(Balance.DASH_START_DELAY, e.ability(1, AbilityType.DASH).remaining)
        assertEquals(Balance.MAGNET_START_DELAY, e.ability(1, AbilityType.MAGNET).remaining)
        assertEquals(Balance.ANCHOR_START_DELAY, e.ability(1, AbilityType.ANCHOR).remaining)
        assertTrue(e.tryMove(Cell(1, 1), Cell(1, 0)).ok)
        if (Balance.DASH_START_DELAY > 0) {
            assertFalse(e.canUseAbility(AbilityType.DASH))
            assertEquals(Msg.ABILITY_NOT_READY, e.abilityGateMsg(AbilityType.DASH))
        }
        if (Balance.ANCHOR_START_DELAY == 0) {
            assertTrue(e.canUseAbility(AbilityType.ANCHOR))
        }
    }

    @Test
    fun randomSelfPlayKeepsInvariants() {
        var draws = 0
        for (g in 0 until 60) {
            val pieces = if (g % 2 == 0) 2 else 3
            val mode = if (g % 4 < 2) WinMode.FIRST_BLOOD else WinMode.LAST_STANDING
            val e = GameEngine(GameConfig(pieces, mode))
            val bot = BotPlayer(0, seed = g.toLong())
            var guard = 0
            while (e.phase == Phase.SETUP && guard++ < 50) {
                assertTrue(e.place(bot.choosePlacement(e)).ok)
            }
            assertEquals(Phase.PLAY, e.phase)
            guard = 0
            while (e.phase == Phase.PLAY && guard++ < 300) {
                val plan = bot.chooseTurn(e.copyState())
                if (plan.isEmpty()) break
                for (a in plan) {
                    if (e.phase != Phase.PLAY) break
                    assertTrue("bot action failed: $a", applyBot(e, a))
                }
                checkInvariants(e)
            }
            if (e.phase == Phase.OVER && e.winner == 0) draws++
        }
        assertTrue("too many draws: $draws", draws < 5)
    }

    @Test
    fun hardBotPlaysLegally() {
        val e = GameEngine(GameConfig(2, WinMode.FIRST_BLOOD))
        val bot = BotPlayer(2, seed = 7L)
        var guard = 0
        while (e.phase == Phase.SETUP && guard++ < 20) {
            assertTrue(e.place(bot.choosePlacement(e)).ok)
        }
        guard = 0
        while (e.phase == Phase.PLAY && guard++ < 40) {
            val plan = bot.chooseTurn(e.copyState())
            assertTrue(plan.isNotEmpty())
            for (a in plan) {
                if (e.phase != Phase.PLAY) break
                assertTrue(applyBot(e, a))
            }
            checkInvariants(e)
        }
    }
}
