# Shove Arena

Turn-based shoving duel on a 5×5 board for Android. Push the opponent's pieces over the edge before they do the same to you.

**Stack:** Kotlin · Android Views with a custom-drawn board · JUnit · Android 7.0+ (API 24)
**Version:** 0.2.8 beta

## Functions

**Game modes**
- Two players on one phone
- Versus bot: easy, medium, hard
- Win conditions: first blood, or to the last piece
- 2 or 3 pieces per player
- Rematch swaps who goes first; session score is tracked

**Rules**
- Setup: pieces may not start on the edge, in the centre, or in line with an enemy piece
- A turn has two actions. First: move a piece one cell. Second: move a different piece, use an ability, or end the turn. A sole surviving piece may move twice
- Moving into an occupied cell pushes the whole chain; pieces shoved off the board are out, including your own
- If one action eliminates pieces of both players, the acting player wins

**Abilities** (second action only, each with a cooldown)
- Dash: two-cell pushing move
- Swap: exchange places with any piece
- Magnet: pull an enemy piece one cell towards yours along a clear line
- Anchor: a piece cannot be pushed or pulled for a turn and blocks shoves through its line

**App**
- Animated board, sound effects, optional background music
- Player names and avatars picked from the gallery
- In-app rules screen
- English and Ukrainian

## Build

Open the folder in Android Studio or IntelliJ IDEA, wait for Gradle sync and run the `app` configuration.

For command-line builds run `setup-wrapper.bat` once (downloads the Gradle wrapper jar), then:

```
gradlew assembleDebug
gradlew test
```

Music is not included. To add it, put `music_menu.mp3` and `music_game.mp3` into `app/src/main/res/raw/` and rebuild.

## Structure

```
app/src/main/java/com/romansgame/shove/
  core/      rules engine and bot (pure Kotlin, unit-tested)
  ui/        menu, game board, settings, rules
  audio/     sound effects and music
  profile/   settings, names, avatars
app/src/test/  engine and self-play tests
legacy/        original pygame prototype
```

Balance values (cooldowns, timings) are in `core/Balance.kt`.
