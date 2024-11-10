package org.perlonjava.parser;

import org.objectweb.asm.Opcodes;
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

public class SpecialBlock {

    static final String beginPackagePrefix = "_BEGIN_::_BEGIN_";

    static Node parseSpecialBlock(Parser parser) {
        String blockName = TokenUtils.consume(parser).text;

        int codeStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode block = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        int codeEnd = parser.tokenIndex;
        String blockText = TokenUtils.toText(parser.tokens, codeStart, codeEnd - 1);

        String currentPackage = parser.ctx.symbolTable.getCurrentPackage();

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // special blocks are always run

        StringBuilder codeSb = new StringBuilder();
        codeSb.append("local ${^GLOBAL_PHASE} = '").append(blockName).append("'; ");
        codeSb.append("package ").append(currentPackage).append("; ");

        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            // Creating `our $var;` entries avoids the "requires explicit package name" error
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                if (entry.decl().equals("our")) {
                    // "our" variable
                    boolean samePackage = currentPackage.equals(entry.perlPackage());
                    if (!samePackage) {
                        // "our" variable was declared in a different package
                        codeSb.append("package ").append(entry.perlPackage()).append("; ");
                    }
                    codeSb.append(entry.decl()).append(" ").append(entry.name()).append("; ");
                    if (!samePackage) {
                        codeSb.append("package ").append(currentPackage).append("; ");
                    }
                } else {
                    // "my" or "state" variable
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    String sigil = entry.name().substring(0,1);
                    // Save the value from the global variable in "my" variable
                    codeSb.append(entry.decl()).append(" ").append(entry.name())
                            .append(" = ")
                            .append(sigil).append(beginPackagePrefix).append(ast.id)
                            .append("; ");
                }
            }
        }
        if (blockName.equals("BEGIN")) {
            codeSb.append(blockText);
            // save the values of lexical variables and initialize them at run time
            for (SymbolTable.SymbolEntry entry : outerVars.values()) {
                if (entry.decl().equals("my") || entry.decl().equals("state")) {
                    // Generate a global temp variable name
                    OperatorNode ast = entry.ast();
                    String sigil = entry.name().substring(0,1);
                    // Save the value from the "my" variable in the global variable
                    codeSb.append(" ")
                            .append(sigil).append(beginPackagePrefix).append(ast.id)
                            .append(" = ")
                            .append(entry.name()).append("; ");
                }
            }
        } else {
            // Not BEGIN - return a sub to execute later
            codeSb.append("sub ").append(blockText);
        }

        parsedArgs.code = codeSb.toString();
        parser.ctx.logDebug("Special block " + blockName + " <<<" + parsedArgs.code + ">>>");
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
            if (!blockName.equals("BEGIN")) {
                RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
                switch (blockName) {
                    case "END" -> saveEndBlock(codeRef);
                    case "INIT" -> saveInitBlock(codeRef);
                    case "CHECK" -> saveCheckBlock(codeRef);
                    case "UNITCHECK" -> parser.ctx.unitcheckBlocks.push(codeRef);
                }
            }
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

        return new OperatorNode("undef", null, parser.tokenIndex);
    }

    public static RuntimeBaseEntity retrieveBeginVariable(String sigil, int id) {
        String beginVar = beginPackagePrefix + id;
        return switch (sigil) {
            case "$" -> {
                RuntimeScalar temp = removeGlobalVariable(beginVar);
                yield temp == null ? new RuntimeScalar() : temp;
            }
            case "@" -> {
                RuntimeArray temp = removeGlobalArray(beginVar);
                yield temp == null ? new RuntimeArray() : temp;
            }
            case "%" -> {
                RuntimeHash temp = removeGlobalHash(beginVar);
                yield temp == null ? new RuntimeHash() : temp;
            }
            default -> null;
        };
    }
}
