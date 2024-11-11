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

public class SpecialBlockParser {

    static Node parseSpecialBlock(Parser parser) {
        String blockName = TokenUtils.consume(parser).text;

        int codeStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode block = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Create AST nodes for the additional code in codeSb
        List<Node> nodes = new ArrayList<>();

        // emit:  local ${^GLOBAL_PHASE} = "BEGIN"
        nodes.add(
                new BinaryOperatorNode("=",
                new OperatorNode("local",
                    new OperatorNode("$",
                        new IdentifierNode(Character.toString('G' - 'A' + 1) + "LOBAL_PHASE",
                                codeStart),
                            codeStart),
                        codeStart),
                new StringNode(blockName, codeStart),
                codeStart));

        // Declare capture variables
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                if (entry.decl().equals("our")) {
                    // "our" variable lives in a Perl package
                    // emit:  package PKG
                    nodes.add(
                        new OperatorNode("package",
                                new IdentifierNode(entry.perlPackage(), codeStart), codeStart));
                } else {
                    // "my" or "state" variable live in a special BEGIN package
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    // emit:  package BEGIN_PKG
                    nodes.add(
                            new OperatorNode("package",
                                    new IdentifierNode(beginPackage(ast.id), codeStart), codeStart));
                }
                // emit:  our $var
                nodes.add(
                        new OperatorNode(
                                "our",
                                new OperatorNode(
                                        entry.name().substring(0, 1),
                                        new IdentifierNode(entry.name().substring(1), codeStart),
                                        codeStart),
                                codeStart));
            }
        }
        // emit:  package PKG
        nodes.add(
                new OperatorNode("package",
                        new IdentifierNode(
                                parser.ctx.symbolTable.getCurrentPackage(), codeStart), codeStart));


        if (blockName.equals("BEGIN")) {
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
                        codeStart)
            );
        }

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // special blocks are always run
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlAST(
                    new BlockNode(nodes, codeStart),
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
            message += blockName + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }

        if (!blockName.equals("BEGIN")) {
            RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
            switch (blockName) {
                case "END" -> saveEndBlock(codeRef);
                case "INIT" -> saveInitBlock(codeRef);
                case "CHECK" -> saveCheckBlock(codeRef);
                case "UNITCHECK" -> parser.ctx.unitcheckBlocks.push(codeRef);
            }
        }

        return new OperatorNode("undef", null, parser.tokenIndex);
    }

    static String beginPackage(int id) {
        return "PerlOnJava::_BEGIN_" + id;
    }

    static String beginVariable(int id, String name) {
        return beginPackage(id) + "::" + name;
    }

    public static RuntimeScalar retrieveBeginScalar(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeScalar temp = removeGlobalVariable(beginVar);
        return temp == null ? new RuntimeScalar() : temp;
    }

    public static RuntimeArray retrieveBeginArray(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeArray temp = removeGlobalArray(beginVar);
        return temp == null ? new RuntimeArray() : temp;
    }

    public static RuntimeHash retrieveBeginHash(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeHash temp = removeGlobalHash(beginVar);
        return temp == null ? new RuntimeHash() : temp;
    }
}
