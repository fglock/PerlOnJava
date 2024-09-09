package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.ArrayList;
import java.util.List;

public class StatementParser {

    public static Node parseWhileStatement(Parser parser) {
        LexerToken operator = parser.consume(LexerTokenType.IDENTIFIER); // "while" "until"

        parser.consume(LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        parser.consume(LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        parser.consume(LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        parser.consume(LexerTokenType.OPERATOR, "}");

        if (operator.text.equals("until")) {
            condition = new OperatorNode("not", condition, condition.getIndex());
        }
        return new For3Node(true, null, condition, null, body, parser.tokenIndex);
    }

    public static Node parseForStatement(Parser parser) {
        parser.consume(LexerTokenType.IDENTIFIER); // "for" "foreach"

        Node varNode = null;
        LexerToken token = parser.peek(); // "my" "$" "("
        if (token.text.equals("my") || token.text.equals("$")) {
            parser.parsingForLoopVariable = true;
            varNode = parser.parsePrimary();
            parser.parsingForLoopVariable = false;
        }

        parser.consume(LexerTokenType.OPERATOR, "(");

        // Parse the initialization part
        Node initialization = null;
        if (!parser.peek().text.equals(";")) {
            initialization = parser.parseExpression(0);

            token = parser.peek();
            if (token.text.equals(")")) {
                // 1-argument for
                parser.consume();

                // Parse the body of the loop
                parser.consume(LexerTokenType.OPERATOR, "{");
                Node body = parser.parseBlock();
                parser.consume(LexerTokenType.OPERATOR, "}");

                if (varNode == null) {
                    varNode = new OperatorNode(
                            "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);  // $_
                }
                return new For1Node(true, varNode, initialization, body, parser.tokenIndex);
            }
        }
        // 3-argument for
        if (varNode != null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        parser.consume(LexerTokenType.OPERATOR, ";");

        // Parse the condition part
        Node condition = null;
        if (!parser.peek().text.equals(";")) {
            condition = parser.parseExpression(0);
        }
        parser.consume(LexerTokenType.OPERATOR, ";");

        // Parse the increment part
        Node increment = null;
        if (!parser.peek().text.equals(")")) {
            increment = parser.parseExpression(0);
        }
        parser.consume(LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        parser.consume(LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        parser.consume(LexerTokenType.OPERATOR, "}");

        return new For3Node(true, initialization, condition, increment, body, parser.tokenIndex);
    }

    public static Node parseIfStatement(Parser parser) {
        LexerToken operator = parser.consume(LexerTokenType.IDENTIFIER); // "if", "unless", "elsif"
        parser.consume(LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        parser.consume(LexerTokenType.OPERATOR, ")");
        parser.consume(LexerTokenType.OPERATOR, "{");
        Node thenBranch = parser.parseBlock();
        parser.consume(LexerTokenType.OPERATOR, "}");
        Node elseBranch = null;
        LexerToken token = parser.peek();
        if (token.text.equals("else")) {
            parser.consume(LexerTokenType.IDENTIFIER); // "else"
            parser.consume(LexerTokenType.OPERATOR, "{");
            elseBranch = parser.parseBlock();
            parser.consume(LexerTokenType.OPERATOR, "}");
        } else if (token.text.equals("elsif")) {
            elseBranch = parseIfStatement(parser);
        }
        return new IfNode(operator.text, condition, thenBranch, elseBranch, parser.tokenIndex);
    }

    public static Node parsePackageDeclaration(Parser parser, LexerToken token) {
        parser.consume();
        String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
        if (packageName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        IdentifierNode nameNode = new IdentifierNode(packageName, parser.tokenIndex);
        OperatorNode packageNode = new OperatorNode(token.text, nameNode, parser.tokenIndex);

        // Parse Version string; throw away the result
        // XXX use the Version string
        parseOptionalPackageVersion(parser);

        BlockNode block = parseOptionalPackageBlock(parser, nameNode, packageNode);
        if (block != null) return block;

        parser.parseStatementTerminator();
        parser.ctx.symbolTable.setCurrentPackage(nameNode.name);
        return packageNode;
    }

    public static BlockNode parseOptionalPackageBlock(Parser parser, IdentifierNode nameNode, OperatorNode packageNode) {
        LexerToken token;
        token = parser.peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // package NAME BLOCK
            parser.consume(LexerTokenType.OPERATOR, "{");
            parser.ctx.symbolTable.enterScope();
            parser.ctx.symbolTable.setCurrentPackage(nameNode.name);
            BlockNode block = parser.parseBlock();

            // Insert packageNode as first statement in block
            block.elements.add(0, packageNode);

            parser.ctx.symbolTable.exitScope();
            parser.consume(LexerTokenType.OPERATOR, "}");
            return block;
        }
        return null;
    }

    public static void parseOptionalPackageVersion(Parser parser) {
        LexerToken token;
        token = parser.peek();
        if (token.type == LexerTokenType.NUMBER) {
            NumberParser.parseNumber(parser, parser.consume());
        } else if (token.text.startsWith("v")) {
            // parseDottedDecimalVersion
            StringBuilder version = new StringBuilder(token.text); // start with 'v'
            parser.consume();

            int componentCount = 0;

            // Loop through components separated by '.'
            while (true) {
                if (!parser.peek().text.equals(".")) {
                    if (componentCount < 2) { // Ensures at least 3 components (v1.2.3)
                        throw new PerlCompilerException(parser.tokenIndex, "Dotted-decimal version must have at least 3 components", parser.ctx.errorUtil);
                    } else {
                        break; // Stop if there's no '.' and we have enough components
                    }
                }

                version.append(parser.consume().text); // consume '.'

                if (parser.peek().type == LexerTokenType.NUMBER) {
                    version.append(parser.consume().text); // consume number
                    componentCount++;
                } else {
                    throw new PerlCompilerException(parser.tokenIndex, "Invalid dotted-decimal format", parser.ctx.errorUtil);
                }
            }

            parser.ctx.logDebug("Dotted-decimal Version: " + version);
        }
    }

    public static Node parseSubroutineDefinition(Parser parser, boolean wantName) {
        // This method is responsible for parsing an anonymous subroutine (a subroutine without a name)
        // or a named subroutine based on the 'wantName' flag.

        // Initialize the subroutine name to null. This will store the name of the subroutine if 'wantName' is true.
        String subName = null;

        // If the 'wantName' flag is true and the next token is an identifier, we parse the subroutine name.
        if (wantName && parser.peek().type == LexerTokenType.IDENTIFIER) {
            // 'parseSubroutineIdentifier' is called to handle cases where the subroutine name might be complex
            // (e.g., namespaced, fully qualified names). It may return null if no valid name is found.
            subName = IdentifierParser.parseSubroutineIdentifier(parser);
        }

        // Initialize the prototype node to null. This will store the prototype of the subroutine if it exists.
        String prototype = null;

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (parser.peek().text.equals("(")) {
            // If a prototype exists, we parse it using 'parseRawString' method which handles it like the 'q()' operator.
            // This means it will take everything inside the parentheses as a literal string.
            prototype = ((StringNode) StringParser.parseRawString(parser, "q")).value;
        }

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();

        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (parser.peek().text.equals(":")) {
            // Consume the colon operator.
            parser.consume(LexerTokenType.OPERATOR, ":");
            // Consume the attribute name (an identifier) and add it to the attributes list.
            attributes.add(parser.consume(LexerTokenType.IDENTIFIER).text);
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        parser.consume(LexerTokenType.OPERATOR, "{");

        // Parse the block of the subroutine, which contains the actual code.
        Node block = parser.parseBlock();

        // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
        parser.consume(LexerTokenType.OPERATOR, "}");

        // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
        // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
        LexerToken token = parser.peek();
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            // Throw an exception indicating a syntax error.
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Finally, we create a new 'AnonSubNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        AnonSubNode anonSubNode = new AnonSubNode(subName, prototype, attributes, block, false, parser.tokenIndex);

        if (subName != null) {
            // Additional steps for named subroutine:
            // - register the subroutine in the namespace
            // - add the typeglob assignment:  *name = sub () :attr {...}

            // register the named subroutine
            String fullName = GlobalContext.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = new RuntimeCode(prototype);
            GlobalContext.getGlobalCodeRef(fullName).set(new RuntimeScalar(codeRef));

            // return typeglob assignment
            return new BinaryOperatorNode("=",
                    new OperatorNode("*",
                            new IdentifierNode(fullName, parser.tokenIndex),
                            parser.tokenIndex),
                    anonSubNode,
                    parser.tokenIndex);
        }

        // return anonymous subroutine
        return anonSubNode;
    }
}
