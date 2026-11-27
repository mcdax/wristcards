# Wristcards for Anki – Architecture

Standalone Wear OS app (Kotlin, Compose for Wear OS). The Anki core (Rust, via
`io.github.david-allison:anki-backend-android`) holds the collection on the watch and syncs directly with the
sync server. No WebView: cards are translated from the rendered HTML into custom blocks.

## Layers

| Layer | Files | Purpose |
|---|---|---|
| Data | `AnkiRepository.kt` | The only access to the core. All core calls are serialized via `lock` on `Dispatchers.IO`. Renders cards (with a small cache), rating, undo, sync, sign-in, deck list as `StateFlow`. |
| | `SyncController.kt` | Starts syncs in the process scope (they survive an activity rebuild), holds `SyncState`. |
| | `CardText.kt` | HTML of a card page → `CardSide` made of `Block`s (text, image, IO image, audio). Pure functions. |
| | `Media.kt` | `CardImages` (decoding, masks, native IO, LRU cache), `CardAudio` (playback, headphone rules). |
| | `Settings.kt`, `Login.kt` | Watch settings (`SettingsStore`, `StateFlow`), host key in the Keystore. |
| State | `ReviewSession.kt` | Study flow of a deck without UI: card, revealed, rate, undo, errors. |
| UI | `MainActivity.kt` | Activity, touch interception for the right swipe (`dispatchTouchEvent`), constants of the swipe logic. |
| | `AppNav.kt` | Startup (with error screen), navigation, connection to sync and deck list. |
| | `DeckListScreen.kt`, `DeckListPrefs.kt` | Deck list, legend, conflict prompt; filtering/pinning/collapsing. |
| | `ReviewScreen.kt` | Study view: gestures, rating buttons, overlay with undo, done screen. |
| | `CardBlocks.kt` | Rendering of the blocks (text, images, zoom, audio button, hint). |
| | `SettingsScreen.kt`, `CommonUi.kt`, `Theme.kt` | Settings pages, shared building blocks, colors. |
| | `DueComplication.kt` | Watch-face complication (stored numbers, fresh only while the app is running or after 2 h). |

## Rules you need to know

- **Core only via `AnkiRepository`** and only under `lock`. Waiting for Wi-Fi and the media sync deliberately run
  outside the lock (`networkLock`) so you can keep studying meanwhile.
- **No full upload on its own.** On a conflict (`FULL_SYNC`) the deck list asks: download the server version,
  upload the watch version (with a second warning) or decide later. `FULL_DOWNLOAD` (watch empty) runs automatically.
- **Rating:** saving and removing the card run together in `NonCancellable`. If only the reload fails afterwards,
  the view shows "Try again" (only reloads, doesn't rate again).
- **Undo** only reverts its own last rating (`lastAnswerStep` against the core's undo status).
- **Swipe logic** (`dispatchTouchEvent`, `swipedRight`, `BACK_ZONE`, `RATE_LOCK_MS`, `PredictiveBackHandler`) is tuned
  on real hardware. Change only with `swipe-test.py` before and after.
- **Masks:** if an IOE mask is missing, no image is shown (otherwise the answer would be on the question).
- **File access** (e.g. `mediaFile`) only on IO; audio files are already resolved during rendering.

## Build and test

- Debug (emulator + watch, with the built-in test account from `local.properties`): `./gradlew assembleDebug`
- Release (R8, armeabi-v7a only; `-Pemulator` adds x86_64): `./gradlew assembleRelease`
- JVM tests (deck list, watch-face numbers, text processing): `./gradlew testDebugUnitTest`
- Swipe test in the emulator: `swipe-test.py` (outside the repo, see project notes).

## Open

- No own baseline profile (Compose/Wear ship theirs).
- After a process restart, `shownAtMillis` starts anew on the answer side (answer time slightly too short).
- The screen stays on while studying as long as `keepScreenOn` applies; no switch-off after idling.
