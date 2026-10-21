package oneechan.host.source

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.FilterDto
import oneechan.host.rpc.RpcException
import oneechan.host.rpc.SortSelectionDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class FilterCodecTest {
    private class Genre(name: String) : Filter.TriState(name)

    private class Genres : Filter.Group<Genre>("Genres", listOf(Genre("Action"), Genre("Romance")))

    private class Status : Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed"))

    private class Title : Filter.Text("Title")

    private class Adult : Filter.CheckBox("Show adult")

    private class Order : Filter.Sort("Order", arrayOf("Latest", "Popular"), Selection(0, false))

    private fun fresh() =
        FilterList(Filter.Header("Note"), Filter.Separator(), Status(), Title(), Adult(), Genres(), Order())

    @Test
    fun `encodes the full filter tree`() {
        val encoded = FilterCodec.encode(fresh())
        assertEquals(
            listOf(
                FilterDto.Header("Note"),
                FilterDto.Separator(""),
                FilterDto.Select("Status", listOf("Any", "Ongoing", "Completed"), 0),
                FilterDto.Text("Title", ""),
                FilterDto.CheckBox("Show adult", false),
                FilterDto.Group("Genres", listOf(FilterDto.TriState("Action", 0), FilterDto.TriState("Romance", 0))),
                FilterDto.Sort("Order", listOf("Latest", "Popular"), SortSelectionDto(0, false)),
            ),
            encoded,
        )
    }

    @Test
    fun `applies client state onto a fresh list`() {
        val state =
            FilterCodec.encode(fresh()).map {
                when (it) {
                    is FilterDto.Select -> it.copy(state = 2)
                    is FilterDto.Text -> it.copy(state = "one piece")
                    is FilterDto.CheckBox -> it.copy(state = true)
                    is FilterDto.Group -> it.copy(state = listOf(FilterDto.TriState("Action", 1), FilterDto.TriState("Romance", 2)))
                    is FilterDto.Sort -> it.copy(state = SortSelectionDto(1, true))
                    else -> it
                }
            }
        val applied = FilterCodec.apply(fresh(), state)
        assertEquals(state, FilterCodec.encode(applied))
        val genres = applied.filterIsInstance<Genres>().single().state
        assertEquals(true, genres[0].isIncluded())
        assertEquals(true, genres[1].isExcluded())
    }

    @Test
    fun `rejects state for a different filter layout`() {
        val error = assertThrows<RpcException> { FilterCodec.apply(fresh(), listOf(FilterDto.Header("Note"))) }
        assertEquals(ErrorCode.FILTER_MISMATCH, error.code)
        val renamed = FilterCodec.encode(fresh()).toMutableList().apply { this[3] = FilterDto.Text("Author", "x") }
        assertEquals(ErrorCode.FILTER_MISMATCH, assertThrows<RpcException> { FilterCodec.apply(fresh(), renamed) }.code)
    }
}
