package de.mcdax.wristcards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import anki.decks.DeckTreeNode
import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import anki.sync.FullUploadOrDownloadRequest
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse.ChangesRequired
import anki.sync.SyncLoginRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendFactory
import net.ankiweb.rsdroid.exceptions.BackendInvalidInputException
import java.io.File
import kotlin.coroutines.resume

/** A card as the watch shows it. */
data class ReviewCard(
    val queued: QueuedCards.QueuedCard,
    val question: CardSide,
    val answer: CardSide,
    // Intervals for Again/Hard/Good/Easy, e.g. "<1m", "6m", "1d", "4d"
    val intervals: List<String>,
    val shownAtMillis: Long,
)

/** Result of a sync or sign-in. */
sealed interface SyncResult {
    data object Ok : SyncResult
    data class Failed(val message: String) : SyncResult
    /**
     * Server and watch both have incompatible changes (e.g. note type changed on the PC, different account).
     * One side has to win, so only after asking: sync(resolve = …).
     */
    data object NeedsFullDownload : SyncResult
}

/** How a conflict is resolved once the user has confirmed it. */
enum class ConflictChoice {
    /** Replace the watch's collection with the server's; ratings made on the watch since the last sync are lost. */
    USE_SERVER,
    /** Overwrite the server with the watch's collection; changes made on the PC since the last sync are lost. */
    USE_WATCH,
}

/**
 * Holds the Anki collection on the watch and talks directly to the sync server.
 * Suspending core calls run serialized on Dispatchers.IO.
 * Initialization also happens on IO (app and complication).
 */
class AnkiRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: AnkiRepository? = null

        /** Whether the collection is already open in this process (then computing fresh is cheap). */
        val isLoaded: Boolean get() = instance != null

        /** Exactly one backend per process, even when the activity is recreated. */
        fun get(context: Context): AnkiRepository =
            instance ?: synchronized(this) {
                instance ?: AnkiRepository(context.applicationContext).also { instance = it }
            }

        // The backend artifact does not load its native library itself (AnkiDroid does that at app start).
        init {
            val start = android.os.SystemClock.elapsedRealtime()
            System.loadLibrary("rsdroid")
            if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "native=${android.os.SystemClock.elapsedRealtime()-start}ms thread=${Thread.currentThread().name}")
        }
    }

    // Watch language, so the core translates intervals ("2 d") and messages like Anki itself.
    private val backend: Backend = BackendFactory.getBackend(
        context.resources.configuration.locales.let { l -> (0 until l.size()).map { l[it].toLanguageTag() } },
    )
    private val dir = context.filesDir
    private val collectionFile = File(dir, "collection.anki2")
    private val mediaDir = File(dir, "collection.media").apply { mkdirs() }
    private val mediaDb = File(dir, "collection.media.db")

    private val mediaRoot by lazy { mediaDir.canonicalFile.toPath() }

    /**
     * File from the media folder; null if not (yet) synced or outside the folder.
     * Touches the file system, so call only on IO.
     */
    fun mediaFile(name: String): File? = runCatching {
        File(mediaDir, android.net.Uri.decode(name)).canonicalFile
            // Compare path components, not characters: otherwise "collection.media.db" would match too.
            .takeIf { it.toPath().startsWith(mediaRoot) && it.isFile }
    }.getOrNull()
    private var auth: SyncAuth? = null
    // Stored host key and watch settings (Wi-Fi, media)
    private val credentials = SyncCredentials(context)
    private val settings = SettingsStore.get(context)

    // The core cannot handle concurrent calls; during a full sync the collection is closed.
    private val lock = Mutex()
    // Network binding and sign-in/sync never run twice at once; separate from [lock] so waiting
    // for Wi-Fi and the media sync do not block studying.
    private val networkLock = Mutex()

    // Undo step of the last rating; undo reverts exactly that one.
    private var lastAnswerStep = -1

    // Prepared display only, never stored scheduler states.
    // Read/write only under lock; keep small for the ARM32 watch.
    private val renderedCards = linkedMapOf<Long, Pair<CardSide, CardSide>>()
    private var renderGeneration = 0L
    private fun invalidateRenderedCards() {
        renderedCards.clear()
        renderGeneration++
    }

    /** Optional preloading: no deck switch, no rating, no network. */
    suspend fun preloadUpcomingCards() {
        val (generation, ids) = io {
            renderGeneration to backend.getQueuedCards(4, false).cardsList.map { it.card.id }
        }
        for (id in ids) {
            kotlinx.coroutines.yield()
            io {
                if (generation == renderGeneration) renderedSides(id)
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { block() } }

    init {
        val start = android.os.SystemClock.elapsedRealtime()
        openCollection()
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "open=${android.os.SystemClock.elapsedRealtime()-start}ms thread=${Thread.currentThread().name}")
    }

    private fun openCollection() {
        backend.openCollection(collectionFile.path, mediaDir.path, mediaDb.path)
    }

    private fun reopenAfterFullSync() {
        try {
            openCollection()
        } catch (_: BackendInvalidInputException.BackendCollectionAlreadyOpenException) {
            // If the full sync failed before closing, the collection is still open.
        }
    }

    /**
     * Wear OS otherwise routes traffic via the phone (Bluetooth), from where the home network
     * is not necessarily reachable. So explicitly request Wi-Fi for the duration of the sync.
     * This also applies to the Rust core's sockets, because the binding is process-wide.
     */
    private suspend fun <T> onWifi(block: suspend () -> T): T {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
        // Everything in try: even if cancelled while waiting, the Wi-Fi request must be released.
        try {
            val network = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Network> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resume(network)
                        }
                    }
                    callback = cb
                    cm.requestNetwork(
                        NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                        cb,
                    )
                }
            }
            // No Wi-Fi within 15 s: use the default network, usually the phone via Bluetooth.
            // If that is the watch's own LTE, only with "Also sync over cellular".
            val target = network ?: cm.activeNetwork?.also { fallback ->
                val caps = cm.getNetworkCapabilities(fallback)
                val cellular = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (cellular && !settings.current.cellularSync) error(context.getString(R.string.no_wifi))
            }
            // Bind firmly so the core does not switch to another network (e.g. LTE) mid-sync.
            if (target != null) cm.bindProcessToNetwork(target)
            return block()
        } finally {
            cm.bindProcessToNetwork(null)
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    // Order: stored host key, otherwise (never signed in) the BuildConfig values.
    private fun login(): SyncAuth = auth ?: credentials.load()?.also { auth = it } ?: run {
        if (credentials.account == Account.SignedOut) error(context.getString(R.string.not_signed_in))
        backend.syncLogin(
            SyncLoginRequest.newBuilder()
                .setUsername(BuildConfig.SYNC_USER)
                .setPassword(BuildConfig.SYNC_PASSWORD)
                .setEndpoint(BuildConfig.SYNC_ENDPOINT)
                .build()
        ).also { auth = it }
    }

    /** Sign-in from the settings; only the host key is stored. */
    suspend fun signIn(endpoint: String, user: String, password: String): SyncResult = withContext(Dispatchers.IO) {
        networkLock.withLock {
            try {
                val url = normalizeEndpoint(endpoint)
                val result = onWifi {
                    lock.withLock {
                        backend.syncLogin(
                            SyncLoginRequest.newBuilder()
                                .setUsername(user)
                                .setPassword(password)
                                .apply { if (url.isNotEmpty()) setEndpoint(url) }
                                .build()
                        )
                    }
                }
                lock.withLock {
                    invalidateRenderedCards()
                    credentials.save(result, user, url)
                    _accountState.value = credentials.account
                    auth = credentials.load() ?: result
                }
                SyncResult.Ok
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Sign out: delete the host key, no fallback to BuildConfig anymore. */
    suspend fun signOut() = io {
        invalidateRenderedCards()
        credentials.clear()
        _accountState.value = credentials.account
        auth = null
    }

    val account: Account get() = credentials.account

    private val _accountState = kotlinx.coroutines.flow.MutableStateFlow(credentials.account)

    /** Current sign-in state; the UI shows only the welcome screen while signed out. */
    val accountState: kotlinx.coroutines.flow.StateFlow<Account> get() = _accountState

    /**
     * Normal sync; the first time (empty collection on the watch) a full sync as download.
     * A full upload from the watch is deliberately never triggered, it would overwrite the server.
     * The core lock only covers the collection sync; waiting for Wi-Fi and the media sync
     * run alongside so you can keep studying meanwhile.
     */
    suspend fun sync(resolve: ConflictChoice? = null): SyncResult = withContext(Dispatchers.IO) {
        networkLock.withLock {
            try {
                onWifi {
                    val (result, auth) = lock.withLock {
                        invalidateRenderedCards()
                        syncCollectionLocked(resolve)
                    }
                    if (result == SyncResult.Ok && auth != null && settings.current.mediaMode != MediaMode.NONE) syncMedia(auth)
                    result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Collection sync under [lock]; also returns the sign-in (possibly switched to the AnkiWeb host). */
    private fun syncCollectionLocked(resolve: ConflictChoice?): Pair<SyncResult, SyncAuth?> {
        var auth = login()
        val result = backend.syncCollection(auth, false)
        // On the first sync AnkiWeb names the responsible sync host. Full sync and media must go there,
        // otherwise the default host answers with 400 "missing original size" (desktop Anki does the same).
        if (result.hasNewEndpoint() && result.newEndpoint.isNotEmpty() && result.newEndpoint != auth.endpoint) {
            auth = auth.toBuilder().setEndpoint(result.newEndpoint).build()
            this.auth = auth
            credentials.updateEndpoint(result.newEndpoint)
        }
        when (result.required) {
            ChangesRequired.NO_CHANGES, ChangesRequired.NORMAL_SYNC -> Unit
            // FULL_DOWNLOAD: the watch has nothing of its own (first sync), downloading loses nothing.
            // FULL_SYNC: both sides have changes; one side loses, so only after asking.
            ChangesRequired.FULL_DOWNLOAD, ChangesRequired.FULL_SYNC -> {
                if (result.required == ChangesRequired.FULL_SYNC && resolve == null) {
                    return SyncResult.NeedsFullDownload to null
                }
                // Upload only if the user explicitly confirmed it for a conflict; never otherwise.
                val upload = result.required == ChangesRequired.FULL_SYNC && resolve == ConflictChoice.USE_WATCH
                // The core closes the collection itself for the full sync (like Anki's close_for_full_sync);
                // it has to be reopened afterwards.
                try {
                    backend.fullUploadOrDownload(
                        FullUploadOrDownloadRequest.newBuilder()
                            .setAuth(auth)
                            .setUpload(upload)
                            .setServerUsn(result.serverMediaUsn)
                            .build()
                    )
                } finally {
                    reopenAfterFullSync()
                }
            }
            ChangesRequired.FULL_UPLOAD ->
                return SyncResult.Failed(context.getString(R.string.upload_required)) to null
            else ->
                return SyncResult.Failed(context.getString(R.string.unknown_sync, result.required.toString())) to null
        }
        refreshDueComplication(context)
        return SyncResult.Ok to auth
    }

    /** The core syncs media in its own thread; here only start it and wait for the end. */
    private suspend fun syncMedia(auth: SyncAuth) {
        lock.withLock { backend.syncMedia(auth) }
        try {
            while (lock.withLock { backend.mediaSyncStatus().active }) kotlinx.coroutines.delay(500)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { lock.withLock { runCatching { backend.abortMediaSync() } } }
            throw e
        }
    }

    private val _deckTree = kotlinx.coroutines.flow.MutableStateFlow<List<DeckTreeNode>>(emptyList())

    /** Most recently loaded deck list; every [decks] query updates it, the UI observes it. */
    val deckTree: kotlinx.coroutines.flow.StateFlow<List<DeckTreeNode>> get() = _deckTree

    suspend fun decks(): List<DeckTreeNode> = withContext(Dispatchers.IO) {
        lock.withLock {
            val start = android.os.SystemClock.elapsedRealtime()
            val root = backend.deckTree(System.currentTimeMillis() / 1000)
            val out = mutableListOf<DeckTreeNode>()
            fun walk(node: DeckTreeNode) {
                node.childrenList.forEach { out += it; walk(it) }
            }
            walk(root)
            if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "decks=${android.os.SystemClock.elapsedRealtime()-start}ms thread=${Thread.currentThread().name}")
            _deckTree.value = out
            out
        }
    }

    /** Cards reviewed since the start of today's Anki day (manual rescheduling excluded). */
    suspend fun studiedToday(): Int = io {
        val dayStartMs = (backend.schedTimingToday().nextDayAt - 86_400) * 1000
        val result = backend.fullQueryProto(
            "select count(distinct cid) from revlog where id > ? and type in (0, 1, 2, 3)", arrayOf(dayStartMs),
        ).result
        result.rowsList.firstOrNull()?.getFields(0)?.longValue?.toInt() ?: 0
    }

    /** Once when choosing a deck; writes the config, so not per card. */
    suspend fun selectDeck(deckId: Long) = io { backend.setCurrentDeck(deckId) }

    suspend fun nextCard(): ReviewCard? = io {
        val queued = backend.getQueuedCards(1, false).cardsList.firstOrNull() ?: return@io null
        build(queued)
    }

    /** Only for emulator tests: show a specific card (adb ... --el card <id>). */
    suspend fun cardById(cardId: Long): ReviewCard = io {
        build(
            QueuedCards.QueuedCard.newBuilder()
                .setCard(backend.getCard(cardId))
                .setStates(backend.getSchedulingStates(cardId))
                .build()
        )
    }

    private fun renderedSides(id: Long): Pair<CardSide, CardSide> {
        renderedCards[id]?.let {
            if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "render cache hit")
            return it
        }
        val rendered = backend.renderExistingCard(id, false, false)
        fun nodes(list: List<anki.card_rendering.RenderedTemplateNode>) =
            list.joinToString("") { if (it.hasText()) it.text else it.replacement.currentText }
        val q = backend.extractAvTags(nodes(rendered.questionNodesList), true)
        val a = backend.extractAvTags(nodes(rendered.answerNodesList), false)
        fun files(tags: List<anki.card_rendering.AVTag>) = tags.map { if (it.hasSoundOrVideo()) it.soundOrVideo else null }
        // Resolve audio files right here (IO), not later in the UI.
        fun CardSide.withFiles() = copy(audioFiles = audio.map { name -> name?.let(::mediaFile) })
        val sides = parseSide(q.text, files(q.avTagsList), isAnswer = false).withFiles() to
            parseSide(answerPart(a.text), files(a.avTagsList), isAnswer = true).withFiles()
        renderedCards[id] = sides
        while (renderedCards.size > 8) renderedCards.remove(renderedCards.keys.first())
        return sides
    }

    private fun build(queued: QueuedCards.QueuedCard): ReviewCard {
        val sides = renderedSides(queued.card.id)
        return ReviewCard(
            queued = queued,
            question = sides.first,
            answer = sides.second,
            // Always fresh: no scheduled states/intervals from the preload buffer.
            intervals = backend.describeNextStates(queued.states),
            shownAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun answer(card: ReviewCard, rating: CardAnswer.Rating) = io {
        renderedCards.remove(card.queued.card.id)
        val states = card.queued.states
        val newState = when (rating) {
            CardAnswer.Rating.AGAIN -> states.again
            CardAnswer.Rating.HARD -> states.hard
            CardAnswer.Rating.EASY -> states.easy
            else -> states.good
        }
        val now = System.currentTimeMillis()
        backend.answerCard(
            CardAnswer.newBuilder()
                .setCardId(card.queued.card.id)
                .setCurrentState(states.current)
                .setNewState(newState)
                .setRating(rating)
                .setAnsweredAtMillis(now)
                .setMillisecondsTaken((now - card.shownAtMillis).coerceAtMost(60_000).toInt())
                .build()
        )
        lastAnswerStep = backend.getUndoStatus().lastStep
    }

    /**
     * Undo the last rating (Anki's own undo); then show the card again.
     * Only if the last undoable action really is this rating.
     */
    suspend fun undoAnswer(cardId: Long): ReviewCard = io {
        check(lastAnswerStep >= 0 && backend.getUndoStatus().lastStep == lastAnswerStep) { "Nothing to undo" }
        backend.undo()
        lastAnswerStep = -1
        renderedCards.remove(cardId)
        build(
            QueuedCards.QueuedCard.newBuilder()
                .setCard(backend.getCard(cardId))
                .setStates(backend.getSchedulingStates(cardId))
                .build()
        )
    }
}
