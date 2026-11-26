package de.mcdax.ankiwear

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

/** Eine Karte, wie die Uhr sie zeigt. */
data class ReviewCard(
    val queued: QueuedCards.QueuedCard,
    val question: CardSide,
    val answer: CardSide,
    // Intervalle für Nochmal/Schwer/Gut/Leicht, z. B. "<1m", "6m", "1d", "4d"
    val intervals: List<String>,
    val shownAtMillis: Long,
)

/** Ergebnis eines Syncs; die Oberfläche zeigt nur „Synced“ oder eine Fehlermeldung. */
sealed interface SyncResult {
    data object Ok : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Hält die Anki-Sammlung auf der Uhr und spricht direkt mit dem Sync-Server.
 * Suspendierende Kern-Aufrufe laufen serialisiert auf Dispatchers.IO.
 * Initialisierung erfolgt ebenfalls auf IO (App und Komplikation).
 */
class AnkiRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: AnkiRepository? = null

        /** Genau ein Backend pro Prozess, auch wenn die Activity neu erstellt wird. */
        fun get(context: Context): AnkiRepository =
            instance ?: synchronized(this) {
                instance ?: AnkiRepository(context.applicationContext).also { instance = it }
            }

        // Das Backend-Artefakt lädt seine native Bibliothek nicht selbst (AnkiDroid macht das beim App-Start).
        init {
            val start = android.os.SystemClock.elapsedRealtime()
            System.loadLibrary("rsdroid")
            if (BuildConfig.DEBUG) android.util.Log.d("AnkiPerf", "native=${android.os.SystemClock.elapsedRealtime()-start}ms thread=${Thread.currentThread().name}")
        }
    }

    // Sprache der Uhr, damit der Kern Intervalle („2 T“) und Meldungen wie Anki selbst übersetzt.
    private val backend: Backend = BackendFactory.getBackend(
        context.resources.configuration.locales.let { l -> (0 until l.size()).map { l[it].toLanguageTag() } },
    )
    private val dir = context.filesDir
    private val collectionFile = File(dir, "collection.anki2")
    private val mediaDir = File(dir, "collection.media").apply { mkdirs() }
    private val mediaDb = File(dir, "collection.media.db")

    /** Datei aus dem Medienordner; null, wenn (noch) nicht synchronisiert. */
    fun mediaFile(name: String): File? =
        File(mediaDir, android.net.Uri.decode(name)).takeIf { it.isFile && it.canonicalPath.startsWith(mediaDir.canonicalPath) }
    private var auth: SyncAuth? = null
    // [settings] Gespeicherter Host-Key und Uhr-Einstellungen (WLAN, Medien)
    private val credentials = SyncCredentials(context)
    private val settings = SettingsStore.get(context)

    // Der Kern verträgt keine gleichzeitigen Aufrufe; während des Vollabgleichs ist die Sammlung zu.
    private val lock = Mutex()

    // Nur vorbereitete Anzeige, niemals gespeicherte Scheduler-Zustände.
    // Ausschließlich unter lock lesen/schreiben; klein halten für die ARM32-Uhr.
    private val renderedCards = linkedMapOf<Long, Pair<CardSide, CardSide>>()
    private var renderGeneration = 0L
    private fun invalidateRenderedCards() {
        renderedCards.clear()
        renderGeneration++
    }

    /** Optionales Vorladen: kein Deckwechsel, kein Bewerten, kein Netzwerk. */
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
            // Scheiterte der Vollabgleich vor dem Schließen, ist die Sammlung noch offen.
        }
    }

    /**
     * Wear OS schickt Verkehr sonst über das Handy (Bluetooth), von dort ist das Heimnetz
     * nicht unbedingt erreichbar. Deshalb für die Dauer des Syncs ausdrücklich WLAN anfordern.
     * Das gilt auch für die Sockets des Rust-Kerns, weil die Bindung prozessweit ist.
     */
    private suspend fun <T> onWifi(block: () -> T): T {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
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
        try {
            // Kein WLAN in 15 s: Standardnetz nehmen, meist das Handy per Bluetooth.
            // [settings] Ist das das eigene LTE der Uhr, nur mit „Cellular sync“.
            val target = network ?: cm.activeNetwork?.also { fallback ->
                val caps = cm.getNetworkCapabilities(fallback)
                val cellular = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (cellular && !settings.current.cellularSync) error(context.getString(R.string.no_wifi))
            }
            // Fest binden, damit der Kern nicht mitten im Sync auf ein anderes Netz (z. B. LTE) wechselt.
            if (target != null) cm.bindProcessToNetwork(target)
            return block()
        } finally {
            cm.bindProcessToNetwork(null)
            callback?.let { cm.unregisterNetworkCallback(it) }
        }
    }

    // [settings] Reihenfolge: gespeicherter Host-Key, sonst (nie angemeldet) die BuildConfig-Werte.
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

    /** [settings] Anmeldung aus den Einstellungen; gespeichert wird nur der Host-Key. */
    suspend fun signIn(endpoint: String, user: String, password: String): SyncResult = io {
        invalidateRenderedCards()
        try {
            val url = normalizeEndpoint(endpoint)
            val result = onWifi {
                backend.syncLogin(
                    SyncLoginRequest.newBuilder()
                        .setUsername(user)
                        .setPassword(password)
                        .apply { if (url.isNotEmpty()) setEndpoint(url) }
                        .build()
                )
            }
            credentials.save(result, user, url)
            auth = credentials.load() ?: result
            SyncResult.Ok
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** [settings] Abmelden: Host-Key löschen, kein Rückfall auf BuildConfig mehr. */
    suspend fun signOut() = io {
        invalidateRenderedCards()
        credentials.clear()
        auth = null
    }

    val account: Account get() = credentials.account

    /**
     * Normaler Sync; beim ersten Mal (leere Sammlung auf der Uhr) ein Vollabgleich als Download.
     * Ein Vollupload von der Uhr wird bewusst nie ausgelöst, er würde den Server überschreiben.
     */
    suspend fun sync(): SyncResult = io {
        invalidateRenderedCards()
        try {
            onWifi { syncLocked() }
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun syncLocked(): SyncResult {
        var auth = login()
        val result = backend.syncCollection(auth, false)
        // AnkiWeb nennt im ersten Abgleich den zuständigen Sync-Host. Vollabgleich und Medien müssen dorthin,
        // sonst antwortet der Standard-Host mit 400 „missing original size“ (so macht es auch Anki am Desktop).
        if (result.hasNewEndpoint() && result.newEndpoint.isNotEmpty() && result.newEndpoint != auth.endpoint) {
            auth = auth.toBuilder().setEndpoint(result.newEndpoint).build()
            this.auth = auth
            credentials.updateEndpoint(result.newEndpoint)
        }
        when (result.required) {
            ChangesRequired.NO_CHANGES, ChangesRequired.NORMAL_SYNC -> Unit
            ChangesRequired.FULL_DOWNLOAD, ChangesRequired.FULL_SYNC -> {
                // Der Kern schließt die Sammlung für den Vollabgleich selbst (wie Ankis close_for_full_sync);
                // danach muss sie wieder geöffnet werden.
                try {
                    backend.fullUploadOrDownload(
                        FullUploadOrDownloadRequest.newBuilder()
                            .setAuth(auth)
                            .setUpload(false)
                            .setServerUsn(result.serverMediaUsn)
                            .build()
                    )
                } finally {
                    reopenAfterFullSync()
                }
            }
            ChangesRequired.FULL_UPLOAD ->
                return SyncResult.Failed(context.getString(R.string.upload_required))
            else ->
                return SyncResult.Failed(context.getString(R.string.unknown_sync, result.required.toString()))
        }
        if (settings.current.mediaMode != MediaMode.NONE) syncMedia(auth)
        refreshDueComplication(context)
        return SyncResult.Ok
    }

    private fun syncMedia(auth: SyncAuth) {
        backend.syncMedia(auth)
        // Blockiert bewusst: läuft auf Dispatchers.IO unter dem Sync-Lock.
        while (backend.mediaSyncStatus().active) Thread.sleep(500)
    }

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
            out
        }
    }

    /** Einmal bei der Deckwahl; schreibt die Config, daher nicht pro Karte. */
    suspend fun selectDeck(deckId: Long) = io { backend.setCurrentDeck(deckId) }

    suspend fun nextCard(): ReviewCard? = io {
        val queued = backend.getQueuedCards(1, false).cardsList.firstOrNull() ?: return@io null
        build(queued)
    }

    /** Nur für Tests im Emulator: eine bestimmte Karte anzeigen (adb ... --el card <id>). */
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
        val sides = parseSide(q.text, files(q.avTagsList), isAnswer = false) to
            parseSide(answerPart(a.text), files(a.avTagsList), isAnswer = true)
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
            // Immer frisch: keine geplanten Zustände/Intervalle aus dem Vorladepuffer.
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
        refreshDueComplication(context)
    }

    /** Letzte Bewertung zurücknehmen (Ankis eigenes Rückgängig); danach die Karte wieder zeigen. */
    suspend fun undoAnswer(cardId: Long): ReviewCard = io {
        backend.undo()
        renderedCards.remove(cardId)
        refreshDueComplication(context)
        build(
            QueuedCards.QueuedCard.newBuilder()
                .setCard(backend.getCard(cardId))
                .setStates(backend.getSchedulingStates(cardId))
                .build()
        )
    }
}
