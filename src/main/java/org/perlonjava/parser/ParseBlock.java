package org.perlonjava.parser;

import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.LabelNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParsePrimary.isIsQuoteLikeOperator;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * ParseBlock handles the parsing of code blocks in the Perl language.
 * A block represents a sequence of statements enclosed in curly braces.
 */
public class ParseBlock {
    /**
     * Parses a block of code and generates an Abstract Syntax Tree (AST) representation.
     * A block consists of zero or more statements and may include labeled statements.
     * <p>
     * The method handles:
     * - Statement parsing
     * - Label declarations (e.g., "label:")
     * - Empty blocks
     * - Scope management
     *
     * @param parser The parser instance containing the current parsing state
     * @return BlockNode representing the parsed block in the AST
     */
    public static BlockNode parseBlock(Parser parser) {
        // Store the starting position of the block for backtracking
        int currentIndex = parser.tokenIndex;

        // Create new scope for variables declared in this block
        int scopeIndex = parser.ctx.symbolTable.enterScope();

        // Container for all statements in the block
        List<Node> statements = new ArrayList<>();
        List<String> blockLabels = new ArrayList<>(); // track labels

        // Get the current token without consuming it
        LexerToken token = peek(parser);

        // Continue parsing until we reach end of file or closing brace
        while (token.type != LexerTokenType.EOF
                && !(token.type == LexerTokenType.OPERATOR && token.text.equals("}"))) {

            // Label parsing logic
            String label = null;
            if (token.type == LexerTokenType.IDENTIFIER) {
                label = parseLabel(parser, statements, blockLabels);

                token = peek(parser);
                String nextLabel = label;
                while (nextLabel != null && token.type == LexerTokenType.IDENTIFIER) {
                    nextLabel = parseLabel(parser, statements, blockLabels);
                    token = peek(parser);
                    if (nextLabel != null) {
                        label = nextLabel;  // Keep track of the last valid label
                    }
                }

                if (label != null && token.type == LexerTokenType.OPERATOR && token.text.equals("}")) {
                    continue;
                }
            }

            // Handle empty statements (lone semicolons)
            if (token.text.equals(";")) {
                TokenUtils.consume(parser);
                token = peek(parser);
                continue;
            }

            // Parse the actual statement, passing any label found
            statements.add(StatementResolver.parseStatement(parser, label));

            token = peek(parser);
        }

        // Handle empty blocks by adding an empty list node
        if (statements.isEmpty()) {
            statements.add(new ListNode(parser.tokenIndex));
        }

        // Exit the current scope before returning
        parser.ctx.symbolTable.exitScope(scopeIndex);

        // Create and return the block node with all parsed statements
        BlockNode blockNode = new BlockNode(statements, currentIndex);
        blockNode.labels = blockLabels; // Set the collected labels in the BlockNode
        return blockNode;
    }

    private static String parseLabel(Parser parser, List<Node> statements, List<String> blockLabels) {
        int currentIndexLabel = parser.tokenIndex;
        String id = TokenUtils.peek(parser).text;
        if (isIsQuoteLikeOperator(id)) {
            // `m:` not a label, but a quote-like operator
            return null;
        }
        
        // Don't treat 'sub' as a label - it's a keyword for subroutine definitions
        if (id.equals("sub")) {
            return null;
        }
        
        TokenUtils.consume(parser);
        if (peek(parser).text.equals(":")) {
            statements.add(new LabelNode(id, currentIndexLabel));
            blockLabels.add(id); // Add each found label to our list
            TokenUtils.consume(parser); // Consume the colon
            return id;
        }
        parser.tokenIndex = currentIndexLabel;
        return null;
    }
}
