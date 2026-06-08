// SPDX-License-Identifier: GPL-3.0-or-later
package dev.itsvic.parceltracker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import dev.itsvic.parceltracker.api.Service
import dev.itsvic.parceltracker.db.Parcel
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ParcelBackupEntry(
    val humanName: String,
    val parcelId: String,
    val postalCode: String?,
    val service: String,
    val isArchived: Boolean,
    val archivePromptDismissed: Boolean,
)

@Serializable
data class SettingsBackupEntry(
    val unmeteredOnly: Boolean,
    val dhlApiKey: String,
)

@Serializable
data class BackupFile(
    val version: Int = 1,
    val parcels: List<ParcelBackupEntry>,
    val settings: SettingsBackupEntry,
)

object BackupManager {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exportToUri(context: Context, uri: Uri) {
        val db = ParcelApplication.db
        val parcels = db.parcelDao().getAll().first()
        val prefs = context.dataStore.data.first()

        val backup = BackupFile(
            parcels = parcels.map { p ->
                ParcelBackupEntry(
                    humanName = p.humanName,
                    parcelId = p.parcelId,
                    postalCode = p.postalCode,
                    service = p.service.name,
                    isArchived = p.isArchived,
                    archivePromptDismissed = p.archivePromptDismissed,
                )
            },
            settings = SettingsBackupEntry(
                unmeteredOnly = prefs[UNMETERED_ONLY] ?: false,
                dhlApiKey = prefs[DHL_API_KEY] ?: "",
            ),
        )

        val encoded = Json.encodeToString(backup)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(encoded.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Could not open output stream")
    }

    suspend fun importFromUri(context: Context, uri: Uri) {
        val encoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("Could not open input stream")

        val backup = json.decodeFromString<BackupFile>(encoded)

        val db = ParcelApplication.db
        val existing = db.parcelDao().getAll().first()
        val existingKeys = existing.map { it.parcelId to it.service.name }.toSet()

        backup.parcels
            .filter { (it.parcelId to it.service) !in existingKeys }
            .forEach { entry ->
                val service = try {
                    Service.valueOf(entry.service)
                } catch (e: IllegalArgumentException) {
                    Log.w("BackupManager", "Unknown service '${entry.service}', skipping")
                    return@forEach
                }
                db.parcelDao().insert(
                    Parcel(
                        id = 0,
                        humanName = entry.humanName,
                        parcelId = entry.parcelId,
                        postalCode = entry.postalCode,
                        service = service,
                        isArchived = entry.isArchived,
                        archivePromptDismissed = entry.archivePromptDismissed,
                    )
                )
            }

        context.dataStore.edit { prefs ->
            prefs[UNMETERED_ONLY] = backup.settings.unmeteredOnly
            if (backup.settings.dhlApiKey.isNotEmpty()) {
                prefs[DHL_API_KEY] = backup.settings.dhlApiKey
            }
        }
    }
}
