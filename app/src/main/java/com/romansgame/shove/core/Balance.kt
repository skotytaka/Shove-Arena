package com.romansgame.shove.core

/**
 * Every game-balance knob in one place — tweak a value and re-run the app.
 * Усі налаштування балансу гри в одному місці.
 */
object Balance {

    /** Board is BOARD_SIZE x BOARD_SIZE. The rules are designed for 5. */
    const val BOARD_SIZE = 5

    // ------------------------------------------------------------------
    // Ability cooldowns, measured in your own turns. The counter ticks at
    // the end of each of your turns (including the turn you used it on),
    // so DASH with cooldown 3 is usable again on your 3rd turn after use.
    // ------------------------------------------------------------------
    const val DASH_COOLDOWN = 3
    const val SWAP_COOLDOWN = 4
    const val MAGNET_COOLDOWN = 3
    const val ANCHOR_COOLDOWN = 3

    // ------------------------------------------------------------------
    // How many of your own turns each ability stays locked at game start.
    // Prevents cheap first-turn knockouts; ANCHOR is available at once
    // as the defensive answer.
    // ------------------------------------------------------------------
    const val DASH_START_DELAY = 2
    const val SWAP_START_DELAY = 1
    const val MAGNET_START_DELAY = 1
    const val ANCHOR_START_DELAY = 0

    // ------------------------------------------------------------------
    // Bot pacing (milliseconds) — feel only, no effect on strength.
    // ------------------------------------------------------------------
    const val BOT_FIRST_DELAY_MS = 600L
    const val BOT_ACTION_DELAY_MS = 750L

    // ------------------------------------------------------------------
    // Animation durations (milliseconds).
    // ------------------------------------------------------------------
    const val ANIM_MOVE_MS = 220L
    const val ANIM_FALL_MS = 520L
    const val ANIM_SWAP_MS = 380L
    const val ANIM_PLACE_MS = 260L
    const val ANIM_SHIFT_MS = 260L
}
