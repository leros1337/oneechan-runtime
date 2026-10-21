package oneechan.host.source

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.FilterDto
import oneechan.host.rpc.RpcException
import oneechan.host.rpc.SortSelectionDto

/**
 * Serializes a source's [FilterList] to a positional DTO tree and applies client-side state back
 * onto a *fresh* filter list (filters are mutable objects owned by the source, so state must never
 * be applied to an instance the source may still be holding).
 */
object FilterCodec {
    fun encode(filters: FilterList): List<FilterDto> = filters.mapNotNull(::encodeFilter)

    private fun encodeFilter(filter: Filter<*>): FilterDto? =
        when (filter) {
            is Filter.Header -> FilterDto.Header(filter.name)
            is Filter.Separator -> FilterDto.Separator(filter.name)
            is Filter.Select<*> -> FilterDto.Select(filter.name, filter.displayValues, filter.state)
            is Filter.Text -> FilterDto.Text(filter.name, filter.state)
            is Filter.CheckBox -> FilterDto.CheckBox(filter.name, filter.state)
            is Filter.TriState -> FilterDto.TriState(filter.name, filter.state)
            is Filter.Group<*> -> FilterDto.Group(filter.name, filter.state.mapNotNull { (it as? Filter<*>)?.let(::encodeFilter) })
            is Filter.Sort -> FilterDto.Sort(filter.name, filter.values.toList(), filter.state?.let { SortSelectionDto(it.index, it.ascending) })
            else -> null
        }

    /** Applies [states] onto [fresh] in place and returns it. */
    fun apply(
        fresh: FilterList,
        states: List<FilterDto>,
    ): FilterList {
        val encodable = fresh.filter { encodeFilter(it) != null }
        applyAll(encodable, states, path = "")
        return fresh
    }

    private fun applyAll(
        filters: List<Filter<*>>,
        states: List<FilterDto>,
        path: String,
    ) {
        if (filters.size != states.size) {
            mismatch("$path: expected ${filters.size} filters, got ${states.size}")
        }
        filters.zip(states).forEachIndexed { index, (filter, state) -> applyOne(filter, state, "$path/$index") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyOne(
        filter: Filter<*>,
        state: FilterDto,
        path: String,
    ) {
        if (filter.name != state.name) mismatch("$path: name '${state.name}' != '${filter.name}'")
        when {
            filter is Filter.Header && state is FilterDto.Header -> Unit

            filter is Filter.Separator && state is FilterDto.Separator -> Unit

            filter is Filter.Select<*> && state is FilterDto.Select -> {
                if (state.state !in filter.values.indices) mismatch("$path: select index ${state.state} out of range")
                filter.state = state.state
            }

            filter is Filter.Text && state is FilterDto.Text -> filter.state = state.state

            filter is Filter.CheckBox && state is FilterDto.CheckBox -> filter.state = state.state

            filter is Filter.TriState && state is FilterDto.TriState -> filter.state = state.state

            filter is Filter.Group<*> && state is FilterDto.Group -> {
                val children = filter.state.filterIsInstance<Filter<*>>().filter { encodeFilter(it) != null }
                applyAll(children, state.state, path)
            }

            filter is Filter.Sort && state is FilterDto.Sort -> {
                val selection = state.state
                if (selection != null && selection.index !in filter.values.indices) mismatch("$path: sort index out of range")
                filter.state = selection?.let { Filter.Sort.Selection(it.index, it.ascending) }
            }

            else -> mismatch("$path: type ${state::class.simpleName} does not match ${filter.javaClass.simpleName}")
        }
    }

    private fun mismatch(message: String): Nothing = throw RpcException(ErrorCode.FILTER_MISMATCH, "Filter state does not match the source filters ($message)")
}
