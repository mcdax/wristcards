package de.mcdax.wristcards

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
                painter = androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher),
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
    // Lives here instead of in the deck list so the scroll position survives studying.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    // Sync and deck list live outside the UI and survive an activity rebuild.
    val syncer = remember { SyncController.get(context) }
    val sync by syncer.state.collectAsState()
    val decks by repo.deckTree.collectAsState()
    val account by repo.accountState.collectAsState()
    val signedOut = account == Account.SignedOut
    var decksLoaded by remember { mutableStateOf(false) }
    var startupDrawn by remember { mutableStateOf(false) }
    // Watch settings
    val store = remember { SettingsStore.get(context) }
    val settings by store.flow.collectAsState()
    // Hidden decks do not count in the watch-face complication.
    LaunchedEffect(settings.hiddenDecks) { refreshDueComplication(context) }

    fun runSync() = syncer.sync()

    LaunchedEffect(Unit) {
        decksLoaded = true
        // NavController restores its study view itself. Neither navigate again
        // nor put the startup sync in the shared backend queue before it.
        if (restoring) return@LaunchedEffect
        when {
            previewCard != null -> nav.navigate("preview/$previewCard")
            else -> decks.firstOrNull { it.name == openDeck }?.let { nav.navigate("review/${it.deckId}") }
                ?: run { if (store.current.syncOnOpen && !signedOut) runSync() } // Auto-sync on open
        }
    }

    // Right after signing in: fetch the collection without waiting for a manual sync.
    var wasSignedOut by remember { mutableStateOf(signedOut) }
    LaunchedEffect(signedOut) {
        if (wasSignedOut && !signedOut) runSync()
        wasSignedOut = signedOut
    }

    if (decksLoaded) LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        (context as? android.app.Activity)?.reportFullyDrawn()
        startupDrawn = true
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "decks ready")
    }

    LaunchedEffect(decks, startupDrawn) {
        // Draw the filled deck list first; preloading is not part of the startup path.
        if (!startupDrawn) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        try { repo.preloadUpcomingCards() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("AnkiPerf", "Optional preloading skipped", e) }
    }

    // Swiping right shows the deck list underneath when going back, instead of a black screen.
    // Swipe shortcuts need the right swipe; then leave the deck only via the back button.
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // Make leaving harder: only pressing back twice within 2 s closes the app.
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
        // On newer Wear OS versions the back swipe arrives as a system edge gesture;
        // swipe-to-rate in the middle of the card doesn't interfere, so always on.
        userSwipeEnabled = true,
    ) {
        composable("decks") {
            Box(Modifier.fillMaxSize()) {
            // Nothing to study without an account: show only the sign-in prompt.
            if (signedOut) WelcomeScreen(onSignIn = { nav.navigate("settings/account") }) else
            DeckList(
                listState, sync, decks, onSync = ::runSync, onOpen = { nav.navigate("review/${it.deckId}") },
                onResolveConflict = { syncer.sync(resolve = it) },
                onDismissFullDownload = syncer::dismissConfirmation,
                // Filter/pin/collapse and access to the settings
                settings = settings, onSettingsChange = store::update, onSettings = { nav.navigate("settings") },
            )

            if (exitArmed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Toast(stringResource(R.string.back_again_to_exit), Modifier.padding(bottom = TOAST_BOTTOM))
                }
            }
            }
        }
        // Settings pages (SettingsScreen.kt)
        settingsGraph(nav, repo) { decks }
        composable("review/{deckId}") { entry ->
            val deckId = entry.arguments?.getString("deckId")?.toLongOrNull()
            val deck = decks.firstOrNull { it.deckId == deckId } ?: return@composable
            ReviewScreen(repo, deck, onBack = {
                nav.popBackStack()
                // Reload the numbers locally right away, not only after the sync.
                scope.launch { runCatching { repo.decks() } }
                // Only when actually leaving the deck, not when Wear OS tears down
                // the activity in the background.
                if (store.current.syncOnDeckExit) runSync()
            })
        }
        composable("preview/{cardId}") { entry ->
            val cid = entry.arguments?.getString("cardId")?.toLongOrNull() ?: return@composable
            ReviewScreen(repo, deck = null, previewCard = cid, onBack = { nav.popBackStack() })
        }
    }
    // Outside the NavHost: in its start destination, back doesn't reach us on the watch.
    // Back button and back swipe both end up here.
    val activity = context as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = route == "decks" && settings.confirmExit) {
        if (exitArmed) activity?.finish() else exitArmed = true
    }
    }
}
