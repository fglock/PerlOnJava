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
                if (entry.decl().equals("our")) {
                    // "our" variable lives in a Perl package
                    packageName = entry.perlPackage();
                    // Emit: package PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(packageName, tokenIndex), tokenIndex));
                } else {
                    // "my" or "state" variable live in a special BEGIN package
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    packageName = PersistentVariable.beginPackage(ast.id);
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
                if (!entry.decl().equals("our")) {
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
                                GlobalVariable.globalArrays.put(fullName, (RuntimeArray) runtimeValue);
                                parser.ctx.logDebug("BEGIN block: Aliased array " + fullName);
                            } else if (runtimeValue instanceof RuntimeHash) {
                                GlobalVariable.globalHashes.put(fullName, (RuntimeHash) runtimeValue);
                                parser.ctx.logDebug("BEGIN block: Aliased hash " + fullName);
                            } else if (runtimeValue instanceof RuntimeScalar) {
                                GlobalVariable.globalVariables.put(fullName, (RuntimeScalar) runtimeValue);
                                parser.ctx.logDebug("BEGIN block: Aliased scalar " + fullName);
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
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        try {
            setCurrentScope(parser.ctx.symbolTable);
            result = PerlLanguageProvider.executePerlAST(
                    new BlockNode(nodes, tokenIndex),
                    parser.tokens,
                    parsedArgs);
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
