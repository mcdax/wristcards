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

    /** Datei aus dem Medienordner; null, wenn (noch) nicht synchronisiert. */
    fun mediaFile(name: String): File? =
        File(mediaDir, android.net.Uri.decode(name)).takeIf { it.isFile && it.canonicalPath.startsWith(mediaDir.canonicalPath) }
    private var auth: SyncAuth? = null

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
            if (network != null) cm.bindProcessToNetwork(network)
            return block()
        } finally {
            cm.bindProcessToNetwork(null)
            callback?.let { cm.unregisterNetworkCallback(it) }
        }
    }

    private fun login(): SyncAuth = auth ?: backend.syncLogin(
        SyncLoginRequest.newBuilder()
            .setUsername(BuildConfig.SYNC_USER)
            .setPassword(BuildConfig.SYNC_PASSWORD)
            .setEndpoint(BuildConfig.SYNC_ENDPOINT)
            .build()
    ).also { auth = it }

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
        syncMedia(auth)
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
        build(queued)
    }

    /** Nur für Tests im Emulator: eine bestimmte Karte anzeigen (adb ... --el card <id>). */
    suspend fun cardById(cardId: Long): ReviewCard = lock.withLock {
        build(
            QueuedCards.QueuedCard.newBuilder()
                .setCard(backend.getCard(cardId))
                .setStates(backend.getSchedulingStates(cardId))
                .build()
        )
    }

    private fun build(queued: QueuedCards.QueuedCard): ReviewCard {
        val rendered = backend.renderExistingCard(queued.card.id, false, false)
        fun nodes(list: List<anki.card_rendering.RenderedTemplateNode>) =
            list.joinToString("") { if (it.hasText()) it.text else it.replacement.currentText }
        // Wie Anki: [sound:…] wird zu [anki:play:q:N] bzw. [anki:play:a:N], die Dateien kommen als Liste.
        val q = backend.extractAvTags(nodes(rendered.questionNodesList), true)
        val a = backend.extractAvTags(nodes(rendered.answerNodesList), false)
        fun files(tags: List<anki.card_rendering.AVTag>) = tags.map { if (it.hasSoundOrVideo()) it.soundOrVideo else null }
        return ReviewCard(
            queued = queued,
            question = parseSide(q.text, files(q.avTagsList), isAnswer = false),
            answer = parseSide(answerPart(a.text), files(a.avTagsList), isAnswer = true),
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
