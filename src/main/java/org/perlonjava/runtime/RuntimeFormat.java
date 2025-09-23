package org.perlonjava.runtime;

import org.perlonjava.astnode.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Represents a runtime format in Perl. Formats are used with the write() function
 * to produce formatted output. This class provides methods to manipulate and interact
 * with formats in the runtime environment.
 */
public class RuntimeFormat extends RuntimeScalar implements RuntimeScalarReference {

    // The name of the format
    public String formatName;
    
    // The format template string
    public String formatTemplate;
    
    // Whether this format is defined
    private boolean isDefined;
    
    // Compiled format lines for execution
    private List<FormatLine> compiledLines;
    
    // Whether the format has been compiled
    private boolean isCompiled = false;

    /**
     * Constructor for RuntimeFormat.
     * Initializes a new instance of the RuntimeFormat class with the specified format name.
     *
     * @param formatName The name of the format.
     */
    public RuntimeFormat(String formatName) {
        this.formatName = formatName;
        this.formatTemplate = "";
        this.isDefined = false;
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.FORMAT;
        this.value = this;
    }

    /**
     * Constructor for RuntimeFormat with template.
     * Initializes a new instance of the RuntimeFormat class with the specified format name and template.
     *
     * @param formatName The name of the format.
     * @param formatTemplate The format template string.
     */
    public RuntimeFormat(String formatName, String formatTemplate) {
        this.formatName = formatName;
        this.formatTemplate = formatTemplate;
        this.isDefined = true;
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.FORMAT;
        this.value = this;
    }

    /**
     * Sets the format template.
     *
     * @param template The format template string.
     * @return This RuntimeFormat instance.
     */
    public RuntimeFormat setTemplate(String template) {
        this.formatTemplate = template;
        this.isDefined = true;
        this.isCompiled = false; // Reset compilation status
        this.compiledLines = null;
        return this;
    }

    /**
     * Sets the compiled format lines directly.
     * This is used when the format is created from parsed AST nodes.
     *
     * @param lines The compiled format lines.
     * @return This RuntimeFormat instance.
     */
    public RuntimeFormat setCompiledLines(List<FormatLine> lines) {
        this.compiledLines = new ArrayList<>(lines);
        this.isCompiled = true;
        this.isDefined = true;
        return this;
    }

    /**
     * Gets the format template.
     *
     * @return The format template string.
     */
    public String getTemplate() {
        return this.formatTemplate;
    }

    /**
     * Checks if the format is defined.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean isFormatDefined() {
        return this.isDefined;
    }

    /**
     * Undefines the format.
     */
    public void undefineFormat() {
        this.formatTemplate = "";
        this.isDefined = false;
    }

    /**
     * Counts the number of elements in the format.
     *
     * @return The number of elements, which is always 1 for a format.
     */
    public int countElements() {
        return 1;
    }

    /**
     * Returns a string representation of the format.
     *
     * @return A string in the format "FORMAT(formatName)".
     */
    public String toString() {
        return "FORMAT(" + this.formatName + ")";
    }

    /**
     * Returns a string representation of the format reference.
     * The format is "FORMAT(hashCode)" where hashCode is the unique identifier for this instance.
     *
     * @return A string representation of the format reference.
     */
    public String toStringRef() {
        String ref = "FORMAT(0x" + this.hashCode() + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the format reference.
     * This is the hash code of the current instance.
     *
     * @return The hash code of this instance.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the format reference.
     * This is the hash code of the current instance, cast to a double.
     *
     * @return The hash code of this instance as a double.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the format reference.
     * This always returns true, indicating the presence of the format.
     *
     * @return Always true.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Returns a boolean indicating whether the format is defined.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean getDefinedBoolean() {
        return this.isDefined;
    }

    /**
     * Gets the scalar value of the format.
     *
     * @return A RuntimeScalar representing the format.
     */
    public RuntimeScalar scalar() {
        return this;
    }

    /**
     * Retrieves the boolean value of the format.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean getBoolean() {
        return this.isDefined;
    }

    /**
     * Creates a reference to the format.
     *
     * @return A RuntimeScalar representing the reference to the format.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.REFERENCE;
        ret.value = this;
        return ret;
    }

    /**
     * Gets the list value of the format.
     *
     * @return A RuntimeList containing the scalar representation of the format.
     */
    public RuntimeList getList() {
        return new RuntimeList(this.scalar());
    }

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar to which this format will be added.
     * @return The updated RuntimeScalar.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    /**
     * Sets itself from a RuntimeList.
     *
     * @param value The RuntimeList from which this format will be set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return super.iterator();
    }

    /**
     * Gets the Format alias into an Array.
     *
     * @param arr The RuntimeArray to which the alias will be added.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this.scalar());
        return arr;
    }

    /**
     * Saves the current state of the format.
     */
    @Override
    public void dynamicSaveState() {
        // Format state is immutable once created, no need to save state
    }

    /**
     * Restores the most recently saved state of the format.
     */
    @Override
    public void dynamicRestoreState() {
        // Format state is immutable once created, no need to restore state
    }

    /**
     * Executes the format with the given arguments and returns the formatted output.
     * This is the main method for format execution, similar to Perl's write() function.
     *
     * @param args The arguments to use for format field values
     * @return The formatted output as a string
     */
    public String execute(RuntimeList args) {
        if (!isDefined) {
            throw new RuntimeException("Undefined format: " + formatName);
        }

        // Ensure format is compiled
        if (!isCompiled) {
            compileFormat();
        }

        if (compiledLines == null || compiledLines.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        List<RuntimeScalar> argList = new ArrayList<>();
        for (RuntimeBase element : args.elements) {
            argList.add(element.scalar());
        }
        int argIndex = 0;

        // Process format lines in pairs (picture line + argument line)
        for (int i = 0; i < compiledLines.size(); i++) {
            FormatLine line = compiledLines.get(i);

            if (line instanceof CommentLine) {
                // Skip comment lines
                continue;
            } else if (line instanceof PictureLine pictureLine) {
                // Look for the corresponding argument line
                ArgumentLine argLine = null;
                if (i + 1 < compiledLines.size() && compiledLines.get(i + 1) instanceof ArgumentLine) {
                    argLine = (ArgumentLine) compiledLines.get(i + 1);
                    i++; // Skip the argument line in the next iteration
                }

                // Execute the picture line with arguments
                String formattedLine = executePictureLine(pictureLine, argLine, argList, argIndex);
                output.append(formattedLine).append("\n");

                // Update argument index based on fields used
                if (argLine != null) {
                    argIndex += argLine.expressions.size();
                }
            } else if (line instanceof ArgumentLine) {
                // Standalone argument line (shouldn't happen in well-formed formats)
                // Skip it as it should be processed with its picture line
                continue;
            }
        }

        return output.toString();
    }

    /**
     * Execute a picture line with its corresponding argument line.
     *
     * @param pictureLine The picture line containing format fields
     * @param argLine The argument line containing expressions (may be null)
     * @param args The list of all arguments
     * @param startIndex The starting index in the argument list
     * @return The formatted line
     */
    private String executePictureLine(PictureLine pictureLine, ArgumentLine argLine, 
                                     List<RuntimeScalar> args, int startIndex) {
        StringBuilder result = new StringBuilder();
        String template = pictureLine.content;
        List<FormatField> fields = pictureLine.fields;

        if (fields.isEmpty()) {
            // No fields, just return the literal text
            return template;
        }

        // Get argument values for this line
        List<RuntimeScalar> lineArgs = new ArrayList<>();
        if (argLine != null && !argLine.expressions.isEmpty()) {
            // For now, use the provided arguments directly
            // In a full implementation, we would evaluate the expressions
            for (int i = 0; i < argLine.expressions.size() && startIndex + i < args.size(); i++) {
                lineArgs.add(args.get(startIndex + i));
            }
        }

        // Process each field in the picture line
        int lastPos = 0;
        int argIdx = 0;

        for (FormatField field : fields) {
            // Add literal text before this field
            if (field.startPosition > lastPos) {
                result.append(template, lastPos, field.startPosition);
            }

            // Get the argument value for this field
            Object fieldValue = null;
            if (argIdx < lineArgs.size()) {
                fieldValue = lineArgs.get(argIdx).toString();
                argIdx++;
            }

            // Format the field value
            String formattedValue = field.formatValue(fieldValue);
            result.append(formattedValue);

            lastPos = field.startPosition + field.width;
        }

        // Add any remaining literal text
        if (lastPos < template.length()) {
            result.append(template.substring(lastPos));
        }

        return result.toString();
    }

    /**
     * Compile the format template into executable format lines.
     * This is a simplified version that parses the template string.
     */
    private void compileFormat() {
        if (formatTemplate == null || formatTemplate.isEmpty()) {
            compiledLines = new ArrayList<>();
            isCompiled = true;
            return;
        }

        // For now, this is a basic implementation
        // In a full implementation, this would use the FormatParser
        compiledLines = new ArrayList<>();
        String[] lines = formatTemplate.split("\n");

        for (String line : lines) {
            if (line.trim().startsWith("#")) {
                // Comment line
                String comment = line.trim().substring(1).trim();
                compiledLines.add(new CommentLine(line, comment, 0));
            } else if (containsFormatFields(line)) {
                // Picture line - parse format fields
                List<FormatField> fields = parseFormatFields(line);
                String literalText = extractLiteralText(line);
                compiledLines.add(new PictureLine(line, fields, literalText, 0));
            } else if (!line.trim().isEmpty()) {
                // Argument line - create simple expressions
                List<Node> expressions = new ArrayList<>();
                expressions.add(new StringNode(line.trim(), 0));
                compiledLines.add(new ArgumentLine(line, expressions, 0));
            }
        }

        isCompiled = true;
    }

    /**
     * Check if a line contains format field definitions.
     */
    private boolean containsFormatFields(String line) {
        return line.matches(".*[@^][<>|#*]+.*");
    }

    /**
     * Parse format fields from a picture line (simplified version).
     */
    private List<FormatField> parseFormatFields(String line) {
        List<FormatField> fields = new ArrayList<>();
        // This is a simplified implementation
        // In practice, this would use the full FormatParser logic
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '@' || c == '^') {
                boolean isSpecial = (c == '^');
                int start = i + 1;
                int width = 0;
                
                // Count field characters
                while (start + width < line.length()) {
                    char fieldChar = line.charAt(start + width);
                    if (fieldChar == '<' || fieldChar == '>' || fieldChar == '|' || 
                        fieldChar == '#' || fieldChar == '*') {
                        width++;
                    } else {
                        break;
                    }
                }
                
                if (width > 0) {
                    String fieldSpec = line.substring(start, start + width);
                    FormatField field = createFormatField(fieldSpec, i, isSpecial);
                    if (field != null) {
                        fields.add(field);
                    }
                    i = start + width - 1; // Skip processed characters
                }
            }
        }
        
        return fields;
    }

    /**
     * Create a FormatField based on field specification (simplified version).
     */
    private FormatField createFormatField(String fieldSpec, int startPos, boolean isSpecialField) {
        int width = fieldSpec.length();
        
        // Multiline fields
        if (fieldSpec.equals("*")) {
            MultilineFormatField.MultilineType type = isSpecialField ? 
                MultilineFormatField.MultilineType.FILL_MODE : 
                MultilineFormatField.MultilineType.CONSUME_ALL;
            return new MultilineFormatField(width, startPos, isSpecialField, type);
        }
        
        // Text fields with justification
        if (fieldSpec.matches("<+")) {
            return new TextFormatField(width, startPos, isSpecialField, TextFormatField.Justification.LEFT);
        } else if (fieldSpec.matches(">+")) {
            return new TextFormatField(width, startPos, isSpecialField, TextFormatField.Justification.RIGHT);
        } else if (fieldSpec.matches("\\|+")) {
            return new TextFormatField(width, startPos, isSpecialField, TextFormatField.Justification.CENTER);
        }
        
        // Numeric fields
        if (fieldSpec.matches("#+")) {
            return new NumericFormatField(width, startPos, isSpecialField, width, 0);
        }
        
        // Default to left-justified text field
        return new TextFormatField(width, startPos, isSpecialField, TextFormatField.Justification.LEFT);
    }

    /**
     * Extract literal text from a picture line, replacing format fields with placeholders.
     */
    private String extractLiteralText(String line) {
        return line.replaceAll("[@^][<>|#*]+", "{}");
    }
}
