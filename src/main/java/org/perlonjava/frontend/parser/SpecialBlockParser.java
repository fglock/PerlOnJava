package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.GlobalContext.GLOBAL_PHASE;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.*;

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
        int adjustScopeIndex = -1;
        if ("ADJUST".equals(blockName) && parser.isInClassBlock) {
            parser.isInMethod = true;
            // Register $self in a scope so the parse-time strict vars check
            // can find it inside ADJUST block bodies.
            adjustScopeIndex = parser.ctx.symbolTable.enterScope();
            parser.ctx.symbolTable.addVariable("$self", "my", null);
        }

        // Parse the block content
        BlockNode block = ParseBlock.parseBlock(parser);

        // Restore the isInMethod flag and exit ADJUST scope
        if (adjustScopeIndex >= 0) {
            parser.ctx.symbolTable.exitScope(adjustScopeIndex);
        }
        parser.isInMethod = wasInMethod;

        // Consume the closing brace '}'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Before executing BEGIN blocks, process any pending heredocs.
        // This handles cases like: BEGIN { eval <<'END' } ... \n heredoc content \n END
        // The heredoc content comes after the newline, but BEGIN must execute immediately.
        // We need to fill in the heredoc content before BEGIN tries to use it.
        if ("BEGIN".equals(blockName) && !parser.getHeredocNodes().isEmpty()) {
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("HEREDOC_BEGIN_FIX: Found " + parser.getHeredocNodes().size() + " pending heredocs after BEGIN block");
            int savedIndex = parser.tokenIndex;
            // Find the next NEWLINE token
            int newlineIndex = -1;
            for (int i = savedIndex; i < parser.tokens.size(); i++) {
                if (parser.tokens.get(i).type == LexerTokenType.NEWLINE) {
                    newlineIndex = i;
                    break;
                }
            }
            if (newlineIndex >= 0) {
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("HEREDOC_BEGIN_FIX: Processing at newlineIndex=" + newlineIndex + ", will skip to after heredocs");
                // Temporarily advance to the newline to process heredocs
                parser.tokenIndex = newlineIndex;
                ParseHeredoc.parseHeredocAfterNewline(parser);
                // Save where to skip to when we later encounter this specific newline
                parser.heredocSkipToIndex = parser.tokenIndex;
                parser.heredocNewlineIndex = newlineIndex;
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("HEREDOC_BEGIN_FIX: Set heredocSkipToIndex=" + parser.heredocSkipToIndex + ", heredocNewlineIndex=" + newlineIndex);
                // Restore tokenIndex to continue parsing from after the '}'
                parser.tokenIndex = savedIndex;
            }
        }

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

        // Return an undefined operator node marked as compile-time-only
        // so it doesn't affect the file's return value
        OperatorNode result = new OperatorNode("undef", null, parser.tokenIndex);
        result.setAnnotation("compileTimeOnly", true);
        return result;
    }

    /**
     * Executes a special block with the given block phase and block AST.
     * Uses VOID context by default.
     *
     * @param parser     The parser instance.
     * @param blockPhase The phase of the block (e.g., BEGIN, END).
     * @param block      The block AST to execute.
     * @return A RuntimeList containing the result of the execution.
     */
    static RuntimeList runSpecialBlock(Parser parser, String blockPhase, Node block) {
        return runSpecialBlock(parser, blockPhase, block, RuntimeContextType.VOID);
    }

    /**
     * Executes a special block with the given block phase, block AST, and context.
     *
     * @param parser      The parser instance.
     * @param blockPhase  The phase of the block (e.g., BEGIN, END).
     * @param block       The block AST to execute.
     * @param contextType The context to use for execution (VOID, SCALAR, LIST).
     * @return A RuntimeList containing the result of the execution.
     */
    static RuntimeList runSpecialBlock(Parser parser, String blockPhase, Node block, int contextType) {
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
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                // Skip lexical subs (entries starting with &) - they are stored as hidden variables
                // and don't need to be captured in BEGIN blocks
                if (entry.name().startsWith("&")) {
                    continue;
                }

                String packageName;
                boolean isFromOuterScope = false;
                if (entry.decl().equals("our")) {
                    // "our" variable lives in a Perl package
                    packageName = entry.perlPackage();
                    // Emit: package PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(packageName, tokenIndex), tokenIndex));
                } else {
                    OperatorNode ast = entry.ast();
                    isFromOuterScope = RuntimeCode.evalBeginIds.containsKey(ast);
                    int beginId = RuntimeCode.evalBeginIds.computeIfAbsent(
                            ast,
                            k -> EmitterMethodCreator.classCounter++);
                    packageName = PersistentVariable.beginPackage(beginId);
                    // Emit: package BEGIN_PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(packageName, tokenIndex), tokenIndex));
                }
                // CLEAN FIX: For eval STRING, make special globals aliases to closed variables
                // This allows BEGIN blocks to access outer lexical variables with their runtime values.
                //
                // In perl5: my @arr = qw(a b); eval q{ BEGIN { say @arr } };  # prints: a b
                // The special global BEGIN_PKG::@arr is an ALIAS to the closed @arr variable.
                //
                // Implementation: Set the global variable to reference the same runtime object.
                // Only alias if the variable is from the outer (eval) scope, NOT if it's a newly
                // declared variable in the current compilation unit that just happens to share
                // the same name.
                if (!entry.decl().equals("our") && isFromOuterScope) {
                    RuntimeCode.EvalRuntimeContext evalCtx = RuntimeCode.getEvalRuntimeContext();
                    if (evalCtx != null) {
                        Object runtimeValue = evalCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            // Create alias: set special global to reference the runtime object
                            // IMPORTANT: Global variable keys do NOT include the sigil
                            // entry.name() is "@arr" but the key should be "packageName::arr"
                            String varNameWithoutSigil = entry.name().substring(1);  // Remove the sigil
                            String fullName = packageName + "::" + varNameWithoutSigil;

                            // Put in the appropriate global map based on variable type
                            if (runtimeValue instanceof RuntimeArray) {
                                GlobalVariable.getGlobalArraysMap().put(fullName, (RuntimeArray) runtimeValue);
                                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("BEGIN block: Aliased array " + fullName);
                            } else if (runtimeValue instanceof RuntimeHash) {
                                GlobalVariable.getGlobalHashesMap().put(fullName, (RuntimeHash) runtimeValue);
                                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("BEGIN block: Aliased hash " + fullName);
                            } else if (runtimeValue instanceof RuntimeScalar) {
                                GlobalVariable.getGlobalVariablesMap().put(fullName, (RuntimeScalar) runtimeValue);
                                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("BEGIN block: Aliased scalar " + fullName);
                            }
                        }
                    }
                }

                // Emit: our $var
                // When we've aliased the variable above, the "our" declaration will fetch the
                // existing global (our alias) instead of creating a new empty one.
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
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        try {
            setCurrentScope(parser.ctx.symbolTable);
            // Mark wrapper infrastructure nodes to skip DEBUG opcodes and source location mapping.
            // Skip all nodes EXCEPT the last one (the actual ->() call or anon sub).
            // The last node needs source location mapping so that caller() inside BEGIN blocks
            // sees the correct package (after the "package Foo;" node has taken effect).
            for (int i = 0; i < nodes.size() - 1; i++) {
                Node n = nodes.get(i);
                if (n instanceof AbstractNode an) {
                    an.setAnnotation("skipDebug", true);
                }
            }
            // Push a CallerStack entry so that caller() inside BEGIN blocks sees the correct
            // package when using the interpreter backend. Without this, the wrapper's interpreter
            // frame inherits the stale "main" CallerStack entry from PerlLanguageProvider.executePerlCode.
            ErrorMessageUtil.SourceLocation loc = parser.ctx.errorUtil.getSourceLocationAccurate(tokenIndex);
            CallerStack.push(
                    parser.ctx.symbolTable.getCurrentPackage(),
                    loc.fileName(),
                    loc.lineNumber());
            try {
                result = PerlLanguageProvider.executePerlAST(
                        new BlockNode(nodes, tokenIndex),
                        parser.tokens,
                        parsedArgs,
                        contextType);
            } finally {
                CallerStack.pop();
            }
        } catch (PerlExitException e) {
            // exit() inside BEGIN block should terminate the program, not cause compilation error
            // Re-throw so it propagates to the CLI (Main.main()) which will call System.exit()
            throw e;
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
}
