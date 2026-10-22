package oneechan.host

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import oneechan.host.rpc.PreferenceValueDto
import oneechan.host.rpc.PreferencesImportParams
import oneechan.host.rpc.SourcePreferencesDto
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.test.assertEquals

class PreferencesImportTest {
    private fun prefs(key: String) = Injekt.get<Application>().getSharedPreferences(key, Context.MODE_PRIVATE)

    @Test
    fun `typed values from Mihon backups are stored with their types`() {
        val result =
            TestHost.blocking {
                TestHost.host.importPreferences(
                    PreferencesImportParams(
                        listOf(
                            SourcePreferencesDto(
                                "source_1001",
                                listOf(
                                    PreferenceValueDto("useHq", "boolean", JsonPrimitive(true)),
                                    PreferenceValueDto("pageSize", "int", JsonPrimitive(30)),
                                    PreferenceValueDto("since", "long", JsonPrimitive(1_700_000_000_000)),
                                    PreferenceValueDto("ratio", "float", JsonPrimitive(1.5f)),
                                    PreferenceValueDto("domain", "string", JsonPrimitive("https://mirror.example")),
                                    PreferenceValueDto("langs", "stringSet", JsonArray(listOf(JsonPrimitive("en"), JsonPrimitive("ja")))),
                                    PreferenceValueDto("unknown", "color", JsonPrimitive(1)),
                                ),
                            ),
                        ),
                    ),
                )
            }
        assertEquals(6, result.imported)
        val stored = prefs("source_1001")
        assertEquals(true, stored.getBoolean("useHq", false))
        assertEquals(30, stored.getInt("pageSize", 0))
        assertEquals(1_700_000_000_000, stored.getLong("since", 0))
        assertEquals(1.5f, stored.getFloat("ratio", 0f))
        assertEquals("https://mirror.example", stored.getString("domain", null))
        assertEquals(setOf("en", "ja"), stored.getStringSet("langs", null))
    }

    @Test
    fun `string-encoded values from Tachimanga read back as their real types`() {
        // Tachimanga's Suwayomi stores every value as a string (sets as key.size / key.N).
        TestHost.blocking {
            TestHost.host.importPreferences(
                PreferencesImportParams(
                    listOf(
                        SourcePreferencesDto(
                            "source_1002",
                            listOf(
                                PreferenceValueDto("flag", "string", JsonPrimitive("true")),
                                PreferenceValueDto("count", "string", JsonPrimitive("7")),
                                PreferenceValueDto("tags.size", "string", JsonPrimitive("2")),
                                PreferenceValueDto("tags.0", "string", JsonPrimitive("a")),
                                PreferenceValueDto("tags.1", "string", JsonPrimitive("b")),
                            ),
                        ),
                    ),
                ),
            )
        }
        val stored = prefs("source_1002")
        assertEquals(true, stored.getBoolean("flag", false))
        assertEquals(7, stored.getInt("count", 0))
        assertEquals(setOf("a", "b"), stored.getStringSet("tags", null))
    }

    @Test
    fun `only source preference files are written`() {
        val result =
            TestHost.blocking {
                TestHost.host.importPreferences(
                    PreferencesImportParams(listOf(SourcePreferencesDto("../server", listOf(PreferenceValueDto("x", "string", JsonPrimitive("y")))))),
                )
            }
        assertEquals(0, result.imported)
    }
}
