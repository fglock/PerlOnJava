package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.ExtractValueVisitor;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

import static org.perlonjava.parser.NumberParser.parseNumber;
import static org.perlonjava.parser.StringParser.parseVstring;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.ScalarUtils.printable;

/**
 * The StatementParser class is responsible for parsing various types of statements
 * in the Perl-like language, including while loops, for loops, if statements,
 * use declarations, and package declarations.
 */
public class StatementParser {

    /**
     * Parses a while or until statement.
     *
     * @param parser The Parser instance
     * @param label  The label for the loop (can be null)
     * @return A For3Node representing the while/until loop
     */
    public static Node parseWhileStatement(Parser parser, String label) {
        LexerToken operator = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "while" "until"

        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        Node continueNode = null;
        if (TokenUtils.peek(parser).text.equals("continue")) {
            TokenUtils.consume(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            continueNode = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
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
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "for" or "foreach"

        // Parse optional loop variable
        Node varNode = null;
        LexerToken token = TokenUtils.peek(parser); // "my" "$" "("
        if (token.text.equals("my") || token.text.equals("$")) {
            parser.parsingForLoopVariable = true;
            varNode = parser.parsePrimary();
            parser.parsingForLoopVariable = false;
        }

        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");

        // Parse the initialization part
        Node initialization = null;
        if (!TokenUtils.peek(parser).text.equals(";")) {
            initialization = parser.parseExpression(0);

            token = TokenUtils.peek(parser);
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
        TokenUtils.consume(parser); // Consume ")"

        // Parse the body of the loop
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse optional continue block
        Node continueNode = null;
        if (TokenUtils.peek(parser).text.equals("continue")) {
            TokenUtils.consume(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            continueNode = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
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

        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ";");

        // Parse the condition part
        Node condition = null;
        if (!TokenUtils.peek(parser).text.equals(";")) {
            condition = parser.parseExpression(0);
        }
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ";");

        // Parse the increment part
        Node increment = null;
        if (!TokenUtils.peek(parser).text.equals(")")) {
            increment = parser.parseExpression(0);
        }
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node body = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

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
        LexerToken operator = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "if", "unless", "elsif"
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node thenBranch = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        Node elseBranch = null;
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("else")) {
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "else"
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            elseBranch = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        } else if (token.text.equals("elsif")) {
            elseBranch = parseIfStatement(parser);
        }
        return new IfNode(operator.text, condition, thenBranch, elseBranch, parser.tokenIndex);
    }

    /**
     * Parses a try-catch-finally statement.
     *
     * @param parser The Parser instance
     * @return A TryNode representing the try-catch-finally statement
     */
    public static Node parseTryStatement(Parser parser) {
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "try"

        // Parse the try block
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node tryBlock = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse the catch block
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "catch"
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        Node catchParameter = parser.parseExpression(0); // Parse the exception variable
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node catchBlock = parser.parseBlock();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse the optional finally block
        Node finallyBlock = null;
        if (TokenUtils.peek(parser).text.equals("finally")) {
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "finally"
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            finallyBlock = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }

        return new TryNode(tryBlock, catchParameter, catchBlock, finallyBlock, parser.tokenIndex);
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

        TokenUtils.consume(parser);   // "use"
        token = TokenUtils.peek(parser);

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

        // Parse Version string
        RuntimeScalar versionScalar = scalarUndef;
        Node versionNode = parseOptionalPackageVersion(parser);
        if (versionNode != null) {
            parser.ctx.logDebug("use version: " + versionNode + " next:" + TokenUtils.peek(parser));
            // Extract version string using ExtractValueVisitor
            RuntimeList versionValues = ExtractValueVisitor.getValues(versionNode);
            if (!versionValues.elements.isEmpty()) {
                // String versionString = versionValues.elements.getFirst().toString();
                // parser.ctx.logDebug("use version String: " + printable(versionString));
                versionScalar = (RuntimeScalar) versionValues.elements.getFirst();
                if (packageName == null) {
                    parser.ctx.logDebug("use version: check Perl version");
                    Universal.compareVersion(
                            new RuntimeScalar(GlobalContext.perlVersion),
                            versionScalar,
                            "Perl");
                }
            }
            if (packageName == null) {
                // `use` statement can terminate after Version
                token = TokenUtils.peek(parser);
                if (token.type == LexerTokenType.EOF || token.text.equals("}") || token.text.equals(";")) {
                    return new ListNode(parser.tokenIndex);
                }
            }
        }

        // Parse the parameter list
        boolean hasParentheses = TokenUtils.peek(parser).text.equals("(");
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
            // execute 'require(fullName)'
            RuntimeScalar ret = new RuntimeScalar(fullName).require();
            ctx.logDebug("Use statement return: " + ret);

            if (versionNode != null) {
                // check module version
                parser.ctx.logDebug("use version: check module version");
                RuntimeArray args = new RuntimeArray();
                args.push(new RuntimeScalar(packageName));
                args.push(versionScalar);
                Universal.VERSION(args, RuntimeContextType.SCALAR);
            }

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
                    RuntimeScalar code = (RuntimeScalar) codeList.elements.getFirst();
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
        TokenUtils.consume(parser);
        String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
        if (packageName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        IdentifierNode nameNode = new IdentifierNode(packageName, parser.tokenIndex);
        OperatorNode packageNode = new OperatorNode(token.text, nameNode, parser.tokenIndex);

        // Parse Version string
        // XXX use the Version string
        Node version = parseOptionalPackageVersion(parser);
        parser.ctx.logDebug("package version: " + version);

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
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // package NAME BLOCK
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            parser.ctx.symbolTable.enterScope();
            parser.ctx.symbolTable.setCurrentPackage(nameNode.name);
            BlockNode block = parser.parseBlock();

            // Insert packageNode as first statement in block
            block.elements.addFirst(packageNode);

            parser.ctx.symbolTable.exitScope();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
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
    public static Node parseOptionalPackageVersion(Parser parser) {
        LexerToken token;
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.NUMBER) {
            return parseNumber(parser, TokenUtils.consume(parser));
        }
        if (token.type == LexerTokenType.IDENTIFIER && token.text.matches("v\\d+")) {
            return parseVstring(parser, TokenUtils.consume(parser).text, parser.tokenIndex);
        }
        return null;
    }
}
