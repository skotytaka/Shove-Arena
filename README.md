# Штовханина / Shove Arena

Roman's 5×5 shoving duel, rebuilt as a native Android game (Kotlin).
The original pygame prototype is preserved in `legacy/`.

## Open & run (Android Studio or IntelliJ IDEA)

1. **Open this folder** — Android Studio: *File → Open*; IntelliJ IDEA (2024.3+ with the Android plugin) works the same way.
2. Let **Gradle sync** finish. The first sync downloads Gradle 8.9 and dependencies automatically (internet required). The IDE also creates `local.properties` with your SDK path and offers to install the Android SDK if you don't have it.
3. Create an emulator (*Device Manager → “+”*) or plug in a phone with USB debugging enabled.
4. Press **Run ▶** with the `app` configuration.

**Command line builds:** `gradlew` needs `gradle/wrapper/gradle-wrapper.jar`, which is not bundled. Run `setup-wrapper.bat` once (it downloads the official jar), then `gradlew.bat assembleDebug` works. The IDEs do **not** need this — they sync straight from `gradle-wrapper.properties`.

## The game

- 5×5 board, each player has 2 pieces (or 3 — see Settings).
- **Setup:** not on the edge, not in the center, not in line with an enemy piece (auto-relaxed if impossible).
- **A turn = two actions.** Action 1: move a piece 1 cell. Action 2: move a *different* piece, use one ability, or end the turn. A sole surviving piece may move twice.
- **Shoving:** moving into an occupied cell pushes the whole chain — pieces shoved over the edge are out (your own too!).
- **Abilities** (second action only): **Dash** (2-cell pushing move, cd 3), **Swap** (your piece ↔ any piece, cd 4), **Magnet** (pull an enemy piece 1 cell toward yours along a clear line — never off the board, cd 3), **Anchor** (piece becomes unpushable/unpullable for a turn and blocks shoves through its line, cd 3).
- **Win modes:** *First blood* or *To the last piece*. If one action eliminates pieces of both players, the acting player wins.
- Modes: 2 players on one phone, or vs bot (easy / medium / hard). Rematch swaps who goes first; session score is tracked.

**Balance tuning:** every knob (cooldowns, animation/bot timing) lives in
`app/src/main/java/com/romansgame/shove/core/Balance.kt`.

## Adding music (drop-in, no code changes)

Put files into `app/src/main/res/raw/` and rebuild:

| File | Plays |
|------|-------|
| `music_menu.mp3` | menu, settings, rules |
| `music_game.mp3` | during a match (falls back to menu track) |

Lowercase names only; `.ogg` also works. While absent, the game is silent — the Music toggle in Settings is already wired.
Sound effects are generated placeholder WAVs — replace any `sfx_*.wav` in the same folder to reskin.

## Avatars

- **In the app:** Settings → tap a player's circle → pick a photo from the gallery (long-press to reset). Stored inside the app.
- **As resources:** drop `avatar_player1.png` / `avatar_player2.png` into `app/src/main/res/drawable/` — used automatically as defaults.

## Project map

```
app/src/main/java/com/romansgame/shove/
  core/      game rules engine + bot AI (pure Kotlin, no Android — unit-tested)
  ui/        screens: menu, game (custom-drawn board + animations), settings, rules
  audio/     SoundManager (sfx) + MusicManager (drop-in music)
  profile/   settings storage, names, avatars
app/src/test/  EngineTest — rules & self-play tests (run: gradlew test, or from the IDE)
legacy/        original pygame prototype
```

## Troubleshooting

- *“SDK location not found”* — open the project in Android Studio once; it writes `local.properties` for you.
- *Gradle sync fails offline* — the first sync needs internet.
- *`gradlew` says “Unable to access jarfile”* — run `setup-wrapper.bat` (see above).

---

### Коротко українською

Відкрийте цю папку в Android Studio, дочекайтеся синхронізації Gradle, запустіть ▶ на емуляторі чи телефоні. Музика: покладіть `music_menu.mp3` і `music_game.mp3` у `app/src/main/res/raw/` та перезберіть. Аватарки: у Налаштуваннях торкніться кола гравця, або покладіть `avatar_player1.png`/`avatar_player2.png` у `res/drawable/`. Баланс гри: `core/Balance.kt`.
