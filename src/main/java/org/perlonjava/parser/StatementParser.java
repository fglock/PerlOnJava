package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.ExtractValueVisitor;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

public class StatementParser {

    /**
     * Parses a while or until statement.
     *
     * @param parser The Parser instance
     * @param label  The label for the loop (can be null)
     * @return A For3Node representing the while/until loop
     */
    public static Node parseWhileStatement(Parser parser, String label) {
        LexerToken operator = parser.consume(LexerTokenType.IDENTIFIER); // "while" "until"

        parser.consume(LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        parser.consume(LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        parser.consume(LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        parser.consume(LexerTokenType.OPERATOR, "}");

        Node continueNode = null;
        if (parser.peek().text.equals("continue")) {
            parser.consume();
            parser.consume(LexerTokenType.OPERATOR, "{");
            continueNode = parser.parseBlock();
            parser.consume(LexerTokenType.OPERATOR, "}");
        }

        if (operator.text.equals("until")) {
            condition = new OperatorNode("not", condition, condition.getIndex());
        }
        return new For3Node(label, true, null,
                condition, null, body, continueNode, false, parser.tokenIndex);
    }

    /**
     * Parses a for or foreach statement.
     *
     * @param parser The Parser instance
     * @param label  The label for the loop (can be null)
     * @return A For1Node or For3Node representing the for/foreach loop
     */
    public static Node parseForStatement(Parser parser, String label) {
        parser.consume(LexerTokenType.IDENTIFIER); // "for" or "foreach"

        // Parse optional loop variable
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
                // 1-argument for loop (foreach-like)
                return parseOneArgumentForLoop(parser, label, varNode, initialization);
            }
        }

        // 3-argument for loop
        return parseThreeArgumentForLoop(parser, label, varNode, initialization);
    }

    /**
     * Helper method to parse a one-argument for loop (foreach-like).
     */
    private static Node parseOneArgumentForLoop(Parser parser, String label, Node varNode, Node initialization) {
        parser.consume(); // Consume ")"

        // Parse the body of the loop
        parser.consume(LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        parser.consume(LexerTokenType.OPERATOR, "}");

        // Parse optional continue block
        Node continueNode = null;
        if (parser.peek().text.equals("continue")) {
            parser.consume();
            parser.consume(LexerTokenType.OPERATOR, "{");
            continueNode = parser.parseBlock();
            parser.consume(LexerTokenType.OPERATOR, "}");
        }

        // Use $_ as the default loop variable if not specified
        if (varNode == null) {
            varNode = new OperatorNode(
                    "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);  // $_
        }

        return new For1Node(label, true, varNode, initialization, body, continueNode, parser.tokenIndex);
    }

    /**
     * Helper method to parse a three-argument for loop.
     */
    private static Node parseThreeArgumentForLoop(Parser parser, String label, Node varNode, Node initialization) {
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

        // 3-argument for doesn't have a continue block

        return new For3Node(label, true, initialization,
                condition, increment, body, null, false, parser.tokenIndex);
    }

    /**
     * Parses an if, unless, or elsif statement.
     *
     * @param parser The Parser instance
     * @return An IfNode representing the if/unless/elsif statement
     */
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

    /**
     * Parses a use or no declaration.
     *
     * @param parser The Parser instance
     * @param token  The current token
     * @return A ListNode representing the use/no declaration
     */
    public static Node parseUseDeclaration(Parser parser, LexerToken token) {
        EmitterContext ctx = parser.ctx;
        ctx.logDebug("use: " + token.text);
        boolean isNoDeclaration = token.text.equals("no");

        parser.consume();   // "use"
        token = parser.peek();

        String fullName = null;
        String packageName = null;
        if (token.type != LexerTokenType.NUMBER && !token.text.matches("^v\\d+")) {
            ctx.logDebug("use module: " + token);
            packageName = IdentifierParser.parseSubroutineIdentifier(parser);
            if (packageName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            fullName = NameNormalizer.moduleToFilename(packageName);
            ctx.logDebug("use fullName: " + fullName);
        }

        // Parse Version string; throw away the result
        // TODO use the Version string
        // TODO call Module->VERSION(12.34)
        String version = parseOptionalPackageVersion(parser);
        parser.ctx.logDebug("use version: " + version);
        if (version != null) {
            // `use` statement can terminate after Version
            token = parser.peek();
            if (token.type == LexerTokenType.EOF || token.text.equals("}") || token.text.equals(";")) {
                return new ListNode(parser.tokenIndex);
            }
        }

        // Parse the parameter list
        boolean hasParentheses = parser.peek().text.equals("(");
        Node list = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        ctx.logDebug("Use statement list hasParentheses:" + hasParentheses + " ast:" + list);

        parser.parseStatementTerminator();

        if (fullName != null) {
            // execute the statement immediately, using:
            // `require "fullName.pm"`

            // Setup the caller stack
            CallerStack.push(
                    ctx.symbolTable.getCurrentPackage(),
                    ctx.compilerOptions.fileName,
                    ctx.errorUtil.getLineNumber(parser.tokenIndex));

            ctx.logDebug("Use statement: " + fullName + " called from " + CallerStack.peek());
            RuntimeScalar ret = new RuntimeScalar(fullName).require();
            ctx.logDebug("Use statement return: " + ret);

            // call Module->import( LIST )
            // or Module->unimport( LIST )
            RuntimeList args = ExtractValueVisitor.getValues(list);
            ctx.logDebug("Use statement list: " + args);
            if (hasParentheses && args.size() == 0) {
                // do not import
            } else {
                // fetch the method using `can` operator
                String importMethod = isNoDeclaration ? "unimport" : "import";
                RuntimeArray canArgs = new RuntimeArray();
                canArgs.push(new RuntimeScalar(packageName));
                canArgs.push(new RuntimeScalar(importMethod));
                RuntimeList codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
                ctx.logDebug("Use can(" + packageName + ", " + importMethod + "): " + codeList);
                if (codeList.size() == 1) {
                    RuntimeScalar code = (RuntimeScalar) codeList.elements.get(0);
                    if (code.getBoolean()) {
                        // call the method
                        ctx.logDebug("Use call : " + importMethod + "(" + args + ")");
                        RuntimeArray importArgs = args.getArrayOfAlias();
                        importArgs.unshift(new RuntimeScalar(packageName));
                        code.apply(importArgs, RuntimeContextType.SCALAR);
                    }
                }
            }

            // restore the caller stack
            CallerStack.pop();
        }

        // return an empty list
        return new ListNode(parser.tokenIndex);
    }

    /**
     * Parses a package declaration.
     *
     * @param parser The Parser instance
     * @param token  The current token
     * @return An OperatorNode or BlockNode representing the package declaration
     */
    public static Node parsePackageDeclaration(Parser parser, LexerToken token) {
        parser.consume();
        String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
        if (packageName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        IdentifierNode nameNode = new IdentifierNode(packageName, parser.tokenIndex);
        OperatorNode packageNode = new OperatorNode(token.text, nameNode, parser.tokenIndex);

        // Parse Version string
        // XXX use the Version string
        String version = parseOptionalPackageVersion(parser);

        BlockNode block = parseOptionalPackageBlock(parser, nameNode, packageNode);
        if (block != null) return block;

        parser.parseStatementTerminator();
        parser.ctx.symbolTable.setCurrentPackage(nameNode.name);
        return packageNode;
    }

    /**
     * Parses an optional package block.
     *
     * @param parser      The Parser instance
     * @param nameNode    The IdentifierNode representing the package name
     * @param packageNode The OperatorNode representing the package declaration
     * @return A BlockNode if a block is present, null otherwise
     */
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

    /**
     * Parses an optional package version.
     *
     * @param parser The Parser instance
     * @return A String representing the package version, or null if not present
     */
    public static String parseOptionalPackageVersion(Parser parser) {
        LexerToken token;
        token = parser.peek();
        if (token.type == LexerTokenType.NUMBER) {
            return NumberParser.parseNumber(parser, parser.consume()).value;
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
            return version.toString();
        }
        return null;
    }
}
