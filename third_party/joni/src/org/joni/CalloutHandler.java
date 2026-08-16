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

/** Runtime-neutral bridge invoked from the matcher bytecode loop. */
public interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView match);

    /**
     * Resolve a match-time dynamic subprogram. Implementations that do not
     * support dynamic programs retain the fail-fast default.
     */
    default DynamicPatternResult executeDynamic(int calloutId, MatchView match) {
        throw new UnsupportedOperationException("dynamic regex callouts are not supported");
    }

    void unwind(Object backtrackToken);

    /** Complete a token on a successful path. The default preserves the original cleanup contract. */
    default void complete(Object backtrackToken) {
        unwind(backtrackToken);
    }
}
