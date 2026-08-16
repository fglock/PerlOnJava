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

/** Runtime-neutral result of resolving a dynamic regex callout. */
public final class DynamicPatternResult {
    private final Regex regex;
    private final CalloutHandler calloutHandler;
    private final Object backtrackToken;

    public DynamicPatternResult(Regex regex, CalloutHandler calloutHandler, Object backtrackToken) {
        if (regex == null) throw new NullPointerException("regex");
        this.regex = regex;
        this.calloutHandler = calloutHandler;
        this.backtrackToken = backtrackToken;
    }

    public Regex getRegex() { return regex; }
    public CalloutHandler getCalloutHandler() { return calloutHandler; }
    public Object getBacktrackToken() { return backtrackToken; }
}
