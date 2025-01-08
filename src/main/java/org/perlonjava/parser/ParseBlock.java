package org.perlonjava.parser;

import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.LabelNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.List;

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
        parser.ctx.symbolTable.enterScope();

        // Container for all statements in the block
        List<Node> statements = new ArrayList<>();

        // Get the current token without consuming it
        LexerToken token = peek(parser);

        // Continue parsing until we reach end of file or closing brace
        while (token.type != LexerTokenType.EOF
                && !(token.type == LexerTokenType.OPERATOR && token.text.equals("}"))) {

            // Label parsing logic
            String label = null;
            if (token.type == LexerTokenType.IDENTIFIER) {
                // Check for potential label declaration
                int currentIndexLabel = parser.tokenIndex;
                String id = TokenUtils.consume(parser).text;
                if (peek(parser).text.equals(":")) {
                    // TODO handle special cases like `L1: L2:` and `L1: }`
                    label = id;
                    statements.add(new LabelNode(id, currentIndex));  // Create label node
                    TokenUtils.consume(parser);
                    token = peek(parser);
                } else {
                    parser.tokenIndex = currentIndexLabel;  // Backtrack if not a label
                }
            }

            // Handle empty statements (lone semicolons)
            if (token.text.equals(";")) {
                TokenUtils.consume(parser);
                token = peek(parser);
                continue;
            }

            // Parse the actual statement, passing any label found
            statements.add(ParseStatement.parseStatement(parser, label));

            token = peek(parser);
        }

        // Handle empty blocks by adding an empty list node
        if (statements.isEmpty()) {
            statements.add(new ListNode(parser.tokenIndex));
        }

        // Exit the current scope before returning
        parser.ctx.symbolTable.exitScope();

        // Create and return the block node with all parsed statements
        return new BlockNode(statements, currentIndex);
    }
}
