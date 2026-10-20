package app.guardianmode.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import app.guardianmode.BuildConfig
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.GuardianJson
import app.guardianmode.data.ChangeItem
import app.guardianmode.data.HelperInfo
import app.guardianmode.data.LocalStore
import app.guardianmode.data.Pairing
import app.guardianmode.data.PhotoItem
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class InvitePreview(val familyName: String, val parentName: String, val helperName: String)

/** What a status report tells helpers about this phone's protection. */
data class DeviceStatus(
    val protected: Boolean,
    val missing: List<String>,
    val installedApps: List<Pair<String, String>>,
)

/**
 * The phone's link to the family's cloud space. Owns Firebase setup, pairing, the
 * live config listener and the event outbox upload. Everything here is best-effort
 * and retried: the phone must keep protecting the parent while offline.
 */
class CloudSync(
    private val context: Context,
    private val store: LocalStore,
    private val scope: CoroutineScope,
) {
    private val app: FirebaseApp by lazy { initFirebase() }
    private val auth by lazy { FirebaseAuth.getInstance(app) }
    private val db by lazy { FirebaseFirestore.getInstance(app) }
    private val storage by lazy { FirebaseStorage.getInstance(app) }
    private val functions by lazy { FirebaseFunctions.getInstance(app, REGION) }

    private val listeners = mutableListOf<ListenerRegistration>()
    private var outboxJob: Job? = null
    private val uploadLock = Mutex()
    private var started = false

    /** The config as this phone last received it, normalized: the base for [pushConfigFromPhone]'s merge. */
    @Volatile private var lastRemote: JsonObject? = null

    private val _pendingRequests = MutableStateFlow<List<ChangeRequest>>(emptyList())

    /** Helpers' risky changes waiting for the parent's yes or no, oldest first. Live while listening. */
    val pendingRequests: StateFlow<List<ChangeRequest>> = _pendingRequests.asStateFlow()

    private fun initFirebase(): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.let { return it }
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            .build()
        val created = FirebaseApp.initializeApp(context, options)
        if (BuildConfig.USE_EMULATORS) {
            val host = BuildConfig.EMULATOR_HOST
            FirebaseAuth.getInstance(created).useEmulator(host, 9099)
            FirebaseFirestore.getInstance(created).useEmulator(host, 8080)
            FirebaseStorage.getInstance(created).useEmulator(host, 9199)
            FirebaseFunctions.getInstance(created, REGION).useEmulator(host, 5001)
        }
        return created
    }

    /** Firebase must exist before Android delivers any push message to the app. */
    fun initialize() {
        app
    }

    private suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        return auth.signInAnonymously().await().user?.uid ?: error("Anonymous sign-in returned no user")
    }

    suspend fun previewInvite(code: String): InvitePreview {
        ensureSignedIn()
        val result = functions.getHttpsCallable("previewInvite").call(mapOf("code" to normalizeCode(code))).await()
        val data = result.data as? Map<*, *> ?: error("Unexpected response")
        return InvitePreview(
            familyName = data["familyName"] as? String ?: "",
            parentName = data["parentName"] as? String ?: "",
            helperName = data["helperName"] as? String ?: "",
        )
    }

    suspend fun claimInvite(code: String, helperName: String): Pairing {
        ensureSignedIn()
        val result = functions.getHttpsCallable("claimDeviceInvite").call(
            mapOf(
                "code" to normalizeCode(code),
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "appVersion" to BuildConfig.VERSION_NAME,
            ),
        ).await()
        val data = result.data as? Map<*, *> ?: error("Unexpected response")
        val pairing = Pairing(
            familyId = data["familyId"] as String,
            deviceId = data["deviceId"] as String,
            helperName = helperName,
        )
        store.update { it.copy(pairing = pairing) }
        start()
        return pairing
    }

    /** Starts listeners and the outbox uploader once the phone is paired. Safe to call repeatedly. */
    fun start() {
        val pairing = store.current.pairing ?: return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            runCatching { ensureSignedIn() }.onFailure { Log.w(TAG, "Sign-in failed; will retry on next start", it) }
            listen(pairing)
            registerPushToken()
        }
        outboxJob = scope.launch {
            store.state.map { it.outbox }.distinctUntilChanged().filter { it.isNotEmpty() }.collect { flushOutbox() }
        }
    }

    fun stop() {
        synchronized(this) { started = false }
        listeners.forEach { it.remove() }
        listeners.clear()
        outboxJob?.cancel()
    }

    private fun deviceDoc(p: Pairing) = db.collection("families").document(p.familyId).collection("devices").document(p.deviceId)

    private fun listen(p: Pairing) {
        val device = deviceDoc(p)
        listeners += device.collection("config").document("current").addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            // Our own write echoing back must not overwrite newer local edits.
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            val data = snap.data ?: return@addSnapshotListener
            val config = runCatching {
                GuardianJson.json.decodeFromJsonElement(DeviceConfig.serializer(), FirestoreJson.toJson(data))
            }.getOrElse {
                Log.w(TAG, "Ignoring unreadable config", it)
                return@addSnapshotListener
            }
            lastRemote = encode(config)
            scope.launch { store.update { it.copy(config = config) } }
        }
        listeners += device.collection("requests").whereEqualTo("status", "pending")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                _pendingRequests.value = snap.documents.sortedBy { it.getTimestamp("createdAt")?.seconds ?: 0L }.mapNotNull { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val changes = doc.get("changes") as? Map<String, Any?> ?: return@mapNotNull null
                    ChangeRequest(
                        id = doc.id,
                        byUid = doc.getString("byUid") ?: return@mapNotNull null,
                        byName = doc.getString("byName") ?: "",
                        changes = changes,
                    )
                }
            }
        listeners += device.collection("photos").orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                scope.launch {
                    val known = store.current.photos.associateBy { it.id }
                    val photos = snap.documents.mapNotNull { doc ->
                        known[doc.id]?.let { return@mapNotNull it }
                        val path = doc.getString("storagePath") ?: return@mapNotNull null
                        val url = runCatching { storage.reference.child(path).downloadUrl.await().toString() }.getOrNull()
                            ?: return@mapNotNull null
                        PhotoItem(
                            id = doc.id,
                            url = url,
                            fromName = doc.getString("fromName") ?: "",
                            caption = doc.getString("caption") ?: "",
                            createdAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                        )
                    }
                    store.update { it.copy(photos = photos) }
                }
            }
        listeners += device.collection("changes").orderBy("at", Query.Direction.DESCENDING).limit(10)
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                val changes = snap.documents.map { doc ->
                    ChangeItem(
                        id = doc.id,
                        byName = doc.getString("byName") ?: "",
                        summary = doc.getString("summary") ?: "",
                        at = doc.getTimestamp("at")?.toDate()?.time ?: 0,
                    )
                }
                scope.launch { store.update { it.copy(changes = changes) } }
            }
        listeners += db.collection("families").document(p.familyId).collection("members")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                val helpers = snap.documents.map { doc ->
                    HelperInfo(
                        uid = doc.id,
                        name = doc.getString("displayName") ?: "",
                        photoUrl = doc.getString("photoUrl"),
                        role = doc.getString("role") ?: "helper",
                        status = doc.getString("status") ?: "active",
                    )
                }
                scope.launch { store.update { it.copy(helpers = helpers) } }
            }
    }

    suspend fun flushOutbox() = uploadLock.withLock {
        val p = store.current.pairing ?: return@withLock
        val pending = store.current.outbox
        if (pending.isEmpty()) return@withLock
        runCatching { ensureSignedIn() }.onFailure { return@withLock }
        val uploaded = mutableSetOf<String>()
        for (event in pending) {
            val result = runCatching { deviceDoc(p).collection("events").document(event.id).set(eventMap(event)).await() }
            val error = result.exceptionOrNull()
            // Events are create-only, so a retry of an event that already reached the server is
            // denied. Retrying a denied event can never succeed; drop it instead of blocking the queue.
            if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Log.w(TAG, "Event ${event.id} rejected by the server, dropping it", error)
            } else if (error != null) {
                Log.w(TAG, "Event upload failed, will retry", error)
                break
            }
            uploaded += event.id
        }
        if (uploaded.isNotEmpty()) store.removeFromOutbox(uploaded)
    }

    private fun eventMap(event: GuardianEvent): Map<String, Any> = mapOf(
        "type" to event.type.name,
        "severity" to event.severity.name,
        "at" to event.at,
        "data" to event.data,
        "source" to "device",
        "createdAt" to FieldValue.serverTimestamp(),
    )

    /** The daily "I'm OK" signal: only a timestamp, nothing about what was done. */
    suspend fun heartbeat() {
        val p = store.current.pairing ?: return
        runCatching {
            ensureSignedIn()
            deviceDoc(p).set(
                mapOf("status" to mapOf("lastHeartbeatAt" to FieldValue.serverTimestamp(), "lastSeenAt" to FieldValue.serverTimestamp())),
                SetOptions.merge(),
            ).await()
        }.onFailure { Log.w(TAG, "Heartbeat failed", it) }
    }

    suspend fun reportStatus(status: DeviceStatus) {
        val p = store.current.pairing ?: return
        runCatching {
            ensureSignedIn()
            deviceDoc(p).set(
                mapOf(
                    "status" to mapOf(
                        "protected" to status.protected,
                        "missing" to status.missing,
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                    ),
                    "installedApps" to status.installedApps.map { (pkg, label) -> mapOf("packageName" to pkg, "label" to label) },
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "androidVersion" to Build.VERSION.RELEASE,
                ),
                SetOptions.merge(),
            ).await()
        }.onFailure { Log.w(TAG, "Status report failed", it) }
    }

    suspend fun reportConsent(features: Map<String, Boolean>, version: Int) {
        val p = store.current.pairing ?: return
        runCatching {
            deviceDoc(p).set(
                mapOf("consent" to mapOf("version" to version, "acceptedAt" to FieldValue.serverTimestamp(), "features" to features)),
                SetOptions.merge(),
            ).await()
        }.onFailure { Log.w(TAG, "Consent report failed", it) }
    }

    private fun encode(config: DeviceConfig): JsonObject =
        GuardianJson.json.encodeToJsonElement(DeviceConfig.serializer(), config).jsonObject

    /** A stored config document, normalized the way the phone reads it (defaults filled, unknown keys dropped). */
    private fun normalize(data: Map<String, Any?>): JsonObject? = runCatching {
        encode(GuardianJson.json.decodeFromJsonElement(DeviceConfig.serializer(), FirestoreJson.toJson(data)))
    }.getOrNull()

    private fun configDoc(config: JsonObject, parentName: String): Map<String, Any?> =
        FirestoreJson.toMap(config) + mapOf(
            "updatedBy" to mapOf("uid" to (auth.currentUser?.uid ?: ""), "name" to parentName, "kind" to "device"),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    /**
     * The parent changed a setting on the phone; helpers see it on the dashboard. Written in a
     * transaction as a three-way merge against the last config this phone received, so a
     * helper's edit to another setting that landed meanwhile isn't overwritten.
     */
    suspend fun pushConfigFromPhone(config: DeviceConfig) {
        val p = store.current.pairing ?: return
        runCatching {
            ensureSignedIn()
            val ref = deviceDoc(p).collection("config").document("current")
            val local = encode(config)
            val base = lastRemote
            db.runTransaction { tx ->
                val remote = tx.get(ref).data?.let(::normalize)
                val merged = if (base == null || remote == null) local else ConfigMerge.threeWay(base, local, remote) as? JsonObject ?: local
                tx.set(ref, configDoc(merged, config.parentName))
                true
            }.await()
        }.onFailure { Log.w(TAG, "Config push failed", it) }
    }

    /**
     * The parent's yes or no to a helper's risky change (docs/SECURITY.md §3). On yes the
     * phone writes the change into the config itself (attributed to the device); either way
     * the request is marked answered and the server tells every helper.
     */
    suspend fun answerRequest(request: ChangeRequest, approve: Boolean) {
        val p = store.current.pairing ?: return
        ensureSignedIn()
        val requestRef = deviceDoc(p).collection("requests").document(request.id)
        val configRef = deviceDoc(p).collection("config").document("current")
        db.runTransaction { tx ->
            val current = tx.get(requestRef)
            val configSnap = if (approve) tx.get(configRef) else null
            if (current.getString("status") != "pending") return@runTransaction false
            if (approve) {
                val remote = configSnap?.data?.let { data ->
                    runCatching { GuardianJson.json.decodeFromJsonElement(DeviceConfig.serializer(), FirestoreJson.toJson(data)) }.getOrNull()
                } ?: store.current.config
                val updated = ChangeRequests.apply(remote, request.changes)
                tx.set(configRef, configDoc(encode(updated), updated.parentName))
            }
            tx.update(requestRef, mapOf("status" to (if (approve) "approved" else "declined"), "decidedAt" to FieldValue.serverTimestamp()))
            true
        }.await()
        _pendingRequests.value = _pendingRequests.value.filterNot { it.id == request.id }
    }

    suspend fun uploadHelpScreenshot(jpeg: ByteArray, id: String): String? {
        val p = store.current.pairing ?: return null
        val path = "families/${p.familyId}/devices/${p.deviceId}/help/$id.jpg"
        return runCatching {
            ensureSignedIn()
            storage.reference.child(path).putBytes(jpeg).await()
            path
        }.onFailure { Log.w(TAG, "Screenshot upload failed", it) }.getOrNull()
    }

    suspend fun removeHelper(uid: String) {
        val p = store.current.pairing ?: return
        functions.getHttpsCallable("removeHelper").call(mapOf("familyId" to p.familyId, "uid" to uid)).await()
    }

    /** The parent's yes or no to a new helper, made on this phone. */
    suspend fun approveHelper(uid: String, approve: Boolean) {
        val p = store.current.pairing ?: return
        functions.getHttpsCallable("approveHelper").call(mapOf("familyId" to p.familyId, "uid" to uid, "approve" to approve)).await()
    }

    suspend fun leaveFamily() {
        val p = store.current.pairing ?: return
        val told = runCatching {
            functions.getHttpsCallable("deleteDevice").call(mapOf("familyId" to p.familyId, "deviceId" to p.deviceId)).await()
        }.isSuccess
        stop()
        lastRemote = null
        _pendingRequests.value = emptyList()
        // A notice the parent can still read on the transparency screen: leaving is never silent.
        val left = ChangeItem(
            id = "left-${System.currentTimeMillis()}",
            byName = store.current.config.parentName.ifBlank { "You" },
            summary = if (told) "Took this phone out of the family. Your helpers were told." else "Took this phone out of the family.",
            at = System.currentTimeMillis(),
        )
        store.update { it.copy(pairing = null, helpers = emptyList(), photos = emptyList(), changes = listOf(left), outbox = emptyList()) }
        runCatching { auth.signOut() }
    }

    suspend fun registerPushToken(token: String? = null) {
        val p = store.current.pairing ?: return
        runCatching {
            val value = token ?: FirebaseMessaging.getInstance().token.await()
            deviceDoc(p).set(mapOf("fcmToken" to value), SetOptions.merge()).await()
        }.onFailure { Log.w(TAG, "Push token registration failed", it) }
    }

    companion object {
        private const val TAG = "CloudSync"
        private const val REGION = "us-central1"
        private val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toSet()

        fun normalizeCode(raw: String): String =
            raw.uppercase().filter { it in CODE_ALPHABET }

        fun isCompleteCode(raw: String) = normalizeCode(raw).length == 6
    }
}
