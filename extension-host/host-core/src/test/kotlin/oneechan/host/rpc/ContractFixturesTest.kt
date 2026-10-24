package oneechan.host.rpc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * Golden wire-format fixtures shared with the Swift client (`contract/fixtures`). The Swift test
 * suite decodes every file; this test regenerates them from the Kotlin DTOs and fails when the
 * committed copy is stale (run with -PupdateContract=true to rewrite).
 */
class ContractFixturesTest {
    private val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }

    private val dir: Path = Path(System.getProperty("oneechan.contractDir") ?: "../contract").resolve("fixtures")
    private val update = System.getProperty("oneechan.updateContract") == "true"

    private val bigId = "7266875667263635239" // > 2^53: must survive as a string

    private val manga =
        MangaDto(
            url = "/manga/9906dfa8",
            title = "Frieren: Beyond Journey's End",
            artist = "Abe Tsukasa",
            author = "Yamada Kanehito",
            description = "An elf mage...",
            genre = "Adventure, Fantasy",
            status = 1,
            thumbnailUrl = "https://uploads.mangadex.org/covers/x.jpg",
            initialized = true,
            memo = buildJsonObject { put("mdId", "b0b721ff") },
        )
    private val chapter = ChapterDto("/chapter/1", "Vol.1 Ch.1 - The Journey's End", 1_696_118_400_000, 1f, "Official", JsonObject(emptyMap()))
    private val source = SourceDto(bigId, "Kavita (1)", "all", "eu.kanade.tachiyomi.extension.all.kavita", null, true, true, ContentWarningDto.SAFE, 1)

    private fun <T> fixture(
        name: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        val text = json.encodeToString(serializer, value) + "\n"
        val file = dir.resolve("$name.json")
        if (update || !file.exists()) {
            dir.createDirectories()
            file.writeText(text)
        } else {
            assertEquals(file.readText(), text, "Contract fixture $name is stale; run with -PupdateContract=true and update the Swift DTOs")
        }
    }

    @Test
    fun `contract fixtures`() {
        fixture("rpc_request", RpcRequest.serializer(), RpcRequest("42", "source.popular", json.encodeToJsonElement(BrowseParams.serializer(), BrowseParams(bigId, 2))))
        fixture("rpc_response_ok", RpcResponse.serializer(), RpcResponse("42", result = JsonObject(mapOf("cancelled" to JsonPrimitive(true)))))
        fixture(
            "rpc_response_error",
            RpcResponse.serializer(),
            RpcResponse("43", error = RpcErrorDto(ErrorCode.CLOUDFLARE, "Blocked by Cloudflare: timeout", true, 403, "https://example.org/x", bigId)),
        )
        fixture(
            "event_ext_progress",
            RpcEvent.serializer(),
            RpcEvent("ext.progress", json.encodeToJsonElement(ExtProgressEvent.serializer(), ExtProgressEvent("job-1", "eu.kanade.tachiyomi.extension.all.kavita", InstallPhase.CONVERT, 0.5))),
        )
        fixture("hello", HelloResult.serializer(), HelloResult(1, "0.1.0", 1.3, 1.6, "OpenJDK 64-Bit Server VM 21", listOf("repo.index.pb"), mapOf("js" to "javascriptcore", "graphics" to "native")))
        fixture("repo", RepoDto.serializer(), RepoDto("https://github.com/keiyoushi/extensions/raw/repo/index.pb", "Keiyoushi", "Keiyoushi", "9add655a", "https://keiyoushi.github.io", null, false))
        // repo.setEnabled: its params, and the repo it returns.
        fixture("repo_set_enabled", RepoEnabledParams.serializer(), RepoEnabledParams("https://raw.githubusercontent.com/Kareadita/tach-extension/repo/repo.json", false))
        fixture(
            "repo_disabled",
            RepoDto.serializer(),
            RepoDto("https://raw.githubusercontent.com/Kareadita/tach-extension/repo/repo.json", "Kavita Extension Repo", "Kavita", "5887aee6", "https://www.kavitareader.com/", null, true, enabled = false),
        )
        fixture(
            "available_extensions",
            ListSerializer(AvailableExtensionDto.serializer()),
            listOf(
                AvailableExtensionDto(
                    "eu.kanade.tachiyomi.extension.all.kavita", "Kavita", "1.4.26", 26, "1.4", "all", ContentWarningDto.SAFE,
                    "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/repo.json",
                    "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/apk/tachiyomi-all.kavita-v1.4.26.apk", null,
                    "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/icon/eu.kanade.tachiyomi.extension.all.kavita.png",
                    listOf(AvailableSourceDto(bigId, "Kavita (1)", "all", "")),
                ),
            ),
        )
        fixture(
            "installed_extensions",
            ListSerializer(InstalledExtensionDto.serializer()),
            listOf(
                InstalledExtensionDto(
                    "eu.kanade.tachiyomi.extension.all.kavita", "Kavita", "1.4.26", 26, "1.4", "all", ContentWarningDto.SAFE,
                    "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/repo.json", "5887aee6", isTrusted = true,
                    hasUpdate = false, isObsolete = false, iconPath = "/data/icons/eu.kanade.tachiyomi.extension.all.kavita.png", sources = listOf(source),
                ),
            ),
        )
        fixture("sources", ListSerializer(SourceDto.serializer()), listOf(source))
        fixture("mangas_page", MangasPageDto.serializer(), MangasPageDto(listOf(manga, MangaDto("/manga/2", "Second")), hasNextPage = true))
        fixture("manga_update", MangaUpdateDto.serializer(), MangaUpdateDto(manga, listOf(chapter)))
        fixture("pages", ListSerializer(PageDto.serializer()), listOf(PageDto(0, "", "https://cdn/1.png"), PageDto(1, "/page/2", null)))
        fixture("image_file", ImageFileDto.serializer(), ImageFileDto("/cache/images/abc", "image/png", 2092368))
        fixture(
            "filters",
            ListSerializer(FilterDto.serializer()),
            listOf(
                FilterDto.Header("Note"),
                FilterDto.Separator(),
                FilterDto.Select("Status", listOf("Any", "Ongoing"), 1),
                FilterDto.Text("Author", "Oda"),
                FilterDto.CheckBox("Adult", false),
                FilterDto.TriState("Action", 2),
                FilterDto.Group("Genres", listOf(FilterDto.TriState("Romance", 1), FilterDto.CheckBox("Official", true))),
                FilterDto.Sort("Order", listOf("Latest", "Popular"), SortSelectionDto(1, true)),
                FilterDto.Sort("Unsorted", listOf("A"), null),
            ),
        )
        fixture(
            "net_fetch_event",
            NetFetchEvent.serializer(),
            NetFetchEvent("f1", "https://site.test/page?q=1", "POST", listOf(listOf("User-Agent", "UA/1"), listOf("Cookie", "a=1"), listOf("Cookie", "b=2")), "e30=", 30000),
        )
        fixture(
            "net_fetch_result",
            NetFetchResultParams.serializer(),
            NetFetchResultParams("f1", 200, "OK", listOf(listOf("Content-Type", "text/html"), listOf("Set-Cookie", "cf_clearance=x; Path=/")), "PGh0bWw+", "h2"),
        )
        fixture(
            "net_fetch_result_error",
            NetFetchResultParams.serializer(),
            NetFetchResultParams("f2", error = RpcErrorDto(ErrorCode.CLOUDFLARE, "timeout")),
        )
        fixture("challenge_required", ChallengeRequiredEvent.serializer(), ChallengeRequiredEvent("c1", "https://site.test/x", "site.test", "UA/1"))
        fixture(
            "event_host_toast",
            RpcEvent.serializer(),
            RpcEvent("host.toast", json.encodeToJsonElement(HostToastEvent.serializer(), HostToastEvent("Chapter List loading takes time for first time", long = true))),
        )
        fixture(
            "challenge_resolved",
            ChallengeResolvedParams.serializer(),
            ChallengeResolvedParams("c1", true, null, listOf(CookieDto("cf_clearance", "x", ".site.test", "/", 1_900_000_000_000, secure = true, httpOnly = true))),
        )
        fixture("init_params", InitParams.serializer(), InitParams(userAgent = "UA/1", nativeNet = true))
        fixture(
            "preferences",
            ListSerializer(PreferenceDto.serializer()),
            listOf(
                PreferenceDto.EditText("address", "Server address", "http://", true, true, "Address", null, null, "", "http://10.0.0.2:5000"),
                PreferenceDto.List("quality", "Quality", "%s", true, true, listOf("High", "Low"), listOf("hq", "lq"), "hq", "lq"),
                PreferenceDto.MultiSelectList("langs", "Languages", null, true, false, null, null, listOf("English", "Spanish"), listOf("en", "es"), listOf("en"), listOf("en", "es")),
                PreferenceDto.Switch("dataSaver", "Data saver", null, true, true, false, true),
                PreferenceDto.CheckBox("nsfw", "Show NSFW", null, false, true, true, true),
            ),
        )
        fixture(
            "preferences_import",
            PreferencesImportParams.serializer(),
            PreferencesImportParams(
                listOf(
                    SourcePreferencesDto(
                        "source_$bigId",
                        listOf(
                            PreferenceValueDto("dataSaver", "boolean", JsonPrimitive(true)),
                            PreferenceValueDto("pageSize", "int", JsonPrimitive(30)),
                            PreferenceValueDto("since", "long", JsonPrimitive(1_700_000_000_000)),
                            PreferenceValueDto("ratio", "float", JsonPrimitive(1.5f)),
                            PreferenceValueDto("quality", "string", JsonPrimitive("hq")),
                            PreferenceValueDto("langs", "stringSet", JsonArray(listOf(JsonPrimitive("en"), JsonPrimitive("es")))),
                        ),
                    ),
                ),
            ),
        )
        fixture("imported", ImportedDto.serializer(), ImportedDto(6))
    }

    @Test
    fun `webview contract fixtures`() {
        val config =
            WebViewConfigDto(
                userAgent = "UA/1",
                interceptRequests = true,
                consoleMessages = true,
                javascriptInterfaces = listOf(JavascriptInterfaceDto("Bridge", listOf(JavascriptMethodDto("post"), JavascriptMethodDto("sum", returnsValue = true)))),
                supportMultipleWindows = true,
            )
        fixture(
            "webview_command_load",
            WebViewCommandEvent.serializer(),
            WebViewCommandEvent("v1", WebViewCommandDto.Load("https://site.test/", mapOf("Referer" to "https://site.test/"), config = config)),
        )
        fixture(
            "webview_command_load_data",
            WebViewCommandEvent.serializer(),
            WebViewCommandEvent("v1", WebViewCommandDto.LoadData("<p>hi</p>", "https://mangalib.me", "text/html", "UTF-8", config = WebViewConfigDto("UA/1"))),
        )
        fixture("webview_command_evaluate", WebViewCommandEvent.serializer(), WebViewCommandEvent("v1", WebViewCommandDto.Evaluate(3, "localStorage.getItem(\"auth\")")))
        fixture("webview_command_destroy", WebViewCommandEvent.serializer(), WebViewCommandEvent("v1", WebViewCommandDto.Destroy))
        fixture(
            "webview_command_touch",
            WebViewCommandEvent.serializer(),
            WebViewCommandEvent("v1", WebViewCommandDto.Touch("down", 120.5, 300.0, pointerId = 0, pressure = 0.4, radiusX = 9.5, radiusY = 7.25, timestampMillis = 1_234_567)),
        )
        fixture(
            "webview_event_page_finished",
            WebViewEventParams.serializer(),
            WebViewEventParams("v1", WebViewEventDto.PageFinished("https://site.test/", WebViewStateDto("https://site.test/", "Site", 100, canGoBack = true))),
        )
        fixture("webview_event_evaluate_result", WebViewEventParams.serializer(), WebViewEventParams("v1", WebViewEventDto.EvaluateResult(3, "\"token\"")))
        fixture(
            "webview_event_error",
            WebViewEventParams.serializer(),
            WebViewEventParams("v1", WebViewEventDto.ReceivedError(WebRequestDto("https://site.test/"), -2, "A server with the specified hostname could not be found.")),
        )
        fixture(
            "webview_intercept_request",
            WebViewRequestParams.serializer(),
            WebViewRequestParams("v1", WebRequestDto("https://api.site.test/v1", "POST", mapOf("Accept" to "application/json"), isForMainFrame = false)),
        )
        fixture(
            "webview_intercept_result",
            WebViewInterceptResult.serializer(),
            WebViewInterceptResult(WebResponseDto(201, "Created", "application/json", "utf-8", mapOf("X-From" to "jvm"), "e30=")),
        )
        fixture("webview_override_result", WebViewOverrideResult.serializer(), WebViewOverrideResult(true))
        fixture("webview_js_call", WebViewJsCallParams.serializer(), WebViewJsCallParams("v1", "Bridge", "post", listOf(JsonPrimitive("{\"a\":1}"), JsonPrimitive(2))))
        fixture("webview_js_call_result", WebViewJsCallResult.serializer(), WebViewJsCallResult(JsonPrimitive(3.5)))
    }

    /** `source.related`: the entry goes with its memo (Madara reads the id and genres from it). */
    @Test
    fun `related titles contract fixtures`() {
        fixture("related_params", MangaParams.serializer(), MangaParams(bigId, manga))
        fixture(
            "related_mangas",
            RelatedMangasDto.serializer(),
            RelatedMangasDto(
                supported = true,
                mangas = listOf(MangaDto("/manga/witch-hat", "Witch Hat Atelier", thumbnailUrl = "https://site.test/covers/witch-hat.jpg")),
                searchDisabled = true,
            ),
        )
        fixture("related_unsupported", RelatedMangasDto.serializer(), RelatedMangasDto(supported = false))
    }
}
