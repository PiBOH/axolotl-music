package com.music.vivi.desktop.data.sync

import com.music.vivi.desktop.utils.DesktopPathProvider
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The pairing identity the 1.55 desktop wrote at the root of
 * `device-sync.json`: `deviceId`, `deviceName`, `pairId` and `serverUrl`.
 *
 * The 1.56 store owns the `localLibrary` key and preserves every other key, so
 * this class only rewrites the four sync fields and never the library. Keeping
 * the flat 1.55 keys is what lets an existing installation keep its UUID and
 * pair id while the UI moves to the new two-card screen.
 */
data class SyncIdentity(
    val deviceId: String,
    val deviceName: String,
    val pairId: String,
    val serverUrl: String,
    val sharingEnabled: Boolean = true,
)

/**
 * One identity file. The app uses the default [SyncIdentityStore], while the
 * end-to-end harness gives each of its two desktop clients its own instance and
 * therefore its own device identity and saved pair.
 */
class SyncIdentityFile(private val file: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): SyncIdentity = synchronized(lock) {
        val root = readRoot()
        val deviceId = root["deviceId"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { generated ->
                writeRoot(root.putString("deviceId", generated))
            }
        SyncIdentity(
            deviceId = deviceId,
            deviceName = root["deviceName"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
                ?: defaultDeviceName(),
            pairId = root["pairId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            serverUrl = root["serverUrl"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            sharingEnabled = root["syncSharingEnabled"]?.jsonPrimitive?.contentOrNullSafe()
                ?.toBooleanStrictOrNull() ?: true,
        )
    }

    fun update(transform: (SyncIdentity) -> SyncIdentity): SyncIdentity = synchronized(lock) {
        val current = load()
        val updated = transform(current)
        val root = readRoot()
        writeRoot(
            root
                .putString("deviceId", updated.deviceId)
                .putString("deviceName", updated.deviceName)
                .putString("pairId", updated.pairId)
                .putString("serverUrl", updated.serverUrl)
                .putString("syncSharingEnabled", updated.sharingEnabled.toString())
        )
        updated
    }

    private fun readRoot(): JsonObject {
        if (!file.isFile) return buildJsonObject { }
        return runCatching {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: buildJsonObject { }
    }

    private fun writeRoot(root: JsonObject) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.sync.tmp")
        temp.writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun JsonObject.putString(key: String, value: String): JsonObject =
        JsonObject(toMutableMap().apply { put(key, JsonPrimitive(value)) })

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { contentOrNull }.getOrNull()
}

/** The one identity file the running desktop uses. */
object SyncIdentityStore {
    internal val defaultStore: SyncIdentityFile by lazy {
        SyncIdentityFile(DesktopPathProvider.syncStateFile)
    }

    fun load(): SyncIdentity = defaultStore.load()

    fun update(transform: (SyncIdentity) -> SyncIdentity): SyncIdentity = defaultStore.update(transform)
}

private fun defaultDeviceName(): String = runCatching {
    val env = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
    if (!env.isNullOrBlank()) env else java.net.InetAddress.getLocalHost().hostName
}.getOrDefault("Desktop").ifBlank { "Desktop" }
