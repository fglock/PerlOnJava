package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

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
        parser.ctx.logDebug("special block " + blockName + " <<<" + parsedArgs.code + ">>>");
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
        return new OperatorNode("undef", null, parser.tokenIndex);
    }
}
