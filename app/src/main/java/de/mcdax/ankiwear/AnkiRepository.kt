package de.mcdax.ankiwear

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
 * Alle Methoden blockieren; Aufrufer laufen auf Dispatchers.IO.
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
            System.loadLibrary("rsdroid")
        }
    }

    private val backend: Backend = BackendFactory.getBackend()
    private val dir = context.filesDir
    private val collectionFile = File(dir, "collection.anki2")
    private val mediaDir = File(dir, "collection.media").apply { mkdirs() }
    private val mediaDb = File(dir, "collection.media.db")
    private var auth: SyncAuth? = null
    // [settings] Gespeicherter Host-Key und Uhr-Einstellungen (WLAN, Medien)
    private val credentials = SyncCredentials(context)
    private val settings = SettingsStore.get(context)

    // Der Kern verträgt keine gleichzeitigen Aufrufe; während des Vollabgleichs ist die Sammlung zu.
    private val lock = Mutex()

    init {
        openCollection()
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
            // Kein WLAN in 15 s (z. B. im Emulator ohne Uhr-WLAN): mit dem Standardnetz versuchen.
            // [settings] Außer „Nur WLAN“ ist eingeschaltet.
            if (network == null && settings.current.wifiOnly) error(context.getString(R.string.no_wifi))
            if (network != null) cm.bindProcessToNetwork(network)
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
    suspend fun signIn(endpoint: String, user: String, password: String): SyncResult = lock.withLock {
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
    suspend fun signOut() = lock.withLock {
        credentials.clear()
        auth = null
    }

    val account: Account get() = credentials.account

    /**
     * Normaler Sync; beim ersten Mal (leere Sammlung auf der Uhr) ein Vollabgleich als Download.
     * Ein Vollupload von der Uhr wird bewusst nie ausgelöst, er würde den Server überschreiben.
     */
    suspend fun sync(): SyncResult = lock.withLock {
        try {
            onWifi { syncLocked() }
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun syncLocked(): SyncResult {
        val auth = login()
        val result = backend.syncCollection(auth, false)
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
        // [settings] Medien: „Nur gewählte Decks“ kann der Kern nicht, lädt vorerst wie „Alles“ (siehe INTEGRATION.md).
        if (settings.current.mediaMode != MediaMode.NONE) syncMedia(auth)
        return SyncResult.Ok
    }

    private fun syncMedia(auth: SyncAuth) {
        backend.syncMedia(auth)
        // Blockiert bewusst: läuft auf Dispatchers.IO unter dem Sync-Lock.
        while (backend.mediaSyncStatus().active) Thread.sleep(500)
    }

    suspend fun decks(): List<DeckTreeNode> = lock.withLock {
        val root = backend.deckTree(System.currentTimeMillis() / 1000)
        val out = mutableListOf<DeckTreeNode>()
        fun walk(node: DeckTreeNode) {
            node.childrenList.forEach { out += it; walk(it) }
        }
        walk(root)
        out
    }

    /** Einmal bei der Deckwahl; schreibt die Config, daher nicht pro Karte. */
    suspend fun selectDeck(deckId: Long) = lock.withLock { backend.setCurrentDeck(deckId) }

    suspend fun nextCard(): ReviewCard? = lock.withLock {
        val queued = backend.getQueuedCards(1, false).cardsList.firstOrNull() ?: return@withLock null
        val rendered = backend.renderExistingCard(queued.card.id, false, false)
        fun nodes(list: List<anki.card_rendering.RenderedTemplateNode>) =
            list.joinToString("") { if (it.hasText()) it.text else it.replacement.currentText }
        ReviewCard(
            queued = queued,
            question = parseSide(nodes(rendered.questionNodesList)),
            answer = parseSide(answerPart(nodes(rendered.answerNodesList))),
            intervals = backend.describeNextStates(queued.states),
            shownAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun answer(card: ReviewCard, rating: CardAnswer.Rating) = lock.withLock {
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
    }
}
