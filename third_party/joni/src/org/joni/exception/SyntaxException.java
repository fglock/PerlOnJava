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
package org.joni.exception;

public class SyntaxException extends JOniException{
    private static final long serialVersionUID = 7862720128961874288L;
    public static final int UNKNOWN_PATTERN_POSITION = -1;

    private final int patternPosition;
    private final String diagnosticMessage;

    public SyntaxException(String message) {
        this(message, UNKNOWN_PATTERN_POSITION);
    }

    public SyntaxException(String message, int patternPosition) {
        this(message, patternPosition, message);
    }

    public SyntaxException(String message, int patternPosition, String diagnosticMessage) {
        super(message);
        this.patternPosition = patternPosition;
        this.diagnosticMessage = diagnosticMessage;
    }

    /**
     * Returns the byte offset in the pattern at which compilation detected the
     * error, or {@link #UNKNOWN_PATTERN_POSITION} when the producer cannot
     * provide one.
     */
    public int getPatternPosition() {
        return patternPosition;
    }

    /**
     * Returns the source-aware diagnostic when the parser retained one, while
     * {@link #getMessage()} remains the stable engine-level error contract.
     */
    public String getDiagnosticMessage() {
        return diagnosticMessage;
    }
}
