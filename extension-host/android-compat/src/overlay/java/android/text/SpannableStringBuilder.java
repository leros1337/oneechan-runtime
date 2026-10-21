package android.text;

/*
 * Oneechan AndroidCompat addition (the android.jar stub throws "Stub!"). Behaviour follows AOSP
 * (Apache-2.0); span styling is kept as data since the host never renders text spans itself.
 */

public class SpannableStringBuilder implements CharSequence, GetChars, Spannable, Editable, Appendable {
    private final StringBuilder text = new StringBuilder();
    private final SpanStore spans = new SpanStore();
    private InputFilter[] filters = new InputFilter[0];

    public SpannableStringBuilder() {}

    public SpannableStringBuilder(CharSequence source) { this(source, 0, source.length()); }

    public SpannableStringBuilder(CharSequence source, int start, int end) {
        text.append(source, start, end);
        copySpans(source, start, end, 0);
    }

    public static SpannableStringBuilder valueOf(CharSequence source) {
        return source instanceof SpannableStringBuilder builder ? builder : new SpannableStringBuilder(source);
    }

    private void copySpans(CharSequence source, int start, int end, int at) {
        if (!(source instanceof Spanned spanned)) return;
        for (Object span : spanned.getSpans(start, end, Object.class)) {
            int s = Math.max(spanned.getSpanStart(span), start) - start + at;
            int e = Math.min(spanned.getSpanEnd(span), end) - start + at;
            spans.set(span, s, e, spanned.getSpanFlags(span));
        }
    }

    public char charAt(int index) { return text.charAt(index); }
    public int length() { return text.length(); }

    public SpannableStringBuilder replace(int start, int end, CharSequence tb, int tbstart, int tbend) {
        text.replace(start, end, tb.subSequence(tbstart, tbend).toString());
        spans.replaced(start, end, tbend - tbstart);
        copySpans(tb, tbstart, tbend, start);
        return this;
    }

    public SpannableStringBuilder replace(int start, int end, CharSequence tb) { return replace(start, end, tb, 0, tb.length()); }
    public SpannableStringBuilder insert(int where, CharSequence tb, int start, int end) { return replace(where, where, tb, start, end); }
    public SpannableStringBuilder insert(int where, CharSequence tb) { return replace(where, where, tb, 0, tb.length()); }
    public SpannableStringBuilder delete(int start, int end) { return replace(start, end, "", 0, 0); }
    public void clear() { replace(0, length(), "", 0, 0); }
    public void clearSpans() { spans.clear(); }
    public SpannableStringBuilder append(CharSequence text) { int length = length(); return replace(length, length, text, 0, text.length()); }

    public SpannableStringBuilder append(CharSequence text, Object what, int flags) {
        int start = length();
        append(text);
        setSpan(what, start, length(), flags);
        return this;
    }

    public SpannableStringBuilder append(CharSequence text, int start, int end) { int length = length(); return replace(length, length, text, start, end); }
    public SpannableStringBuilder append(char text) { return append(String.valueOf(text)); }

    public void setSpan(Object what, int start, int end, int flags) {
        if (start < 0 || end > length()) throw new IndexOutOfBoundsException("setSpan (" + start + " ... " + end + ") ends beyond length " + length());
        spans.set(what, start, end, flags);
    }

    public void removeSpan(Object what) { spans.remove(what); }
    public int getSpanStart(Object what) { return spans.start(what); }
    public int getSpanEnd(Object what) { return spans.end(what); }
    public int getSpanFlags(Object what) { return spans.flags(what); }
    public <T> T[] getSpans(int start, int end, Class<T> kind) { return spans.get(start, end, kind); }
    public int nextSpanTransition(int start, int limit, Class kind) { return spans.nextTransition(start, limit, kind); }
    public CharSequence subSequence(int start, int end) { return new SpannableStringBuilder(this, start, end); }
    public void getChars(int start, int end, char[] dest, int off) { text.getChars(start, end, dest, off); }
    public String toString() { return text.toString(); }
    public int getTextWatcherDepth() { return 0; }
    public int getTextRunCursor(int contextStart, int contextEnd, int dir, int offset, int cursorOpt, android.graphics.Paint p) { return offset; }
    public void setFilters(InputFilter[] filters) { this.filters = filters; }
    public InputFilter[] getFilters() { return filters; }
    @Override public boolean equals(Object other) { return other instanceof Spanned spanned && toString().equals(spanned.toString()); }
    @Override public int hashCode() { return toString().hashCode(); }
}
