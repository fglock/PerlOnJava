package org.perlonjava.parser;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;
import org.perlonjava.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.parser.SignatureParser.parseSignature;
import static org.perlonjava.runtime.NameNormalizer.normalizeVariableName;

public class SubroutineParser {
    /**
     * Parses a subroutine call.
     *
     * @param parser The parser object
     * @return A Node representing the parsed subroutine call.
     */
    static Node parseSubroutineCall(Parser parser, boolean isMethod) {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be the start of a v-string like v10.20.30
        int currentIndex = parser.tokenIndex;

        String subName = IdentifierParser.parseSubroutineIdentifier(parser);
        parser.ctx.logDebug("SubroutineCall subName `" + subName + "` package " + parser.ctx.symbolTable.getCurrentPackage());
        if (subName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Normalize the subroutine name to include the current package
        String fullName = normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        // Create an identifier node for the subroutine name
        IdentifierNode nameNode = new IdentifierNode(subName, parser.tokenIndex);

        // Check if we are parsing a method;
        // Otherwise, check that the subroutine exists in the global namespace - then fetch prototype and attributes
        boolean subExists = !isMethod && GlobalVariable.existsGlobalCodeRef(fullName);
        String prototype = null;
        List<String> attributes = null;
        if (subExists) {
            // Fetch the subroutine reference
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
            if (codeRef.value == null) {
                // subExists = false;
            } else {
                prototype = ((RuntimeCode) codeRef.value).prototype;
                attributes = ((RuntimeCode) codeRef.value).attributes;
            }
        }
        parser.ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "` attributes " + attributes);

        if (subName.startsWith("v") && subName.matches("^v\\d+$")) {
            if (parser.tokens.get(parser.tokenIndex).text.equals(".") || !subExists) {
                return StringParser.parseVstring(parser, subName, currentIndex);
            }
        }

        // Check if the subroutine call has parentheses
        boolean hasParentheses = TokenUtils.peek(parser).text.equals("(");
        if (!subExists && !hasParentheses) {
            // If the subroutine does not exist and there are no parentheses, it is not a subroutine call
            return nameNode;
        }

        // Handle the parameter list for the subroutine call
        ListNode arguments;
        if (TokenUtils.peek(parser).text.equals("->")) {
            // method call without parentheses
            arguments = new ListNode(parser.tokenIndex);
        } else {
            arguments = consumeArgsWithPrototype(parser, prototype);
        }

        // Rewrite and return the subroutine call as `&name(arguments)`
        return new BinaryOperatorNode("(",
                new OperatorNode("&", nameNode, currentIndex),
                arguments,
                currentIndex);
    }

    public static Node parseSubroutineDefinition(Parser parser, boolean wantName, String declaration) {

        if (declaration != null && (declaration.equals("my") || declaration.equals("state"))) {
            throw new PerlCompilerException("Not implemented: sub declaration `" + declaration + "`");
        }

        // This method is responsible for parsing an anonymous subroutine (a subroutine without a name)
        // or a named subroutine based on the 'wantName' flag.
        int currentIndex = parser.tokenIndex;

        // Initialize the subroutine name to null. This will store the name of the subroutine if 'wantName' is true.
        String subName = null;

        // If the 'wantName' flag is true and the next token is an identifier, we parse the subroutine name.
        if (wantName && TokenUtils.peek(parser).type == LexerTokenType.IDENTIFIER) {
            // 'parseSubroutineIdentifier' is called to handle cases where the subroutine name might be complex
            // (e.g., namespaced, fully qualified names). It may return null if no valid name is found.
            subName = IdentifierParser.parseSubroutineIdentifier(parser);
        }

        // Initialize the prototype node to null. This will store the prototype of the subroutine if it exists.
        String prototype = null;

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();

        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (TokenUtils.peek(parser).text.equals(":")) {
            // Consume the colon operator.
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");

            String attrString = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER).text;
            if (parser.tokens.get(parser.tokenIndex).text.equals("(")) {
                String argString = ((StringNode) StringParser.parseRawString(parser, "q")).value;

                if (attrString.equals("prototype")) {
                    //  :prototype($)
                    prototype = argString;
                }

                attrString += "(" + argString + ")";
            }

            // Consume the attribute name (an identifier) and add it to the attributes list.
            attributes.add(attrString);
        }

        ListNode signature = null;

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (TokenUtils.peek(parser).text.equals("(")) {
            // If a prototype exists, we parse it using 'parseRawString' method which handles it like the 'q()' operator.
            // This means it will take everything inside the parentheses as a literal string.
            String paren = ((StringNode) StringParser.parseRawString(parser, "q")).value;
            if (parser.ctx.symbolTable.isFeatureCategoryEnabled("signatures")) {
                parser.ctx.logDebug("Signatures feature enabled: " + paren);
                // If the signatures feature is enabled, we parse the prototype as a signature.
                signature = parseSignature(parser, paren);
                // TODO integrate the AST for the signature into the subroutine definition
                parser.ctx.logDebug("Signature AST: " + signature);
            } else {
                // If the signatures feature is not enabled, we just parse the prototype as a string.
                prototype = paren;
            }
        }

        if (wantName && !TokenUtils.peek(parser).text.equals("{")) {
            // A named subroutine can be predeclared without a block of code.
            String fullName = normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = (RuntimeCode) GlobalVariable.getGlobalCodeRef(fullName).value;
            codeRef.prototype = prototype;
            codeRef.attributes = attributes;
            // return an empty AST list
            return new ListNode(parser.tokenIndex);
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Parse the block of the subroutine, which contains the actual code.
        BlockNode block = ParseBlock.parseBlock(parser);

        // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Insert signature code in the block
        if (signature != null) {
            block.elements.addAll(0, signature.elements);
        }

        if (subName == null) {
            return handleAnonSub(parser, subName, prototype, attributes, block, currentIndex);
        } else {
            handleNamedSub(parser, subName, prototype, attributes, block, currentIndex);
            // return an empty AST list
            return new ListNode(parser.tokenIndex);
        }
    }

    private static SubroutineNode handleAnonSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, int currentIndex) {
        // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
        // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            // Throw an exception indicating a syntax error.
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        // Finally, we return a new 'SubroutineNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        return new SubroutineNode(subName, prototype, attributes, block, false, currentIndex);
    }

    private static void handleNamedSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, int currentIndex) {
        // Finally, we create a new 'SubroutineNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        SubroutineNode subroutineNode = new SubroutineNode(subName, prototype, attributes, block, false, currentIndex);

        // Create the subroutine immediately
        int tokenIndex = parser.tokenIndex;

        // Create AST nodes for setting up the capture variables and package declaration
        List<Node> nodes = new ArrayList<>();

        // Declare capture variables
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                String packageName;
                if (entry.decl().equals("our")) {
                    // "our" variable lives in a Perl package
                    packageName = entry.perlPackage();
                } else {
                    // "my" or "state" variable live in a special BEGIN package
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    packageName = PersistentVariable.beginPackage(ast.id);
                }
                // Emit: our $var
                String varName = normalizeVariableName(entry.name().substring(1), packageName);
                System.out.println("our " + varName);
                nodes.add(
                        new OperatorNode(
                                "our",
                                new OperatorNode(
                                        entry.name().substring(0, 1),
                                        new IdentifierNode(varName, tokenIndex),
                                        tokenIndex),
                                tokenIndex));
            }
        }
        // Emit: package PKG
        nodes.add(
                new OperatorNode("package",
                        new IdentifierNode(
                                parser.ctx.symbolTable.getCurrentPackage(), tokenIndex), tokenIndex));

        SubroutineNode anonSub =
                new SubroutineNode(
                        null,
                        null,
                        null,
                        subroutineNode,
                        false,
                        tokenIndex);

        nodes.add(
                new BinaryOperatorNode(
                        "->",
                        anonSub,
                        new ListNode(tokenIndex),
                        tokenIndex
                )
        );

        ArgumentParser.CompilerOptions parsedArgs = parser.ctx.compilerOptions.clone();
        parsedArgs.compileOnly = false; // Special blocks are always run
        parser.ctx.logDebug("Special block captures " + parser.ctx.symbolTable.getAllVisibleVariables());
        RuntimeList result1;
        // Setup the caller stack for BEGIN
        CallerStack.push(
                parser.ctx.symbolTable.getCurrentPackage(),
                parser.ctx.compilerOptions.fileName,
                parser.ctx.errorUtil.getLineNumber(parser.tokenIndex));
        try {
            SpecialBlockParser.setCurrentScope(parser.ctx.symbolTable);
            result1 = PerlLanguageProvider.executePerlAST(
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
            message += "BEGIN" + " failed--compilation aborted";
            throw new PerlCompilerException(parser.tokenIndex, message, parser.ctx.errorUtil);
        }
        CallerStack.pop();  // restore the caller stack
        GlobalVariable.getGlobalVariable("main::@").set(""); // Reset error variable

        RuntimeList result = result1;
        RuntimeCode codeFrom = (RuntimeCode) result.getFirst().value;

        // - register the subroutine in the namespace
        String fullName = normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        RuntimeCode code = (RuntimeCode) GlobalVariable.getGlobalCodeRef(fullName).value;
        RuntimeCode.copy(code, codeFrom);
    }

}
