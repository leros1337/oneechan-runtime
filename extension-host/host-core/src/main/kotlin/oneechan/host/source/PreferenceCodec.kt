package oneechan.host.source

import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.PreferenceDto
import oneechan.host.rpc.RpcException

object PreferenceCodec {
    fun encode(preferences: List<Preference>): List<PreferenceDto> = preferences.mapNotNull(::encodeOne)

    @Suppress("UNCHECKED_CAST")
    private fun encodeOne(pref: Preference): PreferenceDto? {
        val key = pref.key
        val title = pref.title?.toString()
        val visible = pref.visible
        val enabled = pref.isEnabled
        return when (pref) {
            is EditTextPreference ->
                PreferenceDto.EditText(
                    key = key,
                    title = title,
                    summary = pref.summary?.toString(),
                    visible = visible,
                    enabled = enabled,
                    dialogTitle = pref.dialogTitle?.toString(),
                    dialogMessage = pref.dialogMessage?.toString(),
                    text = pref.text,
                    default = pref.defaultValue as? String,
                    value = pref.currentValueOrNull() as? String,
                )

            is ListPreference ->
                PreferenceDto.List(
                    key = key,
                    title = title,
                    summary = pref.summary?.toString(),
                    visible = visible,
                    enabled = enabled,
                    entries = pref.entries.orEmpty().map { it.toString() },
                    entryValues = pref.entryValues.orEmpty().map { it.toString() },
                    default = pref.defaultValue as? String,
                    value = pref.currentValueOrNull() as? String,
                )

            is MultiSelectListPreference ->
                PreferenceDto.MultiSelectList(
                    key = key,
                    title = title,
                    summary = pref.summary?.toString(),
                    visible = visible,
                    enabled = enabled,
                    dialogTitle = pref.dialogTitle?.toString(),
                    dialogMessage = pref.dialogMessage?.toString(),
                    entries = pref.entries.orEmpty().map { it.toString() },
                    entryValues = pref.entryValues.orEmpty().map { it.toString() },
                    default = (pref.defaultValue as? Set<String>)?.toList(),
                    value = (pref.currentValueOrNull() as? Set<String>)?.toList(),
                )

            is TwoStatePreference -> {
                val default = pref.defaultValue as? Boolean ?: false
                val value = pref.currentValueOrNull() as? Boolean ?: default
                // TwoStatePreference.getSummary() reads the current value; it is safe here.
                val summary = runCatching { pref.summary?.toString() }.getOrNull()
                if (pref is CheckBoxPreference) {
                    PreferenceDto.CheckBox(key, title, summary, visible, enabled, default, value)
                } else {
                    PreferenceDto.Switch(key, title, summary, visible, enabled, default, value)
                }
            }

            else -> null
        }
    }

    private fun Preference.currentValueOrNull(): Any? = runCatching { currentValue }.getOrNull()

    /** Converts a JSON value to the Java type the preference persists. */
    fun decodeValue(
        pref: Preference,
        value: JsonElement,
    ): Any =
        when (pref.defaultValueType) {
            "String" -> (value as? JsonPrimitive)?.contentOrNull ?: invalid(pref, "a string")
            "Boolean" -> (value as? JsonPrimitive)?.booleanOrNull ?: invalid(pref, "a boolean")
            "Set<String>" ->
                (value as? JsonArray)
                    ?.map { (it as? JsonPrimitive)?.contentOrNull ?: invalid(pref, "an array of strings") }
                    ?.toSet()
                    ?: invalid(pref, "an array of strings")
            else -> invalid(pref, "a supported type")
        }

    private fun invalid(
        pref: Preference,
        expected: String,
    ): Nothing = throw RpcException(ErrorCode.INVALID_PARAMS, "Preference '${pref.key}' expects $expected")
}
