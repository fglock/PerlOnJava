package org.perlonjava.runtime.regex;

public class RegexTimeoutCharSequence implements CharSequence {
    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    private static final int CHECK_INTERVAL = 4096;

    private final CharSequence inner;
    private final long deadlineNanos;
    private int checkCount;

    public RegexTimeoutCharSequence(CharSequence inner) {
        this(inner, DEFAULT_TIMEOUT_MS);
    }

    public RegexTimeoutCharSequence(CharSequence inner, long timeoutMillis) {
        this.inner = inner;
        this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    private RegexTimeoutCharSequence(CharSequence inner, long deadlineNanos, boolean shared) {
        this.inner = inner;
        this.deadlineNanos = deadlineNanos;
    }

    @Override
    public char charAt(int index) {
        if (++checkCount % CHECK_INTERVAL == 0 && System.nanoTime() > deadlineNanos) {
            throw new RegexTimeoutException(
                    "Regex matching timed out after " + DEFAULT_TIMEOUT_MS + "ms (catastrophic backtracking detected)");
        }
        return inner.charAt(index);
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new RegexTimeoutCharSequence(inner.subSequence(start, end), deadlineNanos, true);
    }

    @Override
    public String toString() {
        return inner.toString();
    }
}
