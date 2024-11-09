package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.util.Map;

import static org.perlonjava.runtime.SpecialBlock.*;

public class SpecialBlock {

    static OperatorNode parseSpecialBlock(Parser parser) {
        String blockName = TokenUtils.consume(parser).text;

        if (blockName.equals("UNITCHECK")) {
            throw new PerlCompilerException(parser.tokenIndex, "Not implemented: " + blockName, parser.ctx.errorUtil);
        }

        int codeStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode block = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        int codeEnd = parser.tokenIndex;

        String currentPackage = parser.ctx.symbolTable.getCurrentPackage();

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();

        StringBuilder codeSb = new StringBuilder();
        codeSb.append("local ${^GLOBAL_PHASE} = '").append(blockName).append("'; ");
        codeSb.append("package ").append(currentPackage).append("; ");

        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            // Creating `our $var;` entries avoids the "requires explicit package name" error
            // XXX This doesn't do the right thing if the variable is declared with "my" or "state"
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                boolean samePackage = !(entry.decl().equals("our")) || (currentPackage.equals(entry.perlPackage()));
                if (!samePackage) {
                    // "our" variable was declared in a different package
                    codeSb.append("package ").append(entry.perlPackage()).append("; ");
                }
                codeSb.append(entry.decl()).append(" ").append(entry.name()).append("; ");
                if (!samePackage) {
                    codeSb.append("package ").append(currentPackage).append("; ");
                }
            }
        }
        codeSb.append("sub ").append(TokenUtils.toText(parser.tokens, codeStart, codeEnd - 1));

        parsedArgs.code = codeSb.toString();
        parser.ctx.logDebug("Special block " + blockName + " <<<" + parsedArgs.code + ">>>");
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
            RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
            switch (blockName) {
                case "BEGIN" -> codeRef.apply(new RuntimeArray(), RuntimeContextType.VOID);
                case "END" -> saveEndBlock(codeRef);
                case "INIT" -> saveInitBlock(codeRef);
                case "CHECK" -> saveCheckBlock(codeRef);
            }
        } catch (Throwable t) {
            String message = t.getMessage();
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            message += blockName + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }

        // XXX For BEGIN blocks, save the values of lexical variables and initialize them at run time

        return new OperatorNode("undef", null, parser.tokenIndex);
    }
}
