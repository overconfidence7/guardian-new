package app.guardianmode.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.GuardianJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private object LocalStateSerializer : Serializer<LocalState> {
    override val defaultValue: LocalState get() = LocalState()

    override suspend fun readFrom(input: InputStream): LocalState = try {
        GuardianJson.json.decodeFromString(LocalState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable local state", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable local state", e)
    }

    override suspend fun writeTo(t: LocalState, output: OutputStream) {
        output.write(GuardianJson.json.encodeToString(LocalState.serializer(), t).encodeToByteArray())
    }
}

/** The phone's persisted state. Reads are a StateFlow; writes are atomic transforms. */
class LocalStore internal constructor(
    private val store: DataStore<LocalState>,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        DataStoreFactory.create(
            serializer = LocalStateSerializer,
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { LocalState() },
            scope = scope,
            produceFile = { File(context.filesDir, "datastore/guardian_state.json") },
        ),
        scope,
    )

    // The launcher must render the right home screen on its very first frame, so the
    // initial read blocks once at startup. The file is a few kilobytes.
    private val initial: LocalState = runBlocking { store.data.first() }

    val state: StateFlow<LocalState> = store.data.stateIn(scope, SharingStarted.Eagerly, initial)

    val current: LocalState get() = state.value

    suspend fun update(transform: (LocalState) -> LocalState): LocalState = store.updateData(transform)

    suspend fun recordShared(event: GuardianEvent) = update {
        it.copy(
            outbox = (it.outbox + event).takeLast(LocalState.MAX_OUTBOX),
            sharedEvents = (listOf(event) + it.sharedEvents).take(LocalState.MAX_SHARED_HISTORY),
        )
    }

    suspend fun removeFromOutbox(ids: Set<String>) = update { s -> s.copy(outbox = s.outbox.filterNot { it.id in ids }) }
}
