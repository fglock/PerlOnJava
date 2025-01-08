package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

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

        // Consume the opening brace '{'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        // Parse the block content
        BlockNode block = ParseBlock.parseBlock(parser);
        // Consume the closing brace '}'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Execute the special block, throw away the result
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
        // Emit: package PKG
        nodes.add(
                new OperatorNode("package",
                        new IdentifierNode(
                                parser.ctx.symbolTable.getCurrentPackage(), tokenIndex), tokenIndex));

        if (blockPhase.equals("BEGIN")) {
            // BEGIN - execute immediately
            nodes.add(block);
        } else {
            // Not BEGIN - return a sub to execute later
            nodes.add(
                    new SubroutineNode(
                            null,
                            null,
                            null,
                            block,
                            false,
                            tokenIndex)
            );
        }

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // Special blocks are always run
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        // Setup the caller stack for BEGIN
        CallerStack.push(
                parser.ctx.symbolTable.getCurrentPackage(),
                parser.ctx.compilerOptions.fileName,
                parser.ctx.errorUtil.getLineNumber(parser.tokenIndex));
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
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            message += blockPhase + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }
        CallerStack.pop();  // restore the caller stack
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
