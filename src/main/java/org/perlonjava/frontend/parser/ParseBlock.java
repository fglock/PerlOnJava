package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.frontend.astnode.AbstractNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.LabelNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.perlmodule.BHooksEndOfScope;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.ParsePrimary.isIsQuoteLikeOperator;
import static org.perlonjava.frontend.parser.TokenUtils.peek;

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
        return parseBlock(parser, true).block;
    }

    /**
     * Parses a block with optional delayed scope exit.
     *
     * <p>When exitScope=false, the caller is responsible for calling
     * exitScope(scopeIndex) later. This is needed for class blocks where
     * methods must be registered while the scope is still active to capture
     * class-level lexical variables.
     *
     * @param parser    The parser instance
     * @param exitScope Whether to exit the scope before returning
     * @return BlockWithScope containing the block and scope index
     * @see StatementParser#parseOptionalPackageBlock for usage with class blocks
     */
    /**
     * Reports whether the statement just parsed makes the next statement lose its
     * first token's contribution to Perl's {@code copline}.
     *
     * <p>A brace-terminated compound statement needs one lookahead token past its
     * closing brace before Perl's LALR parser can reduce it; that lookahead is the
     * next statement's first token, and creating the compound statement's COP
     * clears {@code copline}, discarding whatever the lookahead armed.  Constructs
     * that produce no COP -- a named {@code sub} declaration, a phaser block, a
     * bare {@code package NAME;} -- never clear {@code copline}, so they leave the
     * following statement alone.
     */
    private static boolean leavesLookaheadSwallowed(Parser parser, Node statement,
                                                    int statementStartIndex) {
        if (statement == null) {
            return false;
        }
        if (statement instanceof AbstractNode node
                && (node.getBooleanAnnotation("compileTimeOnly")
                || node.getBooleanAnnotation("noReturnValue"))) {
            return false;
        }
        return StatementCopline.endsWithClosingBrace(
                parser.tokens, statementStartIndex, parser.tokenIndex);
    }

    public static BlockWithScope parseBlock(Parser parser, boolean exitScope) {
        // Perl's "take reference" mode (`\&name`, `defined &name`, `undef &name`)
        // only suppresses the call for the symbol that immediately follows the
        // operator.  A brace-delimited block is an independent evaluation
        // context, so `&name` inside `defined eval { &name }` must still be a
        // call.  Without this reset the flag leaked into the block and turned
        // the call into a code reference, which made `eval { &missing }`
        // succeed instead of dying (Symbol::Util t/70export_glob.t).
        boolean savedParsingTakeReference = parser.parsingTakeReference;
        parser.parsingTakeReference = false;
        try {
            return parseBlockBody(parser, exitScope);
        } finally {
            parser.parsingTakeReference = savedParsingTakeReference;
        }
    }

    private static BlockWithScope parseBlockBody(Parser parser, boolean exitScope) {
        // Store the starting position of the block for backtracking
        int currentIndex = parser.tokenIndex;

        // B::Hooks::EndOfScope callbacks are compile-time lexical-scope
        // callbacks.  Track the parser scope independently of runtime local
        // levels so pragmas such as namespace::clean run before a following
        // BEGIN block in the enclosing scope.
        BHooksEndOfScope.beginCompileScope();

        // Create new scope for variables declared in this block
        int scopeIndex = parser.ctx.symbolTable.enterScope();

        // Container for all statements in the block
        List<Node> statements = new ArrayList<>();
        List<String> blockLabels = new ArrayList<>(); // track labels

        // True when the statement about to be parsed had its first token consumed
        // as the lookahead that let the previous brace-terminated statement reduce.
        // The first statement of a block is never affected: the opening brace
        // resets copline.
        boolean swallowedLookahead = false;

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

            // Handle empty statements (lone semicolons). Perl parses these as
            // bare_statement_null, whose action resets copline to NOLINE without
            // creating a COP, so a block followed by ";" does not shift the line of
            // the statement after it.
            if (token.text.equals(";")) {
                TokenUtils.consume(parser);
                swallowedLookahead = false;
                token = peek(parser);
                continue;
            }

            // Parse the actual statement, passing any label found.
            // Perl attaches one COP (source line) per statement and caller/warn/die
            // all report it, so remember where the statement begins: most AST nodes
            // carry the token index they *finished* parsing at, which is the closing
            // line of a multi-line expression rather than the statement's own line.
            int statementStartIndex = parser.tokenIndex;
            Node statement = StatementResolver.parseStatement(parser, label);

            // parseStatement should never return null, but if it does, it's a parser bug
            // that should be fixed at the source. For now, add defensive check.
            if (statement != null) {
                if (statement instanceof AbstractNode statementNode
                        && statementNode.getAnnotation("statementStartIndex") == null) {
                    // Perl's copline bookkeeping decides which token in the statement
                    // supplies the line; see StatementCopline. swallowedLookahead is
                    // set when the previous statement was a COP-producing statement
                    // that ended at a closing brace, because reducing it consumed
                    // this statement's first token as its lookahead.
                    int coplineIndex = StatementCopline.coplineTokenIndex(
                            parser.tokens, statementStartIndex, parser.tokenIndex,
                            swallowedLookahead);
                    statementNode.setAnnotation("statementStartIndex", coplineIndex);
                }
                statements.add(statement);
            } else {
                // This should never happen - log and skip
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("WARNING: parseStatement returned null at token: " + token.text);
            }

            swallowedLookahead = leavesLookaheadSwallowed(parser, statement, statementStartIndex);

            token = peek(parser);
        }

        // Handle empty blocks by adding an empty list node
        if (statements.isEmpty()) {
            statements.add(new ListNode(parser.tokenIndex));
        }

        Integer postBlockStrictOptions = null;
        String postBlockWarningBits = null;

        // Run compile-time end-of-scope callbacks while this block is still
        // the innermost parser scope.  This must happen before returning to
        // the enclosing block, but after all statements in this block have
        // been parsed.
        BHooksEndOfScope.endCompileScope();

        // Exit the current scope before returning (unless delayed)
        if (exitScope) {
            parser.ctx.symbolTable.exitScope(scopeIndex);
            postBlockStrictOptions = parser.ctx.symbolTable.getStrictOptions();
            postBlockWarningBits = parser.ctx.symbolTable.getWarningBitsString();
        }

        // Create and return the block node with all parsed statements
        BlockNode blockNode = new BlockNode(statements, currentIndex, parser);
        blockNode.labels = blockLabels; // Set the collected labels in the BlockNode
        if (postBlockStrictOptions != null) {
            blockNode.setAnnotation("postBlockStrictOptions", postBlockStrictOptions);
            blockNode.setAnnotation("postBlockWarningBits", postBlockWarningBits);
        }
        return new BlockWithScope(blockNode, scopeIndex);
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

    /**
         * Result of parseBlock when scope exit is delayed.
         */
        public record BlockWithScope(BlockNode block, int scopeIndex) {
    }
}
