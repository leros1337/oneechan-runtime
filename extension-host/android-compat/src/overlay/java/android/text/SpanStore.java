package android.text;

/*
 * Oneechan AndroidCompat addition (the android.jar stub throws "Stub!"). This is an independent
 * implementation of the public android.text API, written for Oneechan without copying AOSP source
 * code, comments or structure (only the public API names and documented behaviour are shared, so
 * it is not an AOSP-derived file). Span styling is kept as data since the host never renders text
 * spans itself.
 */

import java.lang.reflect.Array;
import java.util.ArrayList;

/** Span bookkeeping shared by the Spanned implementations. */
final class SpanStore {
    private static final class Entry {
        final Object what;
        int start;
        int end;
        int flags;

        Entry(Object what, int start, int end, int flags) {
            this.what = what;
            this.start = start;
            this.end = end;
            this.flags = flags;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();

    SpanStore() {}

    /** Copies the spans of [source] that intersect [start, end), shifted to start at 0. */
    SpanStore(CharSequence source, int start, int end) {
        if (source instanceof Spanned spanned) {
            for (Object span : spanned.getSpans(start, end, Object.class)) {
                int s = Math.max(spanned.getSpanStart(span), start) - start;
                int e = Math.min(spanned.getSpanEnd(span), end) - start;
                set(span, s, e, spanned.getSpanFlags(span));
            }
        }
    }

    void set(Object what, int start, int end, int flags) {
        if (start > end) throw new IndexOutOfBoundsException("setSpan range [" + start + ", " + end + ") has its end before its start");
        for (Entry entry : entries) {
            if (entry.what == what) {
                entry.start = start;
                entry.end = end;
                entry.flags = flags;
                return;
            }
        }
        entries.add(new Entry(what, start, end, flags));
    }

    void remove(Object what) {
        entries.removeIf(entry -> entry.what == what);
    }

    void clear() {
        entries.clear();
    }

    private Entry find(Object what) {
        for (Entry entry : entries) if (entry.what == what) return entry;
        return null;
    }

    int start(Object what) {
        Entry entry = find(what);
        return entry == null ? -1 : entry.start;
    }

    int end(Object what) {
        Entry entry = find(what);
        return entry == null ? -1 : entry.end;
    }

    int flags(Object what) {
        Entry entry = find(what);
        return entry == null ? 0 : entry.flags;
    }

    @SuppressWarnings("unchecked")
    <T> T[] get(int queryStart, int queryEnd, Class<T> kind) {
        ArrayList<Object> found = new ArrayList<>();
        for (Entry entry : entries) {
            if (kind != null && !kind.isInstance(entry.what)) continue;
            if (entry.start > queryEnd || entry.end < queryStart) continue;
            if (entry.start != entry.end && queryStart != queryEnd) {
                if (entry.start == queryEnd || entry.end == queryStart) continue;
            }
            found.add(entry.what);
        }
        T[] result = (T[]) Array.newInstance(kind == null ? Object.class : kind, found.size());
        return found.toArray(result);
    }

    int nextTransition(int start, int limit, Class<?> kind) {
        int next = limit;
        for (Entry entry : entries) {
            if (kind != null && !kind.isInstance(entry.what)) continue;
            if (entry.start > start && entry.start < next) next = entry.start;
            if (entry.end > start && entry.end < next) next = entry.end;
        }
        return next;
    }

    /** Adjusts span offsets after [start, end) was replaced by [length] characters. */
    void replaced(int start, int end, int length) {
        int delta = length - (end - start);
        for (Entry entry : entries) {
            entry.start = adjust(entry.start, start, end, delta);
            entry.end = adjust(entry.end, start, end, delta);
        }
        entries.removeIf(entry -> entry.end < entry.start);
    }

    private static int adjust(int offset, int start, int end, int delta) {
        if (offset >= end) return offset + delta;
        if (offset > start) return start;
        return offset;
    }
}
