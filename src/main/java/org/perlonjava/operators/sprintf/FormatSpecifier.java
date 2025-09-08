package org.perlonjava.operators.sprintf;

public class FormatSpecifier {
    public int startPos;
    public int endPos;
    public String raw = "";

    // Parsed components
    public boolean invalidDueToSpace = false;
    public String invalidLengthModifierWarning;
    public Integer parameterIndex;      // null or 1-based index
    public String flags = "";           // combination of -, +, space, #, 0
    public Integer width;               // null if not specified
    public boolean widthFromArg;        // true if width is from argument
    public Integer widthArgIndex;       // parameter index for width (1-based)
    public Integer precision;           // null if not specified
    public boolean precisionFromArg;    // true if precision is from argument
    public Integer precisionArgIndex;   // parameter index for precision (1-based)
    public String lengthModifier;       // h, l, ll, etc.
    public boolean vectorFlag;
    public char conversionChar;
    public boolean isValid = true;
    public String errorMessage;
    public boolean isOverlapping = false;  // Don't include in output, just warn
}
