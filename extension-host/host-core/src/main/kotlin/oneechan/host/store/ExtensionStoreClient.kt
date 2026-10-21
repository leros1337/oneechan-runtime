package oneechan.host.store

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: port of Suwayomi-Server's ExtensionStoreService
 * (server/src/main/kotlin/suwayomi/tachidesk/manga/impl/extension/ExtensionStoreService.kt)
 * without its database.
 */

import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import suwayomi.tachidesk.manga.impl.extension.github.BaseNetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtension
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtensionRepo
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfo
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfos
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Port of Suwayomi's ExtensionStoreService (MPL-2.0) without its database: resolves a repo URL to
 * its store metadata and lists the store's extensions. Supports:
 *  - legacy repos: `.../index.min.json` (+ sibling `repo.json` for metadata),
 *  - legacy `repo.json` that points to an `index_v2`,
 *  - Mihon extension stores (`index.pb` protobuf, optionally gzip, or the JSON equivalent),
 *  - stores whose extension list lives at a separate `extensionListUrl`.
 */
class ExtensionStoreClient(
    private val client: () -> OkHttpClient,
    private val json: Json,
    private val protoBuf: ProtoBuf = ProtoBuf,
) {
    suspend fun fetchStore(indexUrl: String): ExtensionStore {
        val bytes = fetch(indexUrl)
        var resolvedIndexUrl = indexUrl
        val networkStore: BaseNetworkExtensionStore =
            when (bytes.firstSignificantByte()) {
                '['.code.toByte() -> {
                    require(indexUrl.endsWith("/index.min.json")) { "Provided legacy store url is not valid" }
                    resolvedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                    json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), fetch(resolvedIndexUrl).decodeToString())
                }

                '{'.code.toByte() -> {
                    val text = bytes.decodeToString()
                    try {
                        json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), text)
                    } catch (_: IllegalArgumentException) {
                        json.decodeFromString(NetworkExtensionStore.serializer(), text)
                    }
                }

                else -> protoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)
            }

        val indexV2 = (networkStore as? NetworkLegacyExtensionRepo)?.indexV2
        if (indexV2 != null) return fetchStore(indexV2)
        return networkStore.toExtensionStore(resolvedIndexUrl)
    }

    suspend fun fetchExtensions(store: ExtensionStore): List<ExtensionInfo> {
        val extensionListUrl = store.extensionListUrl
        return when {
            extensionListUrl != null -> {
                val bytes = fetch(extensionListUrl)
                val list =
                    if (bytes.firstSignificantByte() == '{'.code.toByte()) {
                        json.decodeFromString(NetworkExtensionStore.ExtensionList.serializer(), bytes.decodeToString())
                    } else {
                        protoBuf.decodeFromByteArray(NetworkExtensionStore.ExtensionList.serializer(), bytes)
                    }
                list.toExtensionInfos(store)
            }

            !store.isLegacy -> {
                val bytes = fetch(store.indexUrl)
                val networkStore =
                    if (bytes.firstSignificantByte() == '{'.code.toByte()) {
                        json.decodeFromString(NetworkExtensionStore.serializer(), bytes.decodeToString())
                    } else {
                        protoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)
                    }
                checkNotNull(networkStore.extensionList) { "Store ${store.indexUrl} has no extension list" }
                    .toExtensionInfos(store)
            }

            else -> {
                val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
                val text = fetch("$storeBaseUrl/index.min.json").decodeToString()
                json
                    .decodeFromString(kotlinx.serialization.builtins.ListSerializer(NetworkLegacyExtension.serializer()), text)
                    .map { it.toExtensionInfo(store, storeBaseUrl) }
            }
        }
    }

    private suspend fun fetch(url: String): ByteArray = client().getBytesWithFallback(url).gunzipIfNeeded()
}

internal fun ByteArray.gunzipIfNeeded(): ByteArray =
    if (size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()) {
        GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
    } else {
        this
    }

// Like upstream, only the very first byte decides the format: a protobuf index can legitimately
// start with 0x0A (field 1 tag), which is a newline, so whitespace must not be skipped.
internal fun ByteArray.firstSignificantByte(): Byte? = firstOrNull()
