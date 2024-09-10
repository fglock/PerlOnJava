package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.ModuleLoader;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

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

    public static Node parseUseDeclaration(Parser parser, LexerToken token) {
        boolean isNoDeclaration = token.text.equals("no");

        parser.consume();
        String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
        if (packageName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        String fullName = ModuleLoader.moduleToFilename(packageName);
        IdentifierNode nameNode = new IdentifierNode(fullName, parser.tokenIndex);

        // Parse Version string; throw away the result
        // XXX use the Version string
        parseOptionalPackageVersion(parser);

        parser.parseStatementTerminator();

        // execute the statement immediately
        parser.ctx.logDebug("Use statement: " + nameNode);
        RuntimeScalar ret = new RuntimeScalar(nameNode.name).require();
        parser.ctx.logDebug("Use statement return: " + ret);

        // TODO call Module->VERSION(12.34)
        // TODO call Module->import( LIST )
        // TODO call Module->unimport( LIST )

        return new ListNode(parser.tokenIndex);
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

}
