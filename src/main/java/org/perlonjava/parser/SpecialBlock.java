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

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // special blocks are always run

        Node returnNode;

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
        if (blockName.equals("BEGIN")) {
            codeSb.append(blockText);
            // save the values of lexical variables and initialize them at run time
            int tokenIndex = parser.tokenIndex;
            List<Node> elements = new ArrayList<>();
            returnNode = new ListNode(elements, tokenIndex); // this will be the run time Node
            for (SymbolTable.SymbolEntry entry : outerVars.values()) {
                if (entry.decl().equals("my") || entry.decl().equals("state")) {
                    String sigil = entry.name().substring(0,1);
                    // XXX TODO sigil "@", "%" not implemented
                    if (sigil.equals("$")) {

                        // Generate a unique variable name
                        String name = entry.name().substring(1);
                        String beginVar = "_BEGIN_" + EmitterMethodCreator.classCounter++;
                        String beginPackage = "_BEGIN_::";

                        // Save the value in a global variable
                        codeSb.append(" ").append(sigil).append("_BEGIN_::").append(beginVar).append(" = ").append(entry.name()).append("; ");

                        // Retrieve the value at run time:  "($v //= $init, undef $init);"
                        // XXX This should run at the AST node where the variable is declared
                        // XXX This should have global effect, in case more BEGIN blocks share the variable
                        elements.add(
                                new BinaryOperatorNode("//=",
                                        new OperatorNode(sigil, new IdentifierNode(name, tokenIndex), tokenIndex),
                                        new OperatorNode(sigil, new IdentifierNode(beginPackage + beginVar, tokenIndex), tokenIndex),
                                        tokenIndex));
                        elements.add(
                                new OperatorNode("undef",
                                        ListNode.makeList(
                                                new OperatorNode(sigil, new IdentifierNode(beginPackage + beginVar, tokenIndex), tokenIndex)),
                                        tokenIndex));
                    }
                }
            }
        } else {
            // Not BEGIN - return a sub to execute later
            codeSb.append("sub ").append(blockText);
            returnNode = new OperatorNode("undef", null, parser.tokenIndex);
        }

        parsedArgs.code = codeSb.toString();
        parser.ctx.logDebug("Special block " + blockName + " <<<" + parsedArgs.code + ">>>");
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
            RuntimeScalar codeRef = (RuntimeScalar) result.elements.getFirst();
            switch (blockName) {
                // case "BEGIN" -> codeRef.apply(new RuntimeArray(), RuntimeContextType.VOID);
                case "END" -> saveEndBlock(codeRef);
                case "INIT" -> saveInitBlock(codeRef);
                case "CHECK" -> saveCheckBlock(codeRef);
                case "UNITCHECK" -> parser.ctx.unitcheckBlocks.push(codeRef);
            }
        } catch (Throwable t) {
            String message = t.getMessage();
            if (!message.endsWith("\n")) {
                message += "\n";
            }
            message += blockName + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }

        return returnNode;
    }
}
