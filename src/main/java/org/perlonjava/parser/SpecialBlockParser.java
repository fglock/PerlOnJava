package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
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

import static org.perlonjava.parser.SubroutineParser.blockASTtoCode;
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
     * @param blockNode  The block AST to execute.
     * @return A RuntimeList containing the result of the execution.
     */
    static RuntimeList runSpecialBlock(Parser parser, String blockPhase, BlockNode blockNode) {
        RuntimeList result = new RuntimeList();

        // Emit as first operation inside the block: local ${^GLOBAL_PHASE} = "BEGIN"
        String phaseName = blockPhase.equals("BEGIN") || blockPhase.equals("UNITCHECK")
                ? "START"
                : blockPhase;
        int tokenIndex = parser.tokenIndex;
        blockNode.elements.addFirst(
                new BinaryOperatorNode("=",
                        new OperatorNode("local",
                                new OperatorNode("$",
                                        new IdentifierNode(GLOBAL_PHASE, tokenIndex),
                                        tokenIndex),
                                tokenIndex),
                        new StringNode(phaseName, tokenIndex),
                        tokenIndex));
        // System.out.println("Block: " + blockNode);
        RuntimeCode code = blockASTtoCode(parser.ctx, blockNode);

        if (blockPhase.equals("BEGIN")) {
            // BEGIN - execute immediately
            // Setup the caller stack for BEGIN
            CallerStack.push(
                    parser.ctx.symbolTable.getCurrentPackage(),
                    parser.ctx.compilerOptions.fileName,
                    parser.ctx.errorUtil.getLineNumber(parser.tokenIndex));
            result = code.apply(new RuntimeArray(), RuntimeContextType.LIST);
            // System.out.println("Result: " + result);

//            String message = t.getMessage();
//            if (!message.endsWith("\n")) {
//                message += "\n";
//            }
//            message += blockPhase + " failed--compilation aborted";
//            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
            CallerStack.pop();  // restore the caller stack
            GlobalVariable.getGlobalVariable("main::@").set(""); // Reset error variable
        } else {
            RuntimeScalar codeRef = new RuntimeScalar(code);
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
