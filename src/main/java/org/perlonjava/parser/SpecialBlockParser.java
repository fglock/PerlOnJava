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

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.SpecialBlock.*;

/**
 * The SpecialBlockParser class is responsible for parsing and executing special blocks
 * in Perl scripts, such as BEGIN, END, INIT, CHECK, and UNITCHECK blocks.
 */
public class SpecialBlockParser {

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
        BlockNode block = parser.parseBlock();
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
                                            new IdentifierNode("main::" + Character.toString('G' - 'A' + 1) + "LOBAL_PHASE",
                                                    tokenIndex),
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
                                    new IdentifierNode(beginPackage(ast.id), tokenIndex), tokenIndex));
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
        try {
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

        if (!blockPhase.equals("BEGIN")) {
            RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
            switch (blockPhase) {
                case "END" -> saveEndBlock(codeRef);
                case "INIT" -> saveInitBlock(codeRef);
                case "CHECK" -> saveCheckBlock(codeRef);
                case "UNITCHECK" -> parser.ctx.unitcheckBlocks.push(codeRef);
            }
        }

        return result;
    }

    /**
     * Constructs a package name for storing compile-time variables, with the given ID.
     *
     * @param id The ID of the BEGIN block.
     * @return The package name for the BEGIN block.
     */
    static String beginPackage(int id) {
        return "PerlOnJava::_BEGIN_" + id;
    }

    /**
     * Constructs a compile-time variable name for a BEGIN block, with the given ID and name.
     *
     * @param id   The ID of the BEGIN block.
     * @param name The name of the variable.
     * @return The variable name for the BEGIN block.
     */
    static String beginVariable(int id, String name) {
        return beginPackage(id) + "::" + name;
    }

    /**
     * Retrieves a compile-time scalar variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveBeginScalar(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeScalar temp = removeGlobalVariable(beginVar);
        return temp == null ? new RuntimeScalar() : temp;
    }

    /**
     * Retrieves a compile-time array variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeArray.
     */
    public static RuntimeArray retrieveBeginArray(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeArray temp = removeGlobalArray(beginVar);
        return temp == null ? new RuntimeArray() : temp;
    }

    /**
     * Retrieves a compile-time hash variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveBeginHash(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeHash temp = removeGlobalHash(beginVar);
        return temp == null ? new RuntimeHash() : temp;
    }
}
