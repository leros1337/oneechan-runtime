package android.text;

/*
 * Oneechan AndroidCompat addition (the android.jar stub throws "Stub!"). Behaviour follows AOSP
 * (Apache-2.0); span styling is kept as data since the host never renders text spans itself.
 */

public final class SpannedString implements CharSequence, GetChars, Spanned {
    private final String text;
    private final SpanStore spans;

    public SpannedString(CharSequence source) {
        text = source.toString();
        spans = new SpanStore(source, 0, source.length());
    }

    public static SpannedString valueOf(CharSequence source) {
        return source instanceof SpannedString string ? string : new SpannedString(source);
    }

    public CharSequence subSequence(int start, int end) { return new SpannedString(new SpannableString(this).subSequence(start, end)); }
    public final void getChars(int start, int end, char[] dest, int off) { text.getChars(start, end, dest, off); }
    public final int length() { return text.length(); }
    public final char charAt(int index) { return text.charAt(index); }
    public final String toString() { return text; }
    public int getSpanStart(Object what) { return spans.start(what); }
    public int getSpanEnd(Object what) { return spans.end(what); }
    public int getSpanFlags(Object what) { return spans.flags(what); }
    public int nextSpanTransition(int start, int limit, Class kind) { return spans.nextTransition(start, limit, kind); }
    public <T> T[] getSpans(int start, int end, Class<T> kind) { return spans.get(start, end, kind); }
    @Override public int hashCode() { return text.hashCode(); }
    @Override public boolean equals(Object other) { return other instanceof Spanned spanned && text.equals(spanned.toString()); }
}
