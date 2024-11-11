package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.SpecialBlock.*;

public class SpecialBlock {

    static Node parseSpecialBlock(Parser parser) {
        String blockName = TokenUtils.consume(parser).text;

        int codeStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode block = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        int codeEnd = parser.tokenIndex;
        String blockText = TokenUtils.toText(parser.tokens, codeStart, codeEnd - 1);

        String currentPackage = parser.ctx.symbolTable.getCurrentPackage();

        StringBuilder codeSb = new StringBuilder();
        codeSb.append("local ${^GLOBAL_PHASE} = '").append(blockName).append("'; ");

        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            // Creating `our $var;` entries avoids the "requires explicit package name" error
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                if (entry.decl().equals("our")) {
                    // "our" variable
                    codeSb.append("package ").append(entry.perlPackage()).append("; ");
                    codeSb.append(entry.decl()).append(" ").append(entry.name()).append("; ");
                } else {
                    // "my" or "state" variable
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    String sigil = entry.name().substring(0, 1);
                    codeSb.append("package ").append(beginPackage(ast.id)).append("; ");
                    // Alias the global variable to a lexical variable
                    codeSb.append("our ").append(entry.name()).append("; ");
                    // Instantiate the global variable
                    String beginVar = beginVariable(ast.id, entry.name().substring(1));
                    switch (sigil) {
                        case "$" -> getGlobalVariable(beginVar);
                        case "@" -> getGlobalArray(beginVar);
                        case "%" -> getGlobalHash(beginVar);
                    }
                }
            }
        }
        codeSb.append("package ").append(currentPackage).append("; ");

        if (blockName.equals("BEGIN")) {
            // BEGIN - execute immediately
            codeSb.append(blockText);
        } else {
            // Not BEGIN - return a sub to execute later
            codeSb.append("sub ").append(blockText);
        }

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // special blocks are always run
        parsedArgs.code = codeSb.toString();
        parser.ctx.logDebug("Special block " + blockName + " <<<" + parsedArgs.code + ">>>");
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
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
