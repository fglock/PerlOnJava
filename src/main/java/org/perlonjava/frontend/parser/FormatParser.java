package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Perl format declarations.
 * Handles parsing of format statements following the pattern:
 * format NAME =
 * template lines...
 * .
 * <p>
 * Similar to heredoc parsing, this uses deferred processing to collect
 * format template content after the declaration.
 */
public class FormatParser {

    // Pattern to match format field definitions
    // `*` is a complete field.  In `>^*<`, the trailing `<` is literal
    // picture text, not part of a combined `*<` field specification.
    private static final Pattern FIELD_PATTERN = Pattern.compile("[@^](\\*|[<>|]+|[0#]+(?:\\.[0#]*)?)");

    /**
     * Parse a format declaration statement.
     * This parses the format template content immediately, unlike heredocs.
     *
     * @param parser     The parser instance
     * @param formatName The name of the format (or null for default STDOUT)
     * @return FormatNode representing the format declaration with template content
     */
    public static FormatNode parseFormatDeclaration(Parser parser, String formatName) {
        int tokenIndex = parser.tokenIndex;

        // Default format name to STDOUT if not specified
        if (formatName == null || formatName.isEmpty()) {
            formatName = "STDOUT";
        }

        // Normalize format name to fully qualified name for consistent storage
        // This ensures EmitFormat and typeglob access use the same key
        formatName = NameNormalizer.normalizeVariableName(formatName, parser.ctx.symbolTable.getCurrentPackage());

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Parsing format declaration: " + formatName);

        // Parse format template content immediately
        List<FormatLine> templateLines = parseFormatTemplateContentImmediate(parser);

        // Create a format node with the parsed template content
        FormatNode formatNode = new FormatNode(formatName, templateLines, tokenIndex);

        // Formats are declarations, not statements delayed until an enclosing
        // subroutine is called.  A later write() must find this slot even when
        // the containing sub has only been compiled (as with a lexical sub in
        // the format's argument line).
        RuntimeFormat format = new RuntimeFormat(formatName);
        format.setCompiledLines(templateLines);
        GlobalVariable.setGlobalFormatRef(formatName, format);

        return formatNode;
    }

    /**
     * Parse format template content immediately (not deferred like heredocs).
     * Collects template lines until the terminator '.' is found.
     *
     * @param parser The parser instance
     * @return List of FormatLine objects representing the template
     */
    private static List<FormatLine> parseFormatTemplateContentImmediate(Parser parser) {
        List<FormatLine> templateLines = new ArrayList<>();
        List<LexerToken> tokens = parser.tokens;
        StringBuilder currentLine = new StringBuilder();
        boolean foundTerminator = false;
        int lineIndex = parser.tokenIndex;
        int argumentBlockDepth = 0;

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("FormatParser.parseFormatTemplateContentImmediate: Starting at tokenIndex=" + parser.tokenIndex);

        // Process tokens until we find the terminator '.'
        while (parser.tokenIndex < tokens.size()) {
            LexerToken token = tokens.get(parser.tokenIndex);
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("  Processing token: " + token.text + " type: " + token.type);

            if (token.type == LexerTokenType.EOF) {
                break;
            }

            if (token.type == LexerTokenType.NEWLINE) {
                // End of current line
                String line = currentLine.toString();
                if (argumentBlockDepth > 0) {
                    currentLine.append('\n');
                    parser.tokenIndex++;
                    continue;
                }
                // The newline terminating `format NAME =` is not the first
                // line of the format picture.  Treating it as one creates a
                // spurious blank output line before every format and makes
                // `$-` account for one physical line too many.
                if (templateLines.isEmpty() && line.isEmpty()) {
                    currentLine.setLength(0);
                    lineIndex = parser.tokenIndex + 1;
                    parser.tokenIndex++;
                    continue;
                }
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("  Completed line: '" + line + "'");

                // Check if this line is the terminator
                if (line.trim().equals(".")) {
                    if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Found format terminator '.' at token index " + lineIndex);
                    foundTerminator = true;
                    parser.tokenIndex++; // consume the newline
                    break;
                }

                // Parse the line and add to template
                FormatLine formatLine = parseFormatLine(parser, line, lineIndex);
                templateLines.add(formatLine);
                currentLine.setLength(0);

                lineIndex = parser.tokenIndex + 1;
                parser.tokenIndex++; // consume the newline
            } else {
                // Append token to current line
                currentLine.append(token.text);
                if (token.text.equals("{")) {
                    argumentBlockDepth++;
                } else if (token.text.equals("}") && argumentBlockDepth > 0) {
                    argumentBlockDepth--;
                }
                parser.tokenIndex++;
            }
        }

        // Handle the last line if we didn't end with a newline
        if (currentLine.length() > 0) {
            String line = currentLine.toString();
            if (line.trim().equals(".")) {
                foundTerminator = true;
            } else {
                FormatLine formatLine = parseFormatLine(parser, line, lineIndex);
                templateLines.add(formatLine);
            }
        }

        if (!foundTerminator) {
            throw new PerlCompilerException(parser.tokenIndex,
                    "Format not terminated", parser.ctx.errorUtil);
        }

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("FormatParser.parseFormatTemplateContentImmediate: Parsed " +
                templateLines.size() + " template lines");

        return templateLines;
    }

    /**
     * Process format template content after the format declaration.
     * This is called similar to heredoc processing to collect template lines
     * until the terminator '.' is found.
     *
     * @param parser The parser instance
     */
    public static void parseFormatTemplateContent(Parser parser) {
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("FORMAT_PROCESSING_START");
        List<FormatNode> formatNodes = parser.getFormatNodes();
        List<LexerToken> tokens = parser.tokens;
        int currentIndex = parser.tokenIndex;

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("FormatParser.parseFormatTemplateContent: Starting at tokenIndex=" +
                currentIndex + ", format count=" + formatNodes.size());

        // Process all pending format nodes
        List<FormatNode> deferredFormats = new ArrayList<>();

        while (!formatNodes.isEmpty()) {
            FormatNode formatNode = formatNodes.removeFirst();

            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Processing format: " + formatNode.formatName);

            // Check if we have enough tokens
            if (currentIndex + 1 >= tokens.size()) {
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Deferring format " + formatNode.formatName + " - not enough tokens");
                deferredFormats.add(formatNode);
                continue;
            }

            // Collect template lines until we find the terminator '.'
            List<FormatLine> templateLines = new ArrayList<>();
            int lineIndex = currentIndex + 1; // Start after the newline
            StringBuilder currentLine = new StringBuilder();
            boolean foundTerminator = false;

            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("  Looking for format content starting at token index: " + lineIndex);

            while (lineIndex < tokens.size()) {
                LexerToken token = tokens.get(lineIndex);

                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("  Token[" + lineIndex + "]: type=" + token.type +
                        ", text='" + token.text.replace("\n", "\\n") + "'");

                if (token.type == LexerTokenType.NEWLINE || token.type == LexerTokenType.EOF) {
                    // End of current line
                    String line = currentLine.toString();
                    if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("  Completed line: '" + line + "'");

                    // Check if this line is the terminator
                    if (line.trim().equals(".")) {
                        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Found format terminator '.' at token index " + lineIndex);
                        foundTerminator = true;
                        break;
                    }

                    // Parse the line and add to template
                    FormatLine formatLine = parseFormatLine(parser, line, lineIndex);
                    templateLines.add(formatLine);
                    currentLine.setLength(0);

                    lineIndex++;
                } else {
                    // Append token to current line
                    currentLine.append(token.text);
                    lineIndex++;
                }
            }

            // Check if we found the terminator
            if (!foundTerminator) {
                if (lineIndex >= tokens.size() ||
                        (lineIndex < tokens.size() && tokens.get(lineIndex).type == LexerTokenType.EOF)) {
                    throw new PerlCompilerException(currentIndex,
                            "Can't find format terminator \".\" for format " + formatNode.formatName +
                                    " anywhere before EOF", parser.ctx.errorUtil);
                }

                // Defer for parent context to handle
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Format " + formatNode.formatName + " terminator not found - deferring");
                deferredFormats.add(formatNode);
                continue;
            }

            // Update the format node with parsed template lines
            // Since FormatNode.templateLines is final, we need to create a new node
            FormatNode completedFormat = new FormatNode(formatNode.formatName, templateLines, formatNode.tokenIndex);

            // Replace the original node in any collections that reference it
            // For now, we'll update the parser's completed formats list
            parser.getCompletedFormatNodes().add(completedFormat);

            currentIndex = lineIndex;
        }

        // Re-add deferred formats
        formatNodes.addAll(deferredFormats);
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("FormatParser.parseFormatTemplateContent: Deferred " +
                deferredFormats.size() + " formats back to queue");

        parser.tokenIndex = currentIndex;
    }

    /**
     * Parse a single format template line.
     * Determines the type of line (comment, picture, or argument) and creates appropriate FormatLine.
     *
     * @param parser     The parser instance
     * @param line       The line content
     * @param tokenIndex The token index for this line
     * @return FormatLine representing the parsed line
     */
    private static FormatLine parseFormatLine(Parser parser, String line, int tokenIndex) {
        // Comment lines start with #
        if (line.trim().startsWith("#")) {
            String comment = line.trim().substring(1).trim();
            return new CommentLine(line, comment, tokenIndex);
        }

        // Check if this is a picture line (contains format fields)
        if (containsFormatFields(line)) {
            List<FormatField> fields = parseFormatFields(line);
            String literalText = extractLiteralText(line);
            return new PictureLine(line, fields, literalText, tokenIndex);
        }

        // Otherwise, treat as argument line
        parser.formatArgumentLexicalSubName = null;
        List<Node> expressions = parseArgumentExpressions(parser, line, tokenIndex);
        ArgumentLine argumentLine = new ArgumentLine(line, expressions, tokenIndex);
        annotateUnavailableLexicalSub(parser, argumentLine);
        return argumentLine;
    }

    /**
     * A format declaration outlives the lexical pad in which its argument
     * lines are parsed.  Keep the lexical-sub fact on the format AST so the
     * runtime can reproduce Perl's warning and call failure when write()
     * eventually materializes that line.  This deliberately keys off the
     * lexical symbol-table entry, never the compiler-generated storage name.
     */
    private static void annotateUnavailableLexicalSub(Parser parser, ArgumentLine argumentLine) {
        String resolvedName = parser.formatArgumentLexicalSubName;
        if (resolvedName != null) {
            annotateUnavailableLexicalSub(parser, argumentLine, resolvedName);
            return;
        }
        Lexer lexer = new Lexer(argumentLine.content);
        List<LexerToken> tokens = lexer.tokenize();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!"&".equals(tokens.get(i).text)
                    || tokens.get(i + 1).type != LexerTokenType.IDENTIFIER) {
                continue;
            }
            String name = tokens.get(i + 1).text;
            if (parser.hasVisibleLexicalSubroutine(name)) {
                annotateUnavailableLexicalSub(parser, argumentLine, name);
                return;
            }
            if (hasPriorLexicalSubDeclaration(parser, name, argumentLine.tokenIndex)) {
                annotateUnavailableLexicalSub(parser, argumentLine, name);
                return;
            }
            var entry = parser.ctx.symbolTable.getSymbolEntry("&" + name);
            if (entry == null || !(entry.ast() instanceof OperatorNode lexicalSub)
                    || !("my".equals(entry.decl()) || "state".equals(entry.decl()))) {
                continue;
            }
            annotateUnavailableLexicalSub(parser, argumentLine, name);
            return;
        }
    }

    /**
     * Format bodies are deferred token regions: by the time an argument line
     * is parsed, the ordinary lexical symbol-table scope may already have
     * unwound.  Consult the original source region for a preceding lexical
     * sub declaration.  The query is by Perl declaration syntax and name, not
     * by an implementation-generated hidden variable name.
     */
    private static boolean hasPriorLexicalSubDeclaration(Parser parser, String name, int beforeIndex) {
        // A deferred format line has a token index in its own re-tokenized
        // region, while parser.tokens belongs to the owning compilation unit.
        // Inspect that complete unit; the declaration's lexical nature is
        // still determined entirely by source syntax, not a generated name.
        int limit = parser.tokens.size();
        for (int i = 0; i + 2 < limit; i++) {
            String declaration = parser.tokens.get(i).text;
            if (!(("my".equals(declaration) || "state".equals(declaration))
                    && "sub".equals(parser.tokens.get(i + 1).text)
                    && name.equals(parser.tokens.get(i + 2).text))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void annotateUnavailableLexicalSub(Parser parser, ArgumentLine argumentLine, String name) {
        argumentLine.setAnnotation("unavailableLexicalSubWarning",
                "Subroutine \"&" + name + "\" is not available");
        argumentLine.setAnnotation("unavailableLexicalSubError",
                "Undefined subroutine &" + name + " called"
                        + parser.ctx.errorUtil.warningLocation(argumentLine.tokenIndex) + ".\n");
    }

    /**
     * Check if a line contains format field definitions.
     *
     * @param line The line to check
     * @return true if the line contains format fields
     */
    private static boolean containsFormatFields(String line) {
        return line.trim().equals("@") || FIELD_PATTERN.matcher(line).find()
                || line.matches(".*[@^](?=\\s|$).*");
    }

    /**
     * Parse format fields from a picture line.
     *
     * @param line The picture line
     * @return List of FormatField objects
     */
    private static List<FormatField> parseFormatFields(String line) {
        List<FormatField> fields = new ArrayList<>();
        if (line.trim().equals("@")) {
            fields.add(new TextFormatField(1, line.indexOf('@'), false,
                    TextFormatField.Justification.LEFT));
            return fields;
        }
        for (int startPos = 0; startPos < line.length(); startPos++) {
            char sigil = line.charAt(startPos);
            if (sigil != '@' && sigil != '^') continue;
            Matcher matcher = FIELD_PATTERN.matcher(line).region(startPos, line.length());
            if (!matcher.lookingAt()) {
                fields.add(new TextFormatField(1, startPos, sigil == '^',
                        TextFormatField.Justification.LEFT));
                continue;
            }
            String fieldSpec = matcher.group(1);
            int end = matcher.end();
            // A blank inside a numeric-looking picture ends the picture at
            // its sigil: @ 0# and @0 # are @ plus literal text in Perl.
            if (fieldSpec.matches("[0#]+") && end < line.length()
                    && Character.isWhitespace(line.charAt(end))
                    && end + 1 < line.length()
                    && (line.charAt(end + 1) == '0' || line.charAt(end + 1) == '#')) {
                fields.add(new TextFormatField(1, startPos, sigil == '^',
                        TextFormatField.Justification.LEFT));
                continue;
            }
            FormatField field = createFormatField(fieldSpec, startPos, sigil == '^');
            if (field != null) fields.add(field);
            startPos = end - 1;
        }

        return fields;
    }

    /**
     * Create a FormatField based on field specification.
     *
     * @param fieldSpec      The field specification (e.g., "<<<", "###", "*")
     * @param startPos       The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular (@)
     * @return FormatField instance or null if invalid
     */
    private static FormatField createFormatField(String fieldSpec, int startPos, boolean isSpecialField) {
        // The sigil is part of a Perl picture field's width: @<< holds three
        // characters, not two.  Keep this invariant in the AST so rendering
        // and template advancement use the same physical picture span.
        int width = fieldSpec.length() + 1;

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
        if (fieldSpec.matches("[0#]+")) {
            // Simple integer field like @###
            boolean zeroPad = fieldSpec.indexOf('0') >= 0;
            return new NumericFormatField(width, startPos, isSpecialField,
                    zeroPad ? width : fieldSpec.length(), 0, zeroPad);
        } else if (fieldSpec.matches("[0#]+\\.[0#]*")) {
            // Decimal field like @##.##
            String[] parts = fieldSpec.split("\\.", -1);
            int integerDigits = parts[0].length();
            int decimalPlaces = parts[1].length();
            boolean zeroPad = parts[0].indexOf('0') >= 0;
            return new NumericFormatField(width, startPos, isSpecialField,
                    zeroPad ? integerDigits + 1 : integerDigits, decimalPlaces, zeroPad, true);
        }

        // Default to left-justified text field for unknown patterns
        return new TextFormatField(width, startPos, isSpecialField, TextFormatField.Justification.LEFT);
    }

    /**
     * Extract literal text from a picture line, replacing format fields with placeholders.
     *
     * @param line The picture line
     * @return The literal text with field placeholders
     */
    private static String extractLiteralText(String line) {
        // Replace format fields with placeholders for now
        return FIELD_PATTERN.matcher(line).replaceAll("{}");
    }

    /**
     * Parse argument expressions from an argument line.
     * Uses proper Perl expression parsing to handle variables, function calls, etc.
     *
     * @param line       The argument line
     * @param tokenIndex The token index
     * @return List of expression nodes
     */
    private static List<Node> parseArgumentExpressions(Parser parser, String line, int tokenIndex) {
        List<Node> expressions = new ArrayList<>();

        if (line.trim().isEmpty()) {
            return expressions;
        }

        try {
            // Create a lexer for the argument line
            Lexer lexer = new Lexer(line);
            List<LexerToken> tokens = lexer.tokenize();

            // Create a parser for the tokens
            // Use the parser's context for parsing argument expressions
            Parser argParser = new Parser(parser.ctx, tokens);
            argParser.parsingFormatArgumentLine = true;

            // Parse comma-separated expressions
            while (argParser.tokenIndex < tokens.size()) {
                LexerToken token = tokens.get(argParser.tokenIndex);
                if (token.type == LexerTokenType.EOF) {
                    break;
                }

                // Parse the next expression
                Node expr = argParser.parseExpression(0);
                if (expr != null) {
                    expressions.add(expr);
                }

                // Check for comma separator
                if (argParser.tokenIndex < tokens.size()) {
                    LexerToken nextToken = tokens.get(argParser.tokenIndex);
                    if (nextToken.text.equals(",")) {
                        argParser.tokenIndex++; // consume comma
                    } else if (nextToken.type != LexerTokenType.EOF) {
                        // If not comma and not EOF, we might have a syntax error
                        // For now, just break to avoid infinite loop
                        break;
                    }
                }
            }
            parser.formatArgumentLexicalSubName = argParser.formatArgumentLexicalSubName;
        } catch (Exception e) {
            // If parsing fails, fall back to treating the whole line as a string literal
            // This ensures format parsing doesn't fail completely
            expressions.add(new StringNode(line.trim(), tokenIndex));
        }

        return expressions;
    }
}
