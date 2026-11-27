package de.mcdax.ankiwear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
internal fun App(startup: Deferred<Result<AnkiRepository>>, openDeck: String? = null, previewCard: Long? = null, restoring: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(startup) }
    var result by remember { mutableStateOf<Result<AnkiRepository>?>(null) }
    LaunchedEffect(pending) { result = pending.await() }
    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_cards),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(64.dp),
            )
        }
        r.isFailure -> ErrorNote(
            stringResource(R.string.open_failed),
            r.exceptionOrNull()?.message,
            onRetry = {
                result = null
                pending = scope.async(Dispatchers.IO) { openRepository(context.applicationContext) }
            },
        )
        else -> LoadedApp(r.getOrThrow(), openDeck, previewCard, restoring)
    }
}

@Composable
private fun LoadedApp(repo: AnkiRepository, openDeck: String?, previewCard: Long?, restoring: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberSwipeDismissableNavController()
    // Liegt hier statt in der Deckliste, damit die Scrollposition das Lernen überlebt.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    // Abgleich und Deckliste leben außerhalb der Oberfläche und überstehen einen Neuaufbau der Activity.
    val syncer = remember { SyncController.get(context) }
    val sync by syncer.state.collectAsState()
    val decks by repo.deckTree.collectAsState()
    var decksLoaded by remember { mutableStateOf(false) }
    var startupDrawn by remember { mutableStateOf(false) }
    // Uhr-Einstellungen
    val store = remember { SettingsStore.get(context) }
    val settings by store.flow.collectAsState()
    // Ausgeblendete Decks zählen in der Zifferblatt-Komplikation nicht mit.
    LaunchedEffect(settings.hiddenDecks) { refreshDueComplication(context) }

    fun runSync() = syncer.sync()

    LaunchedEffect(Unit) {
        decksLoaded = true
        // NavController stellt seine Lernansicht selbst wieder her. Weder erneut
        // navigieren noch Start-Sync davor in die gemeinsame Backend-Warteschlange setzen.
        if (restoring) return@LaunchedEffect
        when {
            previewCard != null -> nav.navigate("preview/$previewCard")
            else -> decks.firstOrNull { it.name == openDeck }?.let { nav.navigate("review/${it.deckId}") }
                ?: run { if (store.current.syncOnOpen) runSync() } // Auto-Sync beim Öffnen
        }
    }

    if (decksLoaded) LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        (context as? android.app.Activity)?.reportFullyDrawn()
        startupDrawn = true
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "decks ready")
    }

    LaunchedEffect(decks, startupDrawn) {
        // Erst die gefüllte Deckliste zeichnen; Vorladen gehört nicht zum Startpfad.
        if (!startupDrawn) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        try { repo.preloadUpcomingCards() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("AnkiPerf", "Optionales Vorladen übersprungen", e) }
    }

    // Wischen nach rechts zeigt beim Zurückgehen die Deckliste darunter, statt eines schwarzen Bildschirms.
    // Wisch-Abkürzungen brauchen das Rechtswischen; dann nur per Zurück-Taste aus dem Deck.
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // Verlassen erschweren: erst zweimal Zurück innerhalb von 2 s schließt die App.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(2000)
            exitArmed = false
        }
    }
    CompositionLocalProvider(LocalSettings provides settings) {
    SwipeDismissableNavHost(
        navController = nav,
        startDestination = "decks",
        // Auf neuen Wear-OS-Versionen kommt das Zurückwischen als System-Geste vom Rand;
        // Wisch-Bewertung in der Kartenmitte stört das nicht, daher immer an.
        userSwipeEnabled = true,
    ) {
        composable("decks") {
            Box(Modifier.fillMaxSize()) {
            DeckList(
                listState, sync, decks, onSync = ::runSync, onOpen = { nav.navigate("review/${it.deckId}") },
                onResolveConflict = { syncer.sync(resolve = it) },
                onDismissFullDownload = syncer::dismissConfirmation,
                // Filter/Anheften/Einklappen und Zugang zu den Einstellungen
                settings = settings, onSettingsChange = store::update, onSettings = { nav.navigate("settings") },
            )

            if (exitArmed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Toast(stringResource(R.string.back_again_to_exit), Modifier.padding(bottom = TOAST_BOTTOM))
                }
            }
            }
        }
        // Einstellungsseiten (SettingsScreen.kt)
        settingsGraph(nav, repo) { decks }
        composable("review/{deckId}") { entry ->
            val deckId = entry.arguments?.getString("deckId")?.toLongOrNull()
            val deck = decks.firstOrNull { it.deckId == deckId } ?: return@composable
            ReviewScreen(repo, deck, onBack = {
                nav.popBackStack()
                // Zahlen sofort lokal neu laden, nicht erst nach dem Abgleich.
                scope.launch { runCatching { repo.decks() } }
                // Nur beim tatsächlichen Verlassen des Decks, nicht beim Abbau
                // der Activity durch Wear OS im Hintergrund.
                if (store.current.syncOnDeckExit) runSync()
            })
        }
        composable("preview/{cardId}") { entry ->
            val cid = entry.arguments?.getString("cardId")?.toLongOrNull() ?: return@composable
            ReviewScreen(repo, deck = null, previewCard = cid, onBack = { nav.popBackStack() })
        }
    }
    // Außerhalb des NavHost: in dessen Startziel kommt Zurück auf der Uhr nicht an.
    // Zurück-Taste und Zurückwischen landen beide hier.
    val activity = context as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = route == "decks" && settings.confirmExit) {
        if (exitArmed) activity?.finish() else exitArmed = true
    }
    }
}
