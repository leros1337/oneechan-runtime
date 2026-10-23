package oneechan.host.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RpcException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Enabling and disabling repos (Komikku): the flag persists in `repos.json` and filters offers. */
class RepoStoreTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var dir: Path
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/repos/$name")!!.readBytes()

    private fun store() = RepoStore(dir, json, ExtensionStoreClient({ OkHttpClient() }, json))

    private val kavitaIndex get() = server.url("/kavita/repo.json").toString()
    private val suwayomiIndex get() = server.url("/suwayomi/repo.json").toString()

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("repo-store-test")
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.url.encodedPath
                    val body: ByteArray =
                        when (request.url.encodedPath) {
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
    }

    @AfterEach
    fun tearDown() {
        server.close()
        dir.toFile().deleteRecursively()
    }

    private suspend fun RepoStore.addBoth() {
        add(server.url("/kavita/index.min.json").toString())
        add(server.url("/suwayomi/index.min.json").toString())
    }

    @Test
    fun `repos are enabled when added`() =
        runTest {
            val store = store()
            store.addBoth()
            assertTrue(store.repos().all { it.enabled })
            assertTrue(store.repos().all { it.toDto().enabled })
        }

    @Test
    fun `a disabled repo's extensions leave the available list and come back when enabled`() =
        runTest {
            val store = store()
            store.addBoth()
            assertEquals(setOf("Kavita", "Suwayomi"), store.available().map { it.name }.toSet())

            val disabled = store.setEnabled(kavitaIndex, false)
            assertFalse(disabled.enabled)
            assertEquals(listOf("Suwayomi"), store.available().map { it.name })
            assertEquals(setOf(kavitaIndex), store.disabledIndexUrls())
            // The repo stays configured.
            assertEquals(2, store.repos().size)

            requests.clear()
            store.setEnabled(kavitaIndex, true)
            assertEquals(setOf("Kavita", "Suwayomi"), store.available().map { it.name }.toSet())
            assertTrue(requests.isEmpty(), "the cached list comes back without a network fetch: $requests")
        }

    @Test
    fun `the flag persists in repos json`() =
        runTest {
            store().run {
                addBoth()
                setEnabled(kavitaIndex, false)
            }
            val reopened = store()
            assertFalse(reopened.repos().single { it.indexUrl == kavitaIndex }.enabled)
            assertTrue(reopened.repos().single { it.indexUrl == suwayomiIndex }.enabled)
            assertTrue(Files.readString(dir.resolve("repos.json")).contains("\"enabled\""))
        }

    @Test
    fun `repos saved before the flag existed are enabled`() {
        dir.resolve("repos.json").writeText(
            """[{"indexUrl":"https://r.test/repo.json","name":"Old","badgeLabel":"Old","signingKeyFingerprint":"ab","website":"https://r.test","isLegacy":true}]""",
        )
        assertTrue(store().repos().single().enabled)
    }

    @Test
    fun `refresh does not fetch disabled repos and keeps their cached list`() =
        runTest {
            val store = store()
            store.addBoth()
            store.setEnabled(kavitaIndex, false)
            requests.clear()

            val outcome = store.refresh()
            assertTrue(requests.none { it.startsWith("/kavita/") }, "disabled repo was fetched: $requests")
            assertTrue(requests.any { it.startsWith("/suwayomi/") })
            assertEquals(emptyList(), outcome.failedRepos)
            assertEquals(1, outcome.extensionCount, "only enabled repos' extensions are counted")
            assertFalse(outcome.repos.single { it.indexUrl == kavitaIndex }.enabled)

            store.setEnabled(kavitaIndex, true)
            assertTrue(store.available().any { it.name == "Kavita" })
        }

    @Test
    fun `re-adding a disabled repo keeps it disabled`() =
        runTest {
            val store = store()
            store.addBoth()
            store.setEnabled(kavitaIndex, false)
            store.add(server.url("/kavita/index.min.json").toString())
            assertFalse(store.repos().single { it.indexUrl == kavitaIndex }.enabled)
            assertTrue(store.available().none { it.name == "Kavita" })
        }

    @Test
    fun `an unknown repo cannot be enabled`() =
        runTest {
            val store = store()
            val error = assertThrows<RpcException> { store.setEnabled("https://nowhere.test/repo.json", false) }
            assertEquals(ErrorCode.NOT_FOUND, error.code)
        }

    @Test
    fun `installs ignore disabled repos but their signing keys stay trusted`() =
        runTest {
            val store = store()
            store.addBoth()
            store.setEnabled(kavitaIndex, false)
            assertNull(store.findAvailable("eu.kanade.tachiyomi.extension.all.kavita"))
            assertNull(store.findAvailable("eu.kanade.tachiyomi.extension.all.kavita", kavitaIndex))
            // Installed extensions of a disabled repo keep working: its key still trusts them.
            assertTrue("5887aee6597996821a1f05ec0d88cac45ec2847143278ac6b1efc657bfad6be0" in store.fingerprints())
        }

    // ---- update checks of installed extensions --------------------------------------------

    private fun offer(
        pkg: String,
        repo: String,
        versionCode: Long,
    ) = OfferEntry(pkg, repo, versionCode)

    @Test
    fun `installed extensions of a disabled repo get no updates and are not obsolete`() {
        val offers = RepoOffers(listOf(offer("a", "repo-d", 5)), disabled = setOf("repo-d"))
        assertEquals(UpdateState(hasUpdate = false, isObsolete = false), offers.updateState("a", 1, "repo-d", isExternal = false))
        // Without a repo of record either.
        assertEquals(UpdateState(hasUpdate = false, isObsolete = false), offers.updateState("a", 1, null, isExternal = false))
    }

    @Test
    fun `an enabled repo still offers updates`() {
        val offers = RepoOffers(listOf(offer("a", "repo-e", 5)), disabled = emptySet())
        assertEquals(UpdateState(hasUpdate = true, isObsolete = false), offers.updateState("a", 1, "repo-e", isExternal = false))
        assertEquals(UpdateState(hasUpdate = false, isObsolete = true), offers.updateState("a", 9, "repo-e", isExternal = false))
        assertEquals(UpdateState(hasUpdate = false, isObsolete = true), offers.updateState("gone", 1, "repo-e", isExternal = false))
        assertEquals(UpdateState(hasUpdate = false, isObsolete = false), offers.updateState("gone", 1, null, isExternal = true))
    }

    @Test
    fun `an extension installed from a disabled repo is not updated from another repo`() {
        val offers = RepoOffers(listOf(offer("a", "repo-d", 5), offer("a", "repo-e", 7)), disabled = setOf("repo-d"))
        assertEquals(UpdateState(hasUpdate = false, isObsolete = false), offers.updateState("a", 1, "repo-d", isExternal = false))
        assertEquals(UpdateState(hasUpdate = true, isObsolete = false), offers.updateState("a", 1, "repo-e", isExternal = false))
        assertEquals(UpdateState(hasUpdate = true, isObsolete = false), offers.updateState("a", 1, null, isExternal = false))
    }

    // ---- the index is parsed once, and the update check doesn't parse it ----------------------

    private suspend fun RepoStore.kavita() = available().first { it.name == "Kavita" }

    @Test
    fun `offers are kept until the index or the repos change`() =
        runTest {
            val store = store()
            store.addBoth()
            val offers = store.offers()
            assertSame(offers, store.offers())

            store.setEnabled(kavitaIndex, false)
            val afterToggle = store.offers()
            assertNotSame(offers, afterToggle)
            assertSame(afterToggle, store.offers())

            store.refresh()
            assertNotSame(afterToggle, store.offers())
        }

    @Test
    fun `the update check does not parse the index`() =
        runTest {
            val first = store()
            first.addBoth()
            val kavita = first.kavita()
            val index = dir.resolve("available.json")
            // The index is garbage of the same size and time: a store that parsed it would see no offers.
            val stamp = Files.getLastModifiedTime(index)
            index.writeText("x".repeat(Files.size(index).toInt()))
            Files.setLastModifiedTime(index, stamp)

            val cold = store()
            val state = cold.offers().updateState(kavita.pkgName, kavita.versionCode - 1, kavita.repoIndexUrl, isExternal = false)
            assertEquals(UpdateState(hasUpdate = true, isObsolete = false), state)
            assertTrue(cold.available().isEmpty(), "the index itself is unreadable")
        }

    @Test
    fun `an index changed by something else makes the offers follow it`() =
        runTest {
            val store = store()
            store.addBoth()
            val kavita = store.kavita()
            val behind = kavita.versionCode - 1
            assertTrue(store.offers().updateState(kavita.pkgName, behind, kavita.repoIndexUrl, false).hasUpdate)

            val index = dir.resolve("available.json")
            index.writeText(Files.readString(index).replace(kavita.pkgName, "gone.${kavita.pkgName}"))
            val state = store.offers().updateState(kavita.pkgName, behind, kavita.repoIndexUrl, false)
            assertEquals(UpdateState(hasUpdate = false, isObsolete = true), state)
        }

    @Test
    fun `an index from before the summary existed gets one on first use`() =
        runTest {
            store().addBoth()
            val summary = dir.resolve("available-versions.json")
            Files.delete(summary)

            val store = store()
            val kavita = store.kavita()
            assertTrue(store.offers().updateState(kavita.pkgName, kavita.versionCode - 1, kavita.repoIndexUrl, false).hasUpdate)
            assertTrue(summary.exists())
        }

    @Test
    fun `removing and refreshing a repo update the offers`() =
        runTest {
            val store = store()
            store.addBoth()
            val kavita = store.kavita()
            store.remove(kavitaIndex)
            assertEquals(
                UpdateState(hasUpdate = false, isObsolete = true),
                store.offers().updateState(kavita.pkgName, kavita.versionCode, kavita.repoIndexUrl, false),
            )
            // A new store on the same files agrees.
            assertEquals(
                UpdateState(hasUpdate = false, isObsolete = true),
                store().offers().updateState(kavita.pkgName, kavita.versionCode, kavita.repoIndexUrl, false),
            )
        }
}
