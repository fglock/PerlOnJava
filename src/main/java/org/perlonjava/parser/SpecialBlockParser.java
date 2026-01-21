package org.perlonjava.parser;

import org.perlonjava.CompilerOptions;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;
import org.perlonjava.symbols.ScopedSymbolTable;
import org.perlonjava.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.GLOBAL_PHASE;
import static org.perlonjava.runtime.SpecialBlock.*;

/**
 * The SpecialBlockParser class is responsible for parsing and executing special blocks
 * in Perl scripts, such as BEGIN, END, INIT, CHECK, and UNITCHECK blocks.
 */
public class SpecialBlockParser {

    private static ScopedSymbolTable symbolTable = new ScopedSymbolTable();

    public static ScopedSymbolTable getCurrentScope() {
        return symbolTable;
    }

    public static void setCurrentScope(ScopedSymbolTable st) {
        symbolTable = st;
    }

    /**
     * Parses a special block.
     *
     * @param parser The parser instance to use for parsing.
     * @return A Node representing "undef".
     */
    static Node parseSpecialBlock(Parser parser) {
        // Consume the block name token
        String blockName = TokenUtils.consume(parser).text;

        // ADJUST blocks are only allowed inside class blocks
        if ("ADJUST".equals(blockName) && !parser.isInClassBlock) {
            throw new PerlCompilerException(parser.tokenIndex,
                    "ADJUST blocks are only allowed inside class blocks", parser.ctx.errorUtil);
        }

        // Consume the opening brace '{'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // ADJUST blocks have implicit $self, so set isInMethod flag
        boolean wasInMethod = parser.isInMethod;
        if ("ADJUST".equals(blockName) && parser.isInClassBlock) {
            parser.isInMethod = true;
        }

        // Parse the block content
        BlockNode block = ParseBlock.parseBlock(parser);

        // Restore the isInMethod flag
        parser.isInMethod = wasInMethod;

        // Consume the closing brace '}'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // ADJUST blocks in class context are not executed at parse time
        // They are compiled as anonymous subs and stored for the constructor
        if ("ADJUST".equals(blockName) && parser.isInClassBlock) {

            // Create an anonymous sub that captures lexical variables
            SubroutineNode adjustSub = new SubroutineNode(
                    null,  // anonymous
                    null,  // no prototype
                    null,  // no attributes
                    block,
                    false,
                    parser.tokenIndex);

            // Store in parser's ADJUST blocks list
            parser.classAdjustBlocks.add(adjustSub);

            // Return the anonymous sub node (won't be executed now)
            return adjustSub;
        }

        // Execute other special blocks normally
        runSpecialBlock(parser, blockName, block);

        // Return an undefined operator node
        return new OperatorNode("undef", null, parser.tokenIndex);
    }

    /**
     * Executes a special block with the given block phase and block AST.
     *
     * @param parser     The parser instance.
     * @param blockPhase The phase of the block (e.g., BEGIN, END).
     * @param block      The block AST to execute.
     * @return A RuntimeList containing the result of the execution.
     */
    static RuntimeList runSpecialBlock(Parser parser, String blockPhase, Node block) {
        return runSpecialBlock(parser, blockPhase, block, RuntimeContextType.VOID);
    }

    static RuntimeList runSpecialBlock(Parser parser, String blockPhase, Node block, int callerContext) {
        int tokenIndex = parser.tokenIndex;

        // Create AST nodes for setting up the capture variables and package declaration
        List<Node> nodes = new ArrayList<>();

        if (block instanceof BlockNode blockNode) {
            // Emit as first operation inside the block: local ${^GLOBAL_PHASE} = "BEGIN"
            String phaseName = blockPhase.equals("BEGIN") || blockPhase.equals("UNITCHECK")
                    ? "START"
                    : blockPhase;
            blockNode.elements.addFirst(
                    new BinaryOperatorNode("=",
                            new OperatorNode("local",
                                    new OperatorNode("$",
                                            new IdentifierNode(GLOBAL_PHASE, tokenIndex),
                                            tokenIndex),
                                    tokenIndex),
                            new StringNode(phaseName, tokenIndex),
                            tokenIndex));
        }

        // Declare capture variables
        //
        // IMPORTANT: At the point where we execute a special block, ParseBlock has already
        // exited the special block's own scope, so the current scope here is the surrounding
        // scope (e.g. file scope). We must include that scope so BEGIN can see/update outer
        // lexicals (e.g. `my $x; BEGIN { $x = 1 }`).
        //
        // We also use runSpecialBlock("BEGIN", <expr>) to evaluate `use/no` argument lists;
        // those expressions likewise need access to the current lexical scope.
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();

        // When a BEGIN block (or a BEGIN-time evaluation such as `use Module LIST`) reads/writes
        // an outer lexical, we need those effects to persist into runtime. Perl achieves this
        // because BEGIN runs in the same lexical pad as the surrounding scope.
        //
        // Our runtime model uses PersistentVariable + a special BEGIN package to carry values
        // from compile-time into runtime.
        //
        // To bridge the gap we rewrite variable references *inside the BEGIN-time AST* so that
        // captured `my/state` variables are accessed via the BEGIN package globals.
        // This keeps runtime semantics unchanged (runtime still uses lexicals), while BEGIN-time
        // evaluation can observe and modify the persistent storage.
        Map<String, String> beginLexicalRewrite = new HashMap<>();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                // Skip lexical subs (entries starting with &) - they are stored as hidden variables
                // and don't need to be captured in BEGIN blocks
                if (entry.name().startsWith("&")) {
                    continue;
                }
                
                if (entry.decl().equals("our")) {
                    // "our" variable lives in a Perl package
                    // Emit: package PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(entry.perlPackage(), tokenIndex), tokenIndex));
                } else {
                    // "my" or "state" variable live in a special BEGIN package
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }

                    // Record rewrite mapping for BEGIN-time execution: $x -> $PerlOnJava::_BEGIN_<id>::x
                    String sigil = entry.name().substring(0, 1);
                    String bare = entry.name().substring(1);
                    beginLexicalRewrite.put(sigil + bare, PersistentVariable.beginPackage(ast.id) + "::" + bare);

                    // Emit: package BEGIN_PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(PersistentVariable.beginPackage(ast.id), tokenIndex), tokenIndex));
                }
                // Emit: our $var
                nodes.add(
                        new OperatorNode(
                                "our",
                                new OperatorNode(
                                        entry.name().substring(0, 1),
                                        new IdentifierNode(entry.name().substring(1), tokenIndex),
                                        tokenIndex),
                                tokenIndex));
            }
        }

        if (blockPhase.equals("BEGIN") && !beginLexicalRewrite.isEmpty()) {
            rewriteBeginCapturedLexicals(block, beginLexicalRewrite);
        }
        // Emit: package PKG
        nodes.add(
                new OperatorNode("package",
                        new IdentifierNode(
                                parser.ctx.symbolTable.getCurrentPackage(), tokenIndex), tokenIndex));

        SubroutineNode anonSub =
                new SubroutineNode(
                        null,
                        null,
                        null,
                        block,
                        false,
                        tokenIndex);

        if (blockPhase.equals("BEGIN")) {
            // BEGIN - execute immediately
            nodes.add(
                    new BinaryOperatorNode(
                            "->",
                            anonSub,
                            new ListNode(tokenIndex),
                            tokenIndex
                    )
            );
        } else {
            // Not BEGIN - return a sub to execute later
            nodes.add(anonSub);
        }

        CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // Special blocks are always run
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        try {
            setCurrentScope(parser.ctx.symbolTable);
            result = PerlLanguageProvider.executePerlAST(
                    new BlockNode(nodes, tokenIndex),
                    parser.tokens,
                    parsedArgs,
                    callerContext);
        } catch (Throwable t) {
            if (parsedArgs.debugEnabled) {
                // Print full JVM stack
                t.printStackTrace();
                System.out.println();
            }

            String message = t.getMessage();
            if (message == null) {
                message = t.getClass().getSimpleName() + " during " + blockPhase;
            }
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            message += blockPhase + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }
        GlobalVariable.getGlobalVariable("main::@").set(""); // Reset error variable

        if (!blockPhase.equals("BEGIN")) {
            RuntimeScalar codeRef = result.getFirst();
            switch (blockPhase) {
                case "END" -> saveEndBlock(codeRef);
                case "INIT" -> saveInitBlock(codeRef);
                case "CHECK" -> saveCheckBlock(codeRef);
                case "UNITCHECK" -> RuntimeArray.push(parser.ctx.unitcheckBlocks, codeRef);
            }
        }

        return result;
    }

    private static void rewriteBeginCapturedLexicals(Node node, Map<String, String> rewrite) {
        if (node == null) {
            return;
        }

        // Rewrite simple variable nodes: $x, @x, %x
        if (node instanceof OperatorNode op
                && op.operand instanceof IdentifierNode ident
                && op.operator != null
                && "$@%".contains(op.operator)
                && ident.name != null
                && !ident.name.contains("::")) {
            String key = op.operator + ident.name;
            String replacement = rewrite.get(key);
            if (replacement != null) {
                op.operand = new IdentifierNode(replacement, ident.tokenIndex);
            }
        }

        // Recurse into child nodes. Keep this conservative; we only need to walk the nodes
        // that commonly appear in BEGIN and use/no argument lists.
        if (node instanceof BlockNode block) {
            for (Node elem : block.elements) {
                rewriteBeginCapturedLexicals(elem, rewrite);
            }
            return;
        }

        if (node instanceof ListNode list) {
            for (Node elem : list.elements) {
                rewriteBeginCapturedLexicals(elem, rewrite);
            }
            return;
        }

        if (node instanceof ArrayLiteralNode arr) {
            for (Node elem : arr.elements) {
                rewriteBeginCapturedLexicals(elem, rewrite);
            }
            return;
        }

        if (node instanceof HashLiteralNode hash) {
            for (Node elem : hash.elements) {
                rewriteBeginCapturedLexicals(elem, rewrite);
            }
            return;
        }

        if (node instanceof BinaryOperatorNode bin) {
            rewriteBeginCapturedLexicals(bin.left, rewrite);
            rewriteBeginCapturedLexicals(bin.right, rewrite);
            return;
        }

        if (node instanceof TernaryOperatorNode tri) {
            rewriteBeginCapturedLexicals(tri.condition, rewrite);
            rewriteBeginCapturedLexicals(tri.trueExpr, rewrite);
            rewriteBeginCapturedLexicals(tri.falseExpr, rewrite);
            return;
        }

        if (node instanceof SubroutineNode sub) {
            rewriteBeginCapturedLexicals(sub.block, rewrite);
        }
    }
}
