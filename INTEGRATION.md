# Einstellungen – Integration in den Hauptstrang

Branch `settings`. Neue Dateien: `Settings.kt` (Modell + Speicher), `SettingsScreen.kt` (Seiten + Nav-Ziele),
`Login.kt` (Host-Key im Keystore), `DeckListPrefs.kt` (Deckliste filtern, Langdruck-Menü).
Alle Eingriffe in bestehende Dateien sind mit `// [settings]` markiert.

## Werte lesen

```kotlin
val store = SettingsStore.get(context)
val s by store.flow.collectAsState()   // in Compose
store.current                           // außerhalb von Compose (Snapshot)
```

## Bereits verdrahtet (in diesem Branch)

| Einstellung | Wo |
|---|---|
| Sync beim Öffnen (`syncOnOpen`) | `MainActivity.App`, `LaunchedEffect(Unit)` |
| Sync nach dem Deck (`syncOnDeckExit`) | `MainActivity.App`, `DisposableEffect` der Route `review/{deckId}` |
| Nur WLAN (`wifiOnly`) | `AnkiRepository.onWifi` – ohne WLAN nach 15 s Fehler statt Standardnetz |
| Medien `NONE` | `AnkiRepository.syncLocked` – `syncMedia` wird übersprungen |
| Login/Host-Key | `AnkiRepository.login()/signIn()/signOut()`, `SyncCredentials` in `Login.kt` |
| Deckliste: leer ausblenden, anheften, ausblenden, einklappen | `arrangeDecks()` + `DeckMenu` (DeckListPrefs.kt), Aufruf in `DeckList`/`DeckRow` |
| Zugang „⚙ Settings“ | letzter Eintrag in `DeckList`; Nav-Ziele über `settingsGraph(nav, repo) { decks }` |

## Noch zu verdrahten (Hauptstrang: ReviewScreen / Audio)

| Einstellung | Feld | Wo greifen |
|---|---|---|
| Schriftgröße | `fontSize` (SMALL/NORMAL/LARGE) | `CardBody` (jetzt 16.sp/21.sp) und Extra-Text (14.sp/19.sp). Vorschlag Faktor 0.87 / 1.0 / 1.2 auf fontSize **und** lineHeight; `HintChip` mitziehen. |
| Erklärung/Extra | `extraMode` (SHOW/COLLAPSED/HIDE) | `ReviewScreen`, Zweig `else ->` bei `current.answer.extra`: SHOW wie bisher; COLLAPSED = Chip „Show more“ (ähnlich `HintChip`), aufklappbar; HIDE = Block samt Trennlinie weglassen. |
| Audio-Modus | `audioMode` (AUTO/ON_TAP/OFF) | Media.kt/Abspielstelle: AUTO = beim Anzeigen von Frage bzw. Antwort abspielen; ON_TAP = nur Abspielknopf; OFF = keine Knöpfe, nichts abspielen. |
| Audio-Ausgabe | `audioOutput` (BLUETOOTH/SPEAKER) | Beim Abspielen: `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`; BLUETOOTH = A2DP/BLE-Headset bevorzugen (`MediaPlayer.setPreferredDevice`), sonst `TYPE_BUILTIN_SPEAKER`; SPEAKER = immer `TYPE_BUILTIN_SPEAKER`. Wear OS spielt Medien ohne Kopfhörer teils gar nicht ab → vorher prüfen. |
| Vibration beim Bewerten | `vibrateOnRate` | `ReviewScreen.rate()` nach erfolgreichem `repo.answer`: kurzer Klick, z. B. `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.Confirm)` oder `VibrationEffect.EFFECT_TICK`. |
| Vibration Deck fertig | `vibrateOnDeckDone` | `ReviewScreen`, wenn `repo.nextCard()` nach einer Bewertung `null` liefert: `VibrationEffect.createPredefined(EFFECT_DOUBLE_CLICK)`. Braucht ggf. `<uses-permission android:name="android.permission.VIBRATE"/>` (Vibrator direkt; HapticFeedback nicht). |
| Wisch-Abkürzungen | `swipeToRate` | Auf der aufgedeckten Seite horizontale Geste: links = `AGAIN`, rechts = `GOOD`. Achtung: Wischen nach rechts ist auch die Zurück-Geste des `SwipeDismissableNavHost` – dort für die Review-Route abschalten (`userSwipeEnabled`) oder Wischen nur innerhalb der Kartenfläche mit Abstand zum linken Rand auswerten. |
| Knöpfe 4 / 2 | `twoButtons` | `GradeBar`: bei `true` nur `AGAIN` (Index 0) und `GOOD` (Index 2) zeigen, Intervalle entsprechend `intervals[0]`/`intervals[2]`. |
| Intervall-Anzeige | `showIntervals` | `GradeBar`: statt `intervals[i]` ein Kurzlabel (z. B. „✕ / – / ✓ / ★“ oder „Again/Good“) zeigen. |
| Bildschirm wach | `keepScreenOn` | Jetzt global in `MainActivity.onCreate` (`FLAG_KEEP_SCREEN_ON`). Stattdessen in `ReviewScreen` per `DisposableEffect`: `if (s.keepScreenOn) window.addFlags(...)`, in `onDispose` `clearFlags(...)`; den globalen Aufruf in `onCreate` entfernen. |

## Offene Punkte

- Medien „Nur gewählte Decks“: auf Wunsch entfernt (Kern synchronisiert Medien nur komplett).
- **Kontowechsel**: Meldet man sich bei einem anderen Konto/Server an, verlangt der nächste Sync meist einen
  Vollabgleich; der Download überschreibt dann die Sammlung auf der Uhr (nicht hochgeladene Bewertungen gehen verloren).
- **Endpoint-Weiterleitung**: Liefert der Server bei `syncCollection` einen neuen Endpoint (`new_endpoint`), wird er
  noch nicht gespeichert (betrifft nur AnkiWeb-Sharding).
- Text-Eingabe nutzt `BasicTextField` mit der Uhr-Tastatur; auf der echten Uhr noch nicht ausprobiert.
