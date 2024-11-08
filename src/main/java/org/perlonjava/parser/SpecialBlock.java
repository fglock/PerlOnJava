package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.SpecialBlock.saveEndBlock;

public class SpecialBlock {
    
    static OperatorNode parseSpecialBlock(Parser parser) {
        String blockName = TokenUtils.consume(parser).text;

        if (blockName.equals("CHECK") || blockName.equals("INIT") || blockName.equals("UNITCHECK")) {
            throw new PerlCompilerException(parser.tokenIndex, "Not implemented: " + blockName, parser.ctx.errorUtil);
        }

        int codeStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode block = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        int codeEnd = parser.tokenIndex;

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.code = "sub " + TokenUtils.toText(parser.tokens, codeStart, codeEnd - 1);
        parser.ctx.logDebug("Special block " + blockName + " <<<" + parsedArgs.code + ">>>");
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());

        //ScopedSymbolTable saveSymbolTable = outerSymbolTable;
        //outerSymbolTable = parser.ctx.symbolTable;

        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (entry.decl().equals("our")) {
                String sigil = entry.name().substring(0, 1);
                String name = entry.perlPackage() + "::" + entry.name().substring(1);
                // Creating the variable entries avoids the "requires explicit package name" error
                // - this doesn't work if the "our" variable was declared in a different package
                // - this doesn't work if the variable is declared with "my" or "state"
                switch (sigil) {
                    case "$" : getGlobalVariable(name);
                    case "@" : getGlobalArray(name);
                    case "%" : getGlobalHash(name);
                }
            }
        }

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
            RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
            if (blockName.equals("BEGIN")) {
                codeRef.apply(new RuntimeArray(), RuntimeContextType.VOID);
            } else if (blockName.equals("END")) {
                saveEndBlock(codeRef);
            }
        } catch (Throwable t) {
            String message = t.getMessage();
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            message += blockName + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }

        //outerSymbolTable = saveSymbolTable;

        return new OperatorNode("undef", null, parser.tokenIndex);
    }
}
