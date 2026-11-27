# Wristcards for Anki – Aufbau

Eigenständige Wear-OS-App (Kotlin, Compose for Wear OS). Der Anki-Kern (Rust, über
`io.github.david-allison:anki-backend-android`) hält die Sammlung auf der Uhr und gleicht direkt mit dem
Sync-Server ab. Kein WebView: Karten werden aus dem gerenderten HTML in eigene Bausteine übersetzt.

## Schichten

| Schicht | Dateien | Aufgabe |
|---|---|---|
| Daten | `AnkiRepository.kt` | Einziger Zugriff auf den Kern. Alle Kernaufrufe serialisiert über `lock` auf `Dispatchers.IO`. Karten rendern (mit kleinem Cache), Bewerten, Rückgängig, Abgleich, Anmeldung, Deckliste als `StateFlow`. |
| | `SyncController.kt` | Startet Abgleiche im Prozess-Scope (überleben einen Neuaufbau der Activity), hält `SyncState`. |
| | `CardText.kt` | HTML einer Kartenseite → `CardSide` aus `Block`s (Text, Bild, IO-Bild, Audio). Reine Funktionen. |
| | `Media.kt` | `CardImages` (Dekodieren, Masken, natives IO, LRU-Cache), `CardAudio` (Wiedergabe, Kopfhörer-Regeln). |
| | `Settings.kt`, `Login.kt` | Uhr-Einstellungen (`SettingsStore`, `StateFlow`), Host-Key im Keystore. |
| Zustand | `ReviewSession.kt` | Lernablauf eines Decks ohne Oberfläche: Karte, aufgedeckt, Bewerten, Rückgängig, Fehler. |
| Oberfläche | `MainActivity.kt` | Activity, Touch-Abfang fürs Rechtswischen (`dispatchTouchEvent`), Konstanten der Wischlogik. |
| | `AppNav.kt` | Start (mit Fehlerbildschirm), Navigation, Verbindung zu Abgleich und Deckliste. |
| | `DeckListScreen.kt`, `DeckListPrefs.kt` | Deckliste, Legende, Konflikt-Rückfrage; Filtern/Anheften/Einklappen. |
| | `ReviewScreen.kt` | Lernansicht: Gesten, Bewertungsknöpfe, Einblendung mit Rückgängig, Fertig-Bildschirm. |
| | `CardBlocks.kt` | Darstellung der Bausteine (Text, Bilder, Zoom, Audio-Knopf, Hinweis). |
| | `SettingsScreen.kt`, `CommonUi.kt`, `Theme.kt` | Einstellungsseiten, gemeinsame Bausteine, Farben. |
| | `DueComplication.kt` | Zifferblatt-Komplikation (gespeicherte Zahlen, frisch nur bei laufender App oder nach 2 h). |

## Regeln, die man kennen muss

- **Kern nur über `AnkiRepository`** und nur unter `lock`. Warten auf WLAN und der Medienabgleich laufen bewusst
  außerhalb der Sperre (`networkLock`), damit man währenddessen lernen kann.
- **Kein Vollupload von selbst.** Bei einem Konflikt (`FULL_SYNC`) fragt die Deckliste: Server-Version laden,
  Uhr-Version hochladen (mit zweiter Warnung) oder später. `FULL_DOWNLOAD` (Uhr leer) läuft automatisch.
- **Bewerten:** Speichern und Entfernen der Karte laufen gemeinsam in `NonCancellable`. Scheitert danach nur das
  Nachladen, zeigt die Ansicht „Erneut versuchen“ (lädt nur neu, bewertet nicht erneut).
- **Rückgängig** nimmt nur die eigene letzte Bewertung zurück (`lastAnswerStep` gegen den Undo-Status des Kerns).
- **Wischlogik** (`dispatchTouchEvent`, `swipedRight`, `BACK_ZONE`, `RATE_LOCK_MS`, `PredictiveBackHandler`) ist auf
  echter Hardware austariert. Änderungen nur mit `swipe-test.py` vorher und nachher.
- **Masken:** Fehlt eine IOE-Maske, wird kein Bild gezeigt (sonst stünde die Antwort auf der Frage).
- **Dateizugriffe** (z. B. `mediaFile`) nur auf IO; Audiodateien werden schon beim Rendern aufgelöst.

## Bauen und testen

- Debug (Emulator + Uhr, mit eingebautem Testkonto aus `local.properties`): `./gradlew assembleDebug`
- Release (R8, nur armeabi-v7a; `-Pemulator` ergänzt x86_64): `./gradlew assembleRelease`
- JVM-Tests (Deckliste, Zifferblatt-Zahlen, Textaufbereitung): `./gradlew testDebugUnitTest`
- Wischtest im Emulator: `swipe-test.py` (außerhalb des Repos, siehe Projektnotizen).

## Offen

- Kein eigenes Baseline-Profil (Compose/Wear bringen ihre mit).
- `shownAtMillis` beginnt nach einem Prozessneustart auf der Antwortseite neu (Antwortzeit etwas zu kurz).
- Bildschirm bleibt beim Lernen an, solange `keepScreenOn` gilt; kein Abschalten nach Leerlauf.
