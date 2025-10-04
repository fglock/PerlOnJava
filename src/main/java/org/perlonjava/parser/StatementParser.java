package org.perlonjava.parser;

import org.perlonjava.Configuration;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ExtractValueVisitor;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.mro.InheritanceResolver;
import org.perlonjava.operators.ModuleOperators;
import org.perlonjava.operators.VersionHelper;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

import java.util.List;

import static org.perlonjava.operators.VersionHelper.normalizeVersion;
import static org.perlonjava.parser.NumberParser.parseNumber;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscoreArgs;
import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.SpecialBlockParser.runSpecialBlock;
import static org.perlonjava.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.parser.StringParser.parseVstring;
import static org.perlonjava.perlmodule.Feature.featureManager;
import static org.perlonjava.perlmodule.Strict.useStrict;
import static org.perlonjava.perlmodule.Warnings.useWarnings;
import static org.perlonjava.runtime.GlobalVariable.packageExistsCache;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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

        int scopeIndex = parser.ctx.symbolTable.enterScope();

        Node condition;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        if (TokenUtils.peek(parser).text.equals(")")) {
            // Special case for `while ()` to become `while (1)`
            condition = new NumberNode("1", parser.tokenIndex);
        } else {
            condition = parser.parseExpression(0);
        }
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node body = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        Node continueNode = null;
        if (TokenUtils.peek(parser).text.equals("continue")) {
            TokenUtils.consume(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            continueNode = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }

        if (operator.text.equals("until")) {
            condition = new OperatorNode("not", condition, condition.getIndex());
        }

        parser.ctx.symbolTable.exitScope(scopeIndex);

        return new For3Node(label, true, null,
                condition, null, body, continueNode, false, false, parser.tokenIndex);
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

        int scopeIndex = parser.ctx.symbolTable.enterScope();

        // Parse optional loop variable
        Node varNode = null;
        LexerToken token = TokenUtils.peek(parser); // "my" "$" "(" "CORE::my"
        if (token.text.equals("my") || token.text.equals("our") || token.text.equals("CORE") || token.text.equals("$")) {
            parser.parsingForLoopVariable = true;
            varNode = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
        }

        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");

        // Parse the initialization part
        Node initialization = null;
        if (!TokenUtils.peek(parser).text.equals(";")) {
            if (TokenUtils.peek(parser).text.equals(")")) {
                initialization = new ListNode(parser.tokenIndex);
            } else {
                initialization = parser.parseExpression(0);
            }

            token = TokenUtils.peek(parser);
            if (token.text.equals(")")) {
                // 1-argument for loop (foreach-like)
                Node node = parseOneArgumentForLoop(parser, label, varNode, initialization);
                parser.ctx.symbolTable.exitScope(scopeIndex);
                return node;
            }
        }

        // 3-argument for loop
        Node node = parseThreeArgumentForLoop(parser, label, varNode, initialization);
        parser.ctx.symbolTable.exitScope(scopeIndex);
        return node;
    }

    /**
     * Helper method to parse a one-argument for loop (foreach-like).
     */
    private static Node parseOneArgumentForLoop(Parser parser, String label, Node varNode, Node initialization) {
        TokenUtils.consume(parser); // Consume ")"

        // Parse the body of the loop
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node body = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse optional continue block
        Node continueNode = null;
        if (TokenUtils.peek(parser).text.equals("continue")) {
            TokenUtils.consume(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            continueNode = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }

        // Use $_ as the default loop variable if not specified
        if (varNode == null) {
            varNode = scalarUnderscore(parser);  // $_
        }

        if (varNode instanceof OperatorNode operatorNode && operatorNode.operator.equals("$")) {
            if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                String identifier = identifierNode.name;
                int varIndex = parser.ctx.symbolTable.getVariableIndex("$" + identifier);
                if (varIndex == -1) {
                    // Is global variable
                    String fullName = NameNormalizer.normalizeVariableName(identifier, parser.ctx.symbolTable.getCurrentPackage());
                    identifierNode.name = fullName;

                    return new BlockNode(
                            List.of(
                                    new OperatorNode("local", varNode, parser.tokenIndex),
                                    new For1Node(label, true, varNode, initialization, body, continueNode, parser.tokenIndex)
                            ), parser.tokenIndex);
                }
            }
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
        Node body = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // 3-argument for doesn't have a continue block

        return new For3Node(label, true, initialization,
                condition, increment, body, null, false, false, parser.tokenIndex);
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
        BlockNode thenBranch = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        Node elseBranch = null;
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("else")) {
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "else"
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            elseBranch = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        } else if (token.text.equals("elsif")) {
            elseBranch = parseIfStatement(parser);
        }

        // Use a macro to emulate Test::More SKIP blocks
        TestMoreHelper.handleSkipTest(parser, thenBranch);

        return new IfNode(operator.text, condition, thenBranch, elseBranch, parser.tokenIndex);
    }

    /**
     * Parses a try-catch-finally statement.
     *
     * @param parser The Parser instance
     * @return A TryNode representing the try-catch-finally statement
     */
    public static Node parseTryStatement(Parser parser) {
        int index = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "try"

        // Parse the try block
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node tryBlock = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse the catch block
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "catch"
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        Node catchParameter = parser.parseExpression(0); // Parse the exception variable
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node catchBlock = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Parse the optional finally block
        Node finallyBlock = null;
        if (TokenUtils.peek(parser).text.equals("finally")) {
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "finally"
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            finallyBlock = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }

        return new BinaryOperatorNode("->",
                new SubroutineNode(null, null, null,
                        new BlockNode(List.of(
                                new TryNode(tryBlock, catchParameter, catchBlock, finallyBlock, index)), index),
                        false, index),
                atUnderscoreArgs(parser),
                index);
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
        int currentIndex = parser.tokenIndex;
        RuntimeScalar versionScalar = scalarUndef;
        Node versionNode = parseOptionalPackageVersion(parser);
        if (versionNode != null) {
            if (TokenUtils.peek(parser).text.equals(",")) {
                // no comma allowed after version
                versionNode = null;
                parser.tokenIndex = currentIndex; // backtrack
            }
        }
        if (versionNode != null) {
            parser.ctx.logDebug("use version: " + versionNode + " next:" + TokenUtils.peek(parser));
            // Extract version string using ExtractValueVisitor
            RuntimeList versionValues = ExtractValueVisitor.getValues(versionNode);
            if (!versionValues.isEmpty()) {
                // String versionString = versionValues.elements.getFirst().toString();
                // parser.ctx.logDebug("use version String: " + printable(versionString));
                versionScalar = versionValues.getFirst();
                if (packageName == null) {
                    parser.ctx.logDebug("use version: check Perl version");
                    VersionHelper.compareVersion(
                            new RuntimeScalar(Configuration.perlVersion),
                            versionScalar,
                            "Perl");

                    // Enable/disable features based on Perl version
                    setCurrentScope(parser.ctx.symbolTable);
                    // ":5.34"
                    String[] parts = normalizeVersion(versionScalar).split("\\.");
                    int majorVersion = Integer.parseInt(parts[0]);
                    int minorVersion = Integer.parseInt(parts[1]);

                    // If the minor version is odd, increment it to make it the next even version
                    if (minorVersion % 2 != 0) {
                        minorVersion++;
                    }

                    String closestVersion = minorVersion < 10
                            ? ":default"
                            : ":" + majorVersion + "." + minorVersion;
                    featureManager.enableFeatureBundle(closestVersion);

                    if (minorVersion >= 12) {
                        // If the specified Perl version is 5.12 or higher,
                        // strictures are enabled lexically.
                        useStrict(new RuntimeArray(
                                new RuntimeScalar("strict")), RuntimeContextType.VOID);
                    }
                    if (minorVersion >= 35) {
                        // If the specified Perl version is 5.35.0 or higher,
                        // warnings are enabled.
                        useWarnings(new RuntimeArray(
                                new RuntimeScalar("warnings"),
                                new RuntimeScalar("all")), RuntimeContextType.VOID);
                    }
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

        StatementResolver.parseStatementTerminator(parser);

        if (fullName != null) {
            // execute the statement immediately, using:
            // `require "fullName.pm"`

            // Setup the caller stack
            CallerStack.push(
                    ctx.symbolTable.getCurrentPackage(),
                    ctx.compilerOptions.fileName,
                    ctx.errorUtil.getLineNumber(parser.tokenIndex));
            try {

                ctx.logDebug("Use statement: " + fullName + " called from " + CallerStack.peek(0));

                // execute 'require(fullName)'
                RuntimeScalar ret = ModuleOperators.require(new RuntimeScalar(fullName));
                ctx.logDebug("Use statement return: " + ret);

                if (versionNode != null) {
                    // check module version
                    parser.ctx.logDebug("use version: check module version");
                    RuntimeArray args = new RuntimeArray();
                    RuntimeArray.push(args, new RuntimeScalar(packageName));
                    RuntimeArray.push(args, versionScalar);
                    Universal.VERSION(args, RuntimeContextType.SCALAR);
                }

                // call Module->import( LIST )
                // or Module->unimport( LIST )

                // Execute the argument list immediately
                RuntimeList args = runSpecialBlock(parser, "BEGIN", list);

                ctx.logDebug("Use statement list: " + args);
                if (hasParentheses && args.isEmpty()) {
                    // do not import
                } else {
                    // fetch the method using `can` operator
                    String importMethod = isNoDeclaration ? "unimport" : "import";
                    RuntimeArray canArgs = new RuntimeArray();
                    RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
                    RuntimeArray.push(canArgs, new RuntimeScalar(importMethod));

                    RuntimeList codeList = null;
                    InheritanceResolver.autoloadEnabled = false;
                    try {
                        codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
                    } finally {
                        InheritanceResolver.autoloadEnabled = true;
                    }

                    ctx.logDebug("Use can(" + packageName + ", " + importMethod + "): " + codeList);
                    if (codeList.size() == 1) {
                        RuntimeScalar code = codeList.getFirst();
                        if (code.getBoolean()) {
                            // call the method
                            ctx.logDebug("Use call : " + importMethod + "(" + args + ")");
                            RuntimeArray importArgs = args.getArrayOfAlias();
                            RuntimeArray.unshift(importArgs, new RuntimeScalar(packageName));
                            RuntimeCode.apply(code, importArgs, RuntimeContextType.SCALAR);
                        }
                    }
                }
            } finally {
                // restore the caller stack
                CallerStack.pop();
            }
        }

        // return the current compiler flags
        return new CompilerFlagNode(
                ctx.symbolTable.warningFlagsStack.getLast(),
                ctx.symbolTable.featureFlagsStack.getLast(),
                ctx.symbolTable.strictOptionsStack.getLast(),
                parser.tokenIndex);
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

        // Remember that this package exists
        packageExistsCache.put(packageName, true);

        boolean isClass = token.text.equals("class");
        IdentifierNode nameNode = new IdentifierNode(packageName, parser.tokenIndex);
        OperatorNode packageNode = new OperatorNode(token.text, nameNode, parser.tokenIndex);
        packageNode.setAnnotation("isClass", isClass);

        // Parse Version string
        // XXX use the Version string
        Node version = parseOptionalPackageVersion(parser);
        parser.ctx.logDebug("package version: " + version);

        BlockNode block = parseOptionalPackageBlock(parser, nameNode, packageNode);
        if (block != null) return block;

        StatementResolver.parseStatementTerminator(parser);
        parser.ctx.symbolTable.setCurrentPackage(nameNode.name, isClass);
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
            int scopeIndex = parser.ctx.symbolTable.enterScope();
            parser.ctx.symbolTable.setCurrentPackage(nameNode.name, packageNode.getBooleanAnnotation("isClass"));
            BlockNode block = ParseBlock.parseBlock(parser);

            // Insert packageNode as first statement in block
            block.elements.addFirst(packageNode);
            
            // Transform class blocks
            if (packageNode.getBooleanAnnotation("isClass")) {
                block = ClassTransformer.transformClassBlock(block, nameNode.name);
            }

            parser.ctx.symbolTable.exitScope(scopeIndex);
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
