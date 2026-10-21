package oneechan.host.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionStoreClientTest {
    private val server = MockWebServer()
    private lateinit var client: ExtensionStoreClient

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/repos/$name")!!.readBytes()

    @BeforeEach
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body: ByteArray =
                        when (request.url.encodedPath) {
                            "/keiyoushi/repo.json" ->
                                fixture("keiyoushi-repo.json")
                                    .decodeToString()
                                    .replace("https://github.com/keiyoushi/extensions/raw/repo/index.pb", server.url("/keiyoushi/index.pb").toString())
                                    .encodeToByteArray()
                            "/keiyoushi/index.pb" -> fixture("keiyoushi-index.pb")
                            "/kavita/index.min.json" -> fixture("kavita-index.min.json")
                            "/kavita/repo.json" -> fixture("kavita-repo.json")
                            "/suwayomi/index.min.json" -> fixture("suwayomi-index.min.json")
                            "/suwayomi/repo.json" -> fixture("suwayomi-repo.json")
                            else -> return MockResponse.Builder().code(404).build()
                        }
                    return MockResponse.Builder().body(Buffer().write(body)).build()
                }
            }
        server.start()
        client = ExtensionStoreClient({ OkHttpClient() }, Json { ignoreUnknownKeys = true; explicitNulls = false })
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `keiyoushi repo json resolves to the protobuf index v2 store`() =
        runTest {
            val store = client.fetchStore(server.url("/keiyoushi/repo.json").toString())
            assertEquals("Keiyoushi", store.name)
            assertFalse(store.isLegacy)
            assertEquals(server.url("/keiyoushi/index.pb").toString(), store.indexUrl)
            assertEquals("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2", store.signingKey)

            val extensions = client.fetchExtensions(store)
            assertTrue(extensions.size > 500, "expected the full keiyoushi catalogue, got ${extensions.size}")
            val mangadex = extensions.single { it.pkgName == "eu.kanade.tachiyomi.extension.all.mangadex" }
            assertEquals("all", mangadex.lang)
            assertTrue(mangadex.apkUrl.endsWith(".apk"))
            assertTrue(mangadex.sources.size > 10, "MangaDex is a multi-language SourceFactory")
            val libs = extensions.groupingBy { it.extensionLib }.eachCount()
            println("keiyoushi: ${extensions.size} extensions, extensionLib distribution $libs")
        }

    @Test
    fun `protobuf index url can be added directly`() =
        runTest {
            val store = client.fetchStore(server.url("/keiyoushi/index.pb").toString())
            assertEquals("Keiyoushi", store.name)
            assertFalse(store.isLegacy)
        }

    @Test
    fun `legacy index min json uses the sibling repo json for metadata`() =
        runTest {
            val store = client.fetchStore(server.url("/kavita/index.min.json").toString())
            assertTrue(store.isLegacy)
            assertEquals("Kavita Extension Repo", store.name)
            assertEquals("Kavita", store.badgeLabel)
            assertEquals(server.url("/kavita/repo.json").toString(), store.indexUrl)
            assertNull(store.extensionListUrl)

            val extensions = client.fetchExtensions(store)
            val kavita = extensions.single()
            assertEquals("Kavita", kavita.name)
            assertEquals("eu.kanade.tachiyomi.extension.all.kavita", kavita.pkgName)
            assertEquals(server.url("/kavita/apk/tachiyomi-all.kavita-v1.4.26.apk").toString(), kavita.apkUrl)
            assertEquals(server.url("/kavita/icon/eu.kanade.tachiyomi.extension.all.kavita.png").toString(), kavita.iconUrl)
            assertEquals("1.4", kavita.extensionLib)
            assertEquals(7266875667263635239L, kavita.sources.first().id)
        }

    @Test
    fun `legacy suwayomi repo parses source ids above 2^53`() =
        runTest {
            val store = client.fetchStore(server.url("/suwayomi/index.min.json").toString())
            val ext = client.fetchExtensions(store).single()
            assertEquals("eu.kanade.tachiyomi.extension.all.tachidesk", ext.pkgName)
            assertEquals(3100117499901280806L, ext.sources.single().id)
            assertEquals(29L, ext.versionCode)
        }

    @Test
    fun `jsdelivr mirror rewrites github raw urls`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.pb",
            JsDelivr.mirror("https://github.com/keiyoushi/extensions/raw/repo/index.pb"),
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/Kareadita/tach-extension@repo/apk/a.apk",
            JsDelivr.mirror("https://raw.githubusercontent.com/Kareadita/tach-extension/repo/apk/a.apk"),
        )
        assertNull(JsDelivr.mirror("https://example.com/index.min.json"))
    }
}
