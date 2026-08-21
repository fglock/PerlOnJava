/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.joni;

import static org.joni.Option.isFindLongest;

import org.jcodings.Encoding;
import org.jcodings.IntHolder;
import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ASCIIEncoding;
import org.joni.constants.internal.AnchorType;
import org.joni.exception.TimeoutException;

public abstract class Matcher extends IntHolder {
    static final InterruptedException INTERRUPTED_EXCEPTION = new InterruptedException();
    static final InterruptedException TIMEOUT_EXCEPTION = new TimeoutException();
    public static final int FAILED = -1;
    public static final int INTERRUPTED = -2;

    protected final Regex regex;
    protected final Encoding enc;

    protected final byte[]bytes;
    protected final int str;
    protected final int end;

    protected int msaStart;
    protected int msaOptions;
    protected final Region msaRegion;
    protected int msaBestLen;
    protected int msaBestS;
    protected int msaGpos;

    protected int msaBegin;
    protected int msaEnd;

    protected long timeout;  // nanoseconds
    private CalloutHandler calloutHandler;
    private CharacterPropertyResolver.DeferredResolver deferredPropertyResolver;
    private LocaleResolver localeResolver;
    private CharacterPropertyResolver.Result[][] deferredPropertyCache;
    private boolean abortSearch;
    private int skipSearchTo = -1;
    protected String controlMark;
    protected String controlError;
    protected boolean controlVerbEncountered;

    private static final class SkipSearch extends RuntimeException {
        final int target;
        SkipSearch(int target) {
            super(null, null, false, false);
            this.target = target;
        }
    }

    private static final class AbortSearch extends RuntimeException {
        static final AbortSearch INSTANCE = new AbortSearch();
        private AbortSearch() { super(null, null, false, false); }
    }

    // nanoseconds since entering searchCommon (underlying machines will check during interrupt checks
    // which will cheapen how often we look but also it should be granular enough to not matter).
    protected long startTime;

    Matcher(Regex regex, Region region, byte[]bytes, int p, int end) {
        this(regex, region, bytes, p, end, -1);
    }

    // FIXME: For next major version this should be the main constructor and MatcherFactory should
    // have the abstract method be for this.
    Matcher(Regex regex, Region region, byte[]bytes, int p, int end, long timeout) {
        this.regex = regex;
        this.enc = regex.enc;
        this.bytes = bytes;
        this.str = p;
        this.end = end;
        this.msaRegion = region;
        this.timeout = timeout;
    }

    // main matching method
    protected abstract int matchAt(int range, int sstart, int sprev, boolean interrupt) throws InterruptedException;

    protected abstract void stateCheckBuffInit(int strLength, int offset, int stateNum);
    protected abstract void stateCheckBuffClear();

    public abstract void interrupt();

    public final Region getRegion() {
        return msaRegion;
    }

    public final Region getEagerRegion() {
        return msaRegion != null ? msaRegion : Region.newRegion(msaBegin, msaEnd);
    }

    public final int getBegin() {
        return msaBegin;
    }

    public final int getEnd() {
        return msaEnd;
    }

    /** Capture offsets exposed after a match; bytecode matchers may provide a language view. */
    public int captureBegin(int capture) {
        if (capture == 0) return msaBegin;
        Region region = getEagerRegion();
        return capture < region.getNumRegs() ? region.getBeg(capture) : Region.REGION_NOTPOS;
    }

    public int captureEnd(int capture) {
        if (capture == 0) return msaEnd;
        Region region = getEagerRegion();
        return capture < region.getNumRegs() ? region.getEnd(capture) : Region.REGION_NOTPOS;
    }

    /** Physical named-group definition offsets, used when definitions share a number. */
    public int physicalNamedCaptureBegin(int capture) {
        return Region.REGION_NOTPOS;
    }

    public int physicalNamedCaptureEnd(int capture) {
        return Region.REGION_NOTPOS;
    }

    /** Most recently closed active capture; engines without this view return -1. */
    public int lastClosedCapture() {
        return -1;
    }

    public final String getControlMark() {
        return controlMark;
    }

    public final String getControlError() {
        return controlError;
    }

    public final boolean hasEncounteredControlVerb() {
        return controlVerbEncountered;
    }

    protected final void msaInit(int option, int start, int gpos) {
        msaOptions = option;
        msaStart = start;
        msaGpos = gpos;
        if (Config.USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE) msaBestLen = -1;
    }

    protected final void requestSearchSkip(int target) {
        if (target > skipSearchTo) skipSearchTo = target;
    }

    protected final void requestSearchAbort() {
        abortSearch = true;
    }

    protected final int takeSearchSkipRequest() {
        int target = skipSearchTo;
        skipSearchTo = -1;
        return target;
    }

    protected final boolean takeSearchAbortRequest() {
        boolean requested = abortSearch;
        abortSearch = false;
        return requested;
    }

    public final int match(int at, int range, int option) {
        controlVerbEncountered = false;
        try {
            return matchCommon(at, range, option, false);
        } catch (InterruptedException ex) {
            return INTERRUPTED;
        }
    }

    public final int matchInterruptible(int at, int range, int option) throws InterruptedException {
        controlVerbEncountered = false;
        return matchCommon(at, range, option, true);
    }

    private final int matchCommon(int at, int range, int option, boolean interrupt) throws InterruptedException {
        msaInit(option, at, at);

        if (Config.USE_CEC) {
            int offset = at - str;
            stateCheckBuffInit(end - str, offset, regex.numCombExpCheck); // move it to construction?
        } // USE_COMBINATION_EXPLOSION_CHECK

        int prev = enc.prevCharHead(bytes, str, at, end);

        if (Config.USE_MATCH_RANGE_MUST_BE_INSIDE_OF_SPECIFIED_RANGE) {
            return matchAt(end /*range*/, at, prev, interrupt);
        } else {
            return matchAt(range /*range*/, at, prev, interrupt);
        }
    }

    private int logicalCharacterLength(int p) {
        WideScalarCodec codec = regex.wideScalarCodec;
        if (codec != null && p < end) {
            WideScalarCodec.Decoded decoded = codec.decode(bytes, p, end, enc);
            if (decoded != null && decoded.end() > p && decoded.end() <= end) {
                return decoded.end() - p;
            }
        }
        return enc.length(bytes, p, end);
    }

    int low, high; // these are the return values
    private final boolean forwardSearchRange(byte[]bytes, int str, int end, int s, int range, IntHolder lowPrev) {
        int pprev = -1;
        int p = s;

        if (Config.DEBUG_SEARCH) debugForwardSearchRange(str, end, s, range);

        if (regex.dMin > 0) {
            if (enc.isSingleByte()) {
                p += regex.dMin;
            } else {
                int q = p + regex.dMin;
                while (p < q && p < end) p += logicalCharacterLength(p);
            }
        }

        retry:while (true) {
            if (Config.DEBUG_SEARCH) debugSearch(regex.forward.getName(), p, end, range);
            p = regex.forward.search(this, bytes, p, end, range);

            if (p != -1 && p < range) {
                if (p - regex.dMin < s) {
                    // retry_gate:
                    pprev = p;
                    p += logicalCharacterLength(p);
                    continue retry;
                }

                if (regex.subAnchor != 0) {
                    switch (regex.subAnchor) {
                    case AnchorType.BEGIN_LINE:
                        if (p != str) {
                            int prev = enc.prevCharHead(bytes, (pprev != -1) ? pprev : str, p, end);
                            if (!enc.isNewLine(bytes, prev, end)) {
                                // goto retry_gate;
                                pprev = p;
                                p += logicalCharacterLength(p);
                                continue retry;
                            }
                        }
                        break;

                    case AnchorType.END_LINE:
                        if (p == end) {
                            if (!Config.USE_NEWLINE_AT_END_OF_STRING_HAS_EMPTY_LINE) {
                                int prev = enc.prevCharHead(bytes, (pprev != -1) ? pprev : str, p, end);
                                if (prev != -1 && enc.isNewLine(bytes, prev, end)) {
                                    // goto retry_gate;
                                    pprev = p;
                                    p += logicalCharacterLength(p);
                                    continue retry;
                                }
                            }
                        } else if (!enc.isNewLine(bytes, p, end) && (!Config.USE_CRNL_AS_LINE_TERMINATOR || !enc.isMbcCrnl(bytes, p, end))) {
                            //if () break;
                            // goto retry_gate;
                            pprev = p;
                            p += logicalCharacterLength(p);
                            continue retry;
                        }
                        break;
                    } // switch
                }

                if (regex.dMax == 0) {
                    low = p;
                    if (lowPrev != null) { // ??? // remove null checks
                        if (low > s) {
                            lowPrev.value = enc.prevCharHead(bytes, s, p, end);
                        } else {
                            lowPrev.value = enc.prevCharHead(bytes, (pprev != -1) ? pprev : str, p, end);
                        }
                    }
                } else {
                    if (regex.dMax != MinMaxLen.INFINITE_DISTANCE) {
                        low = p - regex.dMax;

                        if (low > s) {
                            low = enc.rightAdjustCharHeadWithPrev(bytes, s, low, end, lowPrev);
                            if (lowPrev != null && lowPrev.value == -1) {
                                lowPrev.value = enc.prevCharHead(bytes, (pprev != -1) ? pprev : s, low, end);
                            }
                        } else {
                            if (lowPrev != null) {
                                lowPrev.value = enc.prevCharHead(bytes, (pprev != -1) ? pprev : str, low, end);
                            }
                        }
                    }
                }
                /* no needs to adjust *high, *high is used as range check only */
                high = p - regex.dMin;
                if (Config.DEBUG_SEARCH) debugForwardSearchRangeSuccess(str, low, high);
                return true;    /* success */
            }
            return false;   /* fail */
        } //while
    }

    // low, high
    private final boolean backwardSearchRange(byte[]bytes, int str, int end, int s, int range, int adjrange) {
        range += regex.dMin;
        int p = s;

        retry:while (true) {
            p = regex.backward.search(this, bytes, range, adjrange, end, p, s, range);

            if (p != -1) {
                if (regex.subAnchor != 0) {
                    switch (regex.subAnchor) {
                    case AnchorType.BEGIN_LINE:
                        if (p != str) {
                            int prev = enc.prevCharHead(bytes, str, p, end);
                            if (!enc.isNewLine(bytes, prev, end)) {
                                p = prev;
                                continue retry;
                            }
                        }
                        break;

                    case AnchorType.END_LINE:
                        if (p == end) {
                            if (!Config.USE_NEWLINE_AT_END_OF_STRING_HAS_EMPTY_LINE) {
                                int prev = enc.prevCharHead(bytes, adjrange, p, end);
                                if (prev == -1) return false;
                                if (enc.isNewLine(bytes, prev, end)) {
                                    p = prev;
                                    continue retry;
                                }
                            }
                        } else if (!enc.isNewLine(bytes, p, end) && (!Config.USE_CRNL_AS_LINE_TERMINATOR || !enc.isMbcCrnl(bytes, p, end))) {
                            p = enc.prevCharHead(bytes, adjrange, p, end);
                            if (p == -1) return false;
                            continue retry;
                        }
                        break;
                    } // switch
                }

                /* no needs to adjust *high, *high is used as range check only */
                if (regex.dMax != MinMaxLen.INFINITE_DISTANCE) {
                    low = p - regex.dMax;
                    high = p - regex.dMin;
                    high = enc.rightAdjustCharHead(bytes, adjrange, high, end);
                }

                if (Config.DEBUG_SEARCH) debugBackwardSearchRange(str, low, high);
                return true;
            }

            if (Config.DEBUG_SEARCH) Config.log.println("backward_search_range: fail.");
            return false;
        } // while
    }

    // MATCH_AND_RETURN_CHECK
    private boolean matchCheck(int upperRange, int s, int prev, boolean interrupt) throws InterruptedException {
        if (Config.USE_MATCH_RANGE_MUST_BE_INSIDE_OF_SPECIFIED_RANGE) {
            if (Config.USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE) {
                //range = upperRange;
                if (matchAt(upperRange, s, prev, interrupt) != -1) {
                    if (!isFindLongest(regex.options)) return true;
                }
            } else {
                //range = upperRange;
                if (matchAt(upperRange, s, prev, interrupt) != -1) return true;
            }
        } else {
            if (Config.USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE) {
                if (matchAt(end, s, prev, interrupt) != -1) {
                    //range = upperRange;
                    if (!isFindLongest(regex.options)) return true;
                }
            } else {
                //range = upperRange;
                if (matchAt(end, s, prev, interrupt) != -1) return true;
            }
        }
        if (abortSearch) throw AbortSearch.INSTANCE;
        if (skipSearchTo >= 0) throw new SkipSearch(skipSearchTo);
        return false;
    }

    public final int search(int start, int range, int option) {
        controlVerbEncountered = false;
        int next = start;
        while (true) {
            abortSearch = false;
            skipSearchTo = -1;
            try {
                return searchCommon(next, next, range, option, false);
            } catch (SkipSearch skip) {
                if (skip.target < next || skip.target > range) return FAILED;
                if (skip.target == next) {
                    if (next >= range) return FAILED;
                    next += logicalCharacterLength(next);
                } else {
                    next = skip.target;
                }
            } catch (AbortSearch abort) {
                return FAILED;
            } catch (InterruptedException ex) {
                return INTERRUPTED;
            }
        }
    }

    public final int search(int gpos, int start, int range, int option) {
        controlVerbEncountered = false;
        try {
            return searchCommon(gpos, start, range, option, false);
        } catch (SkipSearch | AbortSearch control) {
            return FAILED;
        } catch (InterruptedException ex) {
            return INTERRUPTED;
        }
    }

    public final int searchInterruptible(int start, int range, int option) throws InterruptedException {
        controlVerbEncountered = false;
        int next = start;
        while (true) {
            abortSearch = false;
            skipSearchTo = -1;
            try {
                return searchCommon(next, next, range, option, true);
            } catch (SkipSearch skip) {
                if (skip.target < next || skip.target > range) return FAILED;
                if (skip.target == next) {
                    if (next >= range) return FAILED;
                    next += logicalCharacterLength(next);
                } else {
                    next = skip.target;
                }
            } catch (AbortSearch abort) {
                return FAILED;
            }
        }
    }

    public final int searchInterruptible(int gpos, int start, int range, int option) throws InterruptedException {
        controlVerbEncountered = false;
        try {
            return searchCommon(gpos, start, range, option, true);
        } catch (SkipSearch | AbortSearch control) {
            return FAILED;
        }
    }

    private final int searchCommon(int gpos, int start, int range, int option, boolean interrupt) throws InterruptedException {
        if (timeout != -1) startTime = System.nanoTime();
        int s, prev;
        int origStart = start;
        int origRange = range;

        if (Config.DEBUG_SEARCH) debugSearch(str, end, start, range);

        if (start > end || start < str) return FAILED;

        /* anchor optimize: resume search range */
        if (regex.anchor != 0 && str < end) {
            int minSemiEnd, maxSemiEnd;

            if ((regex.anchor & AnchorType.BEGIN_POSITION) != 0) {
                /* search start-position only */
                // !begin_position:!
                int lower = Math.min(start, range);
                int upper = Math.max(start, range);
                if (gpos < lower || gpos > upper) return FAILED;
                start = range = gpos;
            } else if ((regex.anchor & AnchorType.BEGIN_BUF) != 0) {
                /* search str-position only */
                if (range > start) {
                    if (start != str) return FAILED; // mismatch_no_msa;
                    range = str + 1;
                } else {
                    if (range <= str) {
                        start = str;
                        range = str;
                    } else {
                        return FAILED; // mismatch_no_msa;
                    }
                }
            } else if ((regex.anchor & AnchorType.END_BUF) != 0) {
                minSemiEnd = maxSemiEnd = end;
                // !end_buf:!
                if (endBuf(start, range, minSemiEnd, maxSemiEnd)) return FAILED; // mismatch_no_msa;
            } else if ((regex.anchor & AnchorType.SEMI_END_BUF) != 0) {
                int preEnd = enc.stepBack(bytes, str, end, end, 1);
                maxSemiEnd = end;
                if (enc.isNewLine(bytes, preEnd, end)) {
                    minSemiEnd = preEnd;
                    if (Config.USE_CRNL_AS_LINE_TERMINATOR) {
                        preEnd = enc.stepBack(bytes, str, preEnd, end, 1);
                        if (preEnd != -1 && enc.isMbcCrnl(bytes, preEnd, end)) {
                            minSemiEnd = preEnd;
                        }
                    }
                    if (minSemiEnd > str && start <= minSemiEnd) {
                        // !goto end_buf;!
                        if (endBuf(start, range, minSemiEnd, maxSemiEnd)) return FAILED; // mismatch_no_msa;
                    }
                } else {
                    minSemiEnd = end;
                    // !goto end_buf;!
                    if (endBuf(start, range, minSemiEnd, maxSemiEnd)) return FAILED; // mismatch_no_msa;
                }
            } else if ((regex.anchor & AnchorType.ANYCHAR_STAR_ML) != 0) {
                // goto !begin_position;!
                if (range > start) {
                    if (gpos > start) {
                        if (gpos < range) range = gpos + 1;
                    } else {
                        range = start + 1;
                    }
                } else {
                    range = start;
                }
            }

        } else if (str == end) { /* empty string */
            // empty address ?
            if (Config.DEBUG_SEARCH) Config.log.println("onig_search: empty string.");

            if (regex.thresholdLength == 0) {
                s = start = str;
                prev = -1;
                msaInit(option, start, start);

                if (Config.USE_CEC) stateCheckBuffClear();

                if (matchCheck(end, s, prev, interrupt)) return match(s);
                return mismatch();
            }
            return FAILED; // goto mismatch_no_msa;
        }

        if (Config.DEBUG_SEARCH) debugSearch(str, end, start, range);

        msaInit(option, origStart, gpos);
        if (Config.USE_CEC) {
            int offset = Math.min(start, range) - str;
            stateCheckBuffInit(end - str, offset, regex.numCombExpCheck);
        }

        s = start;
        if (range > start) {    /* forward search */
            if (s > str) {
                prev = enc.prevCharHead(bytes, str, s, end);
            } else {
                prev = 0; // -1
            }

            // Locale character tables are matcher-local, so compile-time maps
            // cannot safely reject candidates selected by those tables.
            if (regex.forward != null && localeResolver == null) {
                int schRange = range;
                if (regex.dMax != 0) {
                    if (regex.dMax == MinMaxLen.INFINITE_DISTANCE) {
                        schRange = end;
                    } else {
                        schRange += regex.dMax;
                        if (schRange > end) schRange = end;
                    }
                }
                if ((end - start) < regex.thresholdLength) return mismatch();

                if (regex.dMax != MinMaxLen.INFINITE_DISTANCE) {
                    do {
                        if (!forwardSearchRange(bytes, str, end, s, schRange, this)) return mismatch(); // low, high, lowPrev
                        if (s < low) {
                            s = low;
                            prev = value;
                        }
                        while (s <= high) {
                            if (matchCheck(origRange, s, prev, interrupt)) return match(s); // ???
                            prev = s;
                            s += logicalCharacterLength(s);
                        }
                    } while (s < range);
                    return mismatch();

                } else { /* check only. */
                    if (!forwardSearchRange(bytes, str, end, s, schRange, null)) return mismatch();

                    if ((regex.anchor & AnchorType.ANYCHAR_STAR) != 0) {
                        do {
                            if (matchCheck(origRange, s, prev, interrupt)) return match(s);
                            prev = s;
                            s += logicalCharacterLength(s);

                            if ((regex.anchor & (AnchorType.LOOK_BEHIND | AnchorType.PREC_READ_NOT)) == 0) {
                                while (!enc.isNewLine(bytes, prev, end) && s < range) {
                                    prev = s;
                                    s += logicalCharacterLength(s);
                                }
                            }
                        } while (s < range);
                        return mismatch();
                    }
                }
            }

            do {
                if (matchCheck(origRange, s, prev, interrupt)) return match(s);
                prev = s;
                s += logicalCharacterLength(s);
            } while (s < range);

            if (s == range) { /* because empty match with /$/. */
                if (matchCheck(origRange, s, prev, interrupt)) return match(s);
            }
        } else { /* backward search */
            if (Config.USE_MATCH_RANGE_MUST_BE_INSIDE_OF_SPECIFIED_RANGE) {
                if (origStart < end) {
                    origStart += logicalCharacterLength(origStart); // /* is upper range */
                }
            }

            if (regex.backward != null) {
                int adjrange;
                if (range < end) {
                    adjrange = enc.leftAdjustCharHead(bytes, str, range, end);
                } else {
                    adjrange = end;
                }
                if (regex.dMax != MinMaxLen.INFINITE_DISTANCE && (end - range) >= regex.thresholdLength) {
                    do {
                        int schStart = s + regex.dMax;
                        if (schStart > end) schStart = end;
                        if (!backwardSearchRange(bytes, str, end, schStart, range, adjrange)) return mismatch(); // low, high
                        if (s > high) s = high;
                        while (s != -1 && s >= low) {
                            prev = enc.prevCharHead(bytes, str, s, end);
                            if (matchCheck(origStart, s, prev, interrupt)) return match(s);
                            s = prev;
                        }
                    } while (s >= range);
                    return mismatch();
                } else { /* check only. */
                    if ((end - range) < regex.thresholdLength) return mismatch();

                    int schStart = s;
                    if (regex.dMax != 0) {
                        if (regex.dMax == MinMaxLen.INFINITE_DISTANCE) {
                            schStart = end;
                        } else {
                            schStart += regex.dMax;
                            if (schStart > end) {
                                schStart = end;
                            } else {
                                schStart = enc.leftAdjustCharHead(bytes, start, schStart, end);
                            }
                        }
                    }
                    if (!backwardSearchRange(bytes, str, end, schStart, range, adjrange)) return mismatch();
                }
            }

            do {
                prev = enc.prevCharHead(bytes, str, s, end);
                if (matchCheck(origStart, s, prev, interrupt)) return match(s);
                s = prev;
            } while (s >= range);

        }
        return mismatch();
    }

    private final boolean endBuf(int start, int range, int minSemiEnd, int maxSemiEnd) {
        if ((maxSemiEnd - str) < regex.anchorDmin) return true; // mismatch_no_msa;

        if (range > start) {
            if ((minSemiEnd - start) > regex.anchorDmax) {
                start = minSemiEnd - regex.anchorDmax;
                if (start < end) {
                    start = enc.rightAdjustCharHead(bytes, str, start, end);
                } else { /* match with empty at end */
                    start = enc.prevCharHead(bytes, str, end, end);
                }
            }
            if ((maxSemiEnd - (range - 1)) < regex.anchorDmin) {
                range = maxSemiEnd - regex.anchorDmin + 1;
            }
            if (start > range || start == range && regex.anchorDmin > 0) {
                return true; // mismatch_no_msa;
            }
        } else {
            if ((minSemiEnd - range) > regex.anchorDmax) {
                range = minSemiEnd - regex.anchorDmax;
            }
            if ((maxSemiEnd - start) < regex.anchorDmin) {
                start = maxSemiEnd - regex.anchorDmin;
                start = enc.leftAdjustCharHead(bytes, str, start, end);
            }
            if (range > start) return true; // mismatch_no_msa;
        }
        return false;
    }

    private final int match(int s) {
        return s - str; // sstart ???
    }

    private final int mismatch() {
        if (Config.USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE) {
            if (msaBestLen >= 0) {
                int s = msaBestS;
                return match(s);
            }
        }
        // falls through finish:
        return FAILED;
    }

    private byte[]icbuf;

    protected final byte[]icbuf() {
        return icbuf == null ? icbuf = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN] : icbuf;
    }

    static boolean isMbcAsciiWord(Encoding enc, byte[]bytes, int p, int end) { // ONIGENC_IS_MBC_ASCII_WORD
        return ASCIIEncoding.INSTANCE.isCodeCType(enc.mbcToCode(bytes, p, end), CharacterType.WORD);
    }

    private final void debugForwardSearchRange(int str, int end, int s, int range) {
        if (Config.DEBUG_SEARCH) {
            Config.log.println("forward_search_range: " +
                "str: " + str +
                ", end: " + end +
                ", s: " + s +
                ", range: " + range);
        }
    }

    private final void debugForwardSearchRangeSuccess(int str, int low, int high) {
        if (Config.DEBUG_SEARCH) {
            Config.log.println("forward_search_range success: " +
                "low: " + (low - str) +
                ", high: " + (high - str) +
                ", dmin: " + regex.dMin +
                ", dmax: " + regex.dMax);
        }
    }

    private final void debugSearch(int str, int end, int start, int range) {
        if (Config.DEBUG_SEARCH) {
            Config.log.println("onig_search (entry point): " +
                "str: " + str +
                ", end: " + (end - str) +
                ", start: " + (start - str) +
                ", range " + (range - str));
        }
    }

    private final void debugBackwardSearchRange(int str, int low, int high) {
        if (Config.DEBUG_SEARCH) {
            Config.log.println("backward_search_range: "+
                "low: " + (low - str) +
                ", high: " + (high - str));
        }
    }

    static void debugSearch(String name, int textP, int textEnd, int textRange) {
        Config.log.println(name + ": text: " + textP + ", text_end: " + textEnd + ", text_range: " + textRange);
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public final void setCalloutHandler(CalloutHandler handler) {
        calloutHandler = handler;
    }

    /** Attaches a matcher-local service for parser-retained property terms. */
    public final void setDeferredPropertyResolver(
            CharacterPropertyResolver.DeferredResolver resolver) {
        deferredPropertyResolver = resolver;
        deferredPropertyCache = null;
    }

    /** Attaches immutable matcher-local character semantics for Perl {@code /l}. */
    public final void setLocaleResolver(LocaleResolver resolver) {
        localeResolver = resolver;
    }

    protected final boolean isLocaleCodeCType(int codePoint, int characterType,
            boolean fallback) {
        return localeResolver == null
                ? fallback : localeResolver.isCodeCType(codePoint, characterType);
    }

    protected final boolean localeClassMembership(
            org.joni.ast.CClassNode.DebugClassExpression expression,
            int codePoint, boolean fallback) {
        if (localeResolver == null || expression == null
                || !expression.authoritative()
                || expression.terms().size() != 1
                || !expression.literalCodePoints().isEmpty()) return fallback;
        org.joni.ast.CClassNode.DebugClassTerm term = expression.terms().get(0);
        if (!Option.isPerlLocale(term.lexicalOption())) return fallback;
        boolean member = localeResolver.isCodeCType(codePoint, term.ctype());
        if (term.tokenNegated()) member = !member;
        return expression.outerNegated() ? !member : member;
    }

    protected final CharacterPropertyResolver.Result[] resolveDeferredProperties(
            int classIndex, org.joni.ast.CClassNode characterClass) {
        if (!characterClass.hasDeferredProperties()) return null;
        if (deferredPropertyResolver == null) {
            throw new CharacterPropertyResolver.ResolutionException(
                    "deferred character property has no matcher resolver");
        }
        if (deferredPropertyCache == null) {
            deferredPropertyCache = new CharacterPropertyResolver.Result[
                    regex.wideScalarClasses.length][];
        }
        CharacterPropertyResolver.Result[] cached =
                deferredPropertyCache[classIndex];
        if (cached != null) return cached;

        CharacterPropertyResolver.Result[] resolved =
                new CharacterPropertyResolver.Result[
                        characterClass.deferredPropertyCount()];
        for (int index = 0; index < resolved.length; index++) {
            CharacterPropertyResolver.DeferredProperty property =
                    characterClass.deferredProperty(index);
            CharacterPropertyResolver.Result result;
            try {
                result = deferredPropertyResolver.resolve(property.name(),
                        property.context(), property.option(),
                        property.position(), enc);
            } catch (CharacterPropertyResolver.ResolutionException failure) {
                if (failure.getPosition() >= 0) throw failure;
                throw new CharacterPropertyResolver.ResolutionException(
                        failure.getMessage(), property.position());
            }
            if (result == null || result.isDeferred()) {
                throw new CharacterPropertyResolver.ResolutionException(
                        "deferred character property remained unresolved");
            }
            resolved[index] = new CharacterPropertyResolver.Result(
                    result.ranges, result.wideRanges, result.caseFold);
        }
        deferredPropertyCache[classIndex] = resolved;
        return resolved;
    }

    final CalloutHandler getCalloutHandler() {
        return calloutHandler;
    }
}
