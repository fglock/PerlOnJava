package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.parser.SpecialBlockParser.runSpecialBlock;

public class SubroutineParser {
    /**
     * Parses a subroutine call.
     *
     * @param parser The parser object
     * @return A Node representing the parsed subroutine call.
     */
    static Node parseSubroutineCall(Parser parser) {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be the start of a v-string like v10.20.30
        int currentIndex = parser.tokenIndex;

        String subName = IdentifierParser.parseSubroutineIdentifier(parser);
        parser.ctx.logDebug("SubroutineCall subName `" + subName + "` package " + parser.ctx.symbolTable.getCurrentPackage());
        if (subName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Normalize the subroutine name to include the current package
        String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        // Create an identifier node for the subroutine name
        IdentifierNode nameNode = new IdentifierNode(subName, parser.tokenIndex);

        // Check if the subroutine exists in the global namespace
        boolean subExists = GlobalVariable.existsGlobalCodeRef(fullName);
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

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (TokenUtils.peek(parser).text.equals("(")) {
            // If a prototype exists, we parse it using 'parseRawString' method which handles it like the 'q()' operator.
            // This means it will take everything inside the parentheses as a literal string.
            prototype = ((StringNode) StringParser.parseRawString(parser, "q")).value;
        }

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();

        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (TokenUtils.peek(parser).text.equals(":")) {
            // Consume the colon operator.
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");

            String attrString = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER).text;
            if (TokenUtils.peek(parser).text.equals("(")) {
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

        if (wantName && !TokenUtils.peek(parser).text.equals("{")) {
            // A named subroutine can be predeclared without a block of code.
            String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = new RuntimeCode(prototype, attributes);
            GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(codeRef));
            // return an empty AST list
            return new ListNode(parser.tokenIndex);
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Parse the block of the subroutine, which contains the actual code.
        Node block = parser.parseBlock();

        // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        if (subName == null) {
            // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
            // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
            LexerToken token = TokenUtils.peek(parser);
            if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
                // Throw an exception indicating a syntax error.
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
        }

        // Finally, we create a new 'SubroutineNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        SubroutineNode subroutineNode = new SubroutineNode(subName, prototype, attributes, block, false, currentIndex);

        if (subName != null) {
            // Additional steps for named subroutine:

            // Create the subroutine immediately
            RuntimeList result = runSpecialBlock(parser, "BEGIN", subroutineNode);
            RuntimeScalar codeRef = result.getFirst();

            // - register the subroutine in the namespace
            String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            GlobalVariable.getGlobalCodeRef(fullName).set(codeRef);

            // return an empty AST list
            return new ListNode(parser.tokenIndex);
        }

        // return anonymous subroutine
        return subroutineNode;
    }
}
