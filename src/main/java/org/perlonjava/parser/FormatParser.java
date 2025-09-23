package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

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
 * 
 * Similar to heredoc parsing, this uses deferred processing to collect
 * format template content after the declaration.
 */
public class FormatParser {
    
    // Pattern to match format field definitions
    private static final Pattern FIELD_PATTERN = Pattern.compile("[@^]([<>|#*]+|\\*|#+\\.?#+?)");
    
    /**
     * Parse a format declaration statement.
     * This parses the format template content immediately, unlike heredocs.
     * 
     * @param parser The parser instance
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
        
        parser.ctx.logDebug("Parsing format declaration: " + formatName);
        
        // Parse format template content immediately
        List<FormatLine> templateLines = parseFormatTemplateContentImmediate(parser);
        
        // Create a format node with the parsed template content
        FormatNode formatNode = new FormatNode(formatName, templateLines, tokenIndex);
        
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
        
        parser.ctx.logDebug("FormatParser.parseFormatTemplateContentImmediate: Starting at tokenIndex=" + parser.tokenIndex);
        
        // Process tokens until we find the terminator '.'
        while (parser.tokenIndex < tokens.size()) {
            LexerToken token = tokens.get(parser.tokenIndex);
            parser.ctx.logDebug("  Processing token: " + token.text + " type: " + token.type);
            
            if (token.type == LexerTokenType.EOF) {
                break;
            }
            
            if (token.type == LexerTokenType.NEWLINE) {
                // End of current line
                String line = currentLine.toString();
                parser.ctx.logDebug("  Completed line: '" + line + "'");
                
                // Check if this line is the terminator
                if (line.trim().equals(".")) {
                    parser.ctx.logDebug("Found format terminator '.' at token index " + lineIndex);
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
            throw new org.perlonjava.runtime.PerlCompilerException(parser.tokenIndex, 
                "Format not terminated", parser.ctx.errorUtil);
        }
        
        parser.ctx.logDebug("FormatParser.parseFormatTemplateContentImmediate: Parsed " + 
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
        parser.ctx.logDebug("FORMAT_PROCESSING_START");
        List<FormatNode> formatNodes = parser.getFormatNodes();
        List<LexerToken> tokens = parser.tokens;
        int currentIndex = parser.tokenIndex;
        
        parser.ctx.logDebug("FormatParser.parseFormatTemplateContent: Starting at tokenIndex=" + 
                           currentIndex + ", format count=" + formatNodes.size());
        
        // Process all pending format nodes
        List<FormatNode> deferredFormats = new ArrayList<>();
        
        while (!formatNodes.isEmpty()) {
            FormatNode formatNode = formatNodes.removeFirst();
            
            parser.ctx.logDebug("Processing format: " + formatNode.formatName);
            
            // Check if we have enough tokens
            if (currentIndex + 1 >= tokens.size()) {
                parser.ctx.logDebug("Deferring format " + formatNode.formatName + " - not enough tokens");
                deferredFormats.add(formatNode);
                continue;
            }
            
            // Collect template lines until we find the terminator '.'
            List<FormatLine> templateLines = new ArrayList<>();
            int lineIndex = currentIndex + 1; // Start after the newline
            StringBuilder currentLine = new StringBuilder();
            boolean foundTerminator = false;
            
            parser.ctx.logDebug("  Looking for format content starting at token index: " + lineIndex);
            
            while (lineIndex < tokens.size()) {
                LexerToken token = tokens.get(lineIndex);
                
                parser.ctx.logDebug("  Token[" + lineIndex + "]: type=" + token.type + 
                                   ", text='" + token.text.replace("\n", "\\n") + "'");
                
                if (token.type == LexerTokenType.NEWLINE || token.type == LexerTokenType.EOF) {
                    // End of current line
                    String line = currentLine.toString();
                    parser.ctx.logDebug("  Completed line: '" + line + "'");
                    
                    // Check if this line is the terminator
                    if (line.trim().equals(".")) {
                        parser.ctx.logDebug("Found format terminator '.' at token index " + lineIndex);
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
                parser.ctx.logDebug("Format " + formatNode.formatName + " terminator not found - deferring");
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
        parser.ctx.logDebug("FormatParser.parseFormatTemplateContent: Deferred " + 
                           deferredFormats.size() + " formats back to queue");
        
        parser.tokenIndex = currentIndex;
    }
    
    /**
     * Parse a single format template line.
     * Determines the type of line (comment, picture, or argument) and creates appropriate FormatLine.
     * 
     * @param parser The parser instance
     * @param line The line content
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
        List<Node> expressions = parseArgumentExpressions(parser, line, tokenIndex);
        return new ArgumentLine(line, expressions, tokenIndex);
    }
    
    /**
     * Check if a line contains format field definitions.
     * 
     * @param line The line to check
     * @return true if the line contains format fields
     */
    private static boolean containsFormatFields(String line) {
        return FIELD_PATTERN.matcher(line).find();
    }
    
    /**
     * Parse format fields from a picture line.
     * 
     * @param line The picture line
     * @return List of FormatField objects
     */
    private static List<FormatField> parseFormatFields(String line) {
        List<FormatField> fields = new ArrayList<>();
        Matcher matcher = FIELD_PATTERN.matcher(line);
        
        while (matcher.find()) {
            int startPos = matcher.start();
            String fieldSpec = matcher.group(1);
            boolean isSpecialField = line.charAt(matcher.start()) == '^';
            
            FormatField field = createFormatField(fieldSpec, startPos, isSpecialField);
            if (field != null) {
                fields.add(field);
            }
        }
        
        return fields;
    }
    
    /**
     * Create a FormatField based on field specification.
     * 
     * @param fieldSpec The field specification (e.g., "<<<", "###", "*")
     * @param startPos The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular (@)
     * @return FormatField instance or null if invalid
     */
    private static FormatField createFormatField(String fieldSpec, int startPos, boolean isSpecialField) {
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
            // Simple integer field like @###
            return new NumericFormatField(width, startPos, isSpecialField, width, 0);
        } else if (fieldSpec.matches("#+\\.#+")) {
            // Decimal field like @##.##
            String[] parts = fieldSpec.split("\\.");
            int integerDigits = parts[0].length();
            int decimalPlaces = parts[1].length();
            return new NumericFormatField(width, startPos, isSpecialField, integerDigits, decimalPlaces);
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
     * @param line The argument line
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
            org.perlonjava.lexer.Lexer lexer = new org.perlonjava.lexer.Lexer(line);
            List<LexerToken> tokens = lexer.tokenize();
            
            // Create a parser for the tokens
            // Use the parser's context for parsing argument expressions
            Parser argParser = new Parser(parser.ctx, tokens);
            
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
        } catch (Exception e) {
            // If parsing fails, fall back to treating the whole line as a string literal
            // This ensures format parsing doesn't fail completely
            expressions.add(new StringNode(line.trim(), tokenIndex));
        }
        
        return expressions;
    }
}
