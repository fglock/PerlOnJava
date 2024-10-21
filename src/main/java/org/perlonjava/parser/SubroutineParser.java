package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.List;

public class SubroutineParser {
    /**
     * Parses a subroutine call.
     *
     * @param parser
     * @return A Node representing the parsed subroutine call.
     */
    static Node parseSubroutineCall(Parser parser) {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be a v-string like v10.20.30   XXX TODO
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
        boolean subExists = GlobalContext.existsGlobalCodeRef(fullName);
        String prototype = null;
        List<String> attributes = null;
        if (subExists) {
            // Fetch the subroutine reference
            RuntimeScalar codeRef = GlobalContext.getGlobalCodeRef(fullName);
            prototype = ((RuntimeCode) codeRef.value).prototype;
            attributes = ((RuntimeCode) codeRef.value).attributes;
        }
        parser.ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "` attributes " + attributes);

        // Check if the subroutine call has parentheses
        boolean hasParentheses = TokenUtils.peek(parser).text.equals("(");
        if (!subExists && !hasParentheses) {
            // If the subroutine does not exist and there are no parentheses, it is not a subroutine call
            return nameNode;
        }

        // Handle the parameter list for the subroutine call
        Node arguments;
        if (prototype == null) {
            // no prototype
            arguments = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        } else if (prototype.isEmpty()) {
            // prototype is empty string
            arguments = new ListNode(parser.tokenIndex);
        } else if (prototype.equals("$")) {
            // prototype is `$`
            arguments = ListParser.parseZeroOrOneList(parser, 1);
        } else if (prototype.equals(";$")) {
            // prototype is `;$`
            arguments = ListParser.parseZeroOrOneList(parser, 0);
        } else {
            // XXX TODO: Handle more prototypes or parameter variables
            arguments = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        }

        // Rewrite and return the subroutine call as `&name(arguments)`
        return new BinaryOperatorNode("(",
                new OperatorNode("&", nameNode, currentIndex),
                arguments,
                currentIndex);
    }

    public static Node parseSubroutineDefinition(Parser parser, boolean wantName) {
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
            // Consume the attribute name (an identifier) and add it to the attributes list.
            attributes.add(TokenUtils.consume(parser, LexerTokenType.IDENTIFIER).text);
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Parse the block of the subroutine, which contains the actual code.
        Node block = parser.parseBlock();

        // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
        // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            // Throw an exception indicating a syntax error.
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Finally, we create a new 'SubroutineNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        SubroutineNode subroutineNode = new SubroutineNode(subName, prototype, attributes, block, false, currentIndex);

        if (subName != null) {
            // Additional steps for named subroutine:
            // - register the subroutine in the namespace
            // - add the typeglob assignment:  *name = sub () :attr {...}

            // register the named subroutine
            String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = new RuntimeCode(prototype, attributes);
            GlobalContext.getGlobalCodeRef(fullName).set(new RuntimeScalar(codeRef));

            // return typeglob assignment
            return new BinaryOperatorNode("=",
                    new OperatorNode("*",
                            new IdentifierNode(fullName, currentIndex),
                            currentIndex),
                    subroutineNode,
                    currentIndex);
        }

        // return anonymous subroutine
        return subroutineNode;
    }
}
