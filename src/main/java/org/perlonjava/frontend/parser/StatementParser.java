package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.core.Configuration;
import org.perlonjava.frontend.analysis.ExtractValueVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.operators.VersionHelper;
import org.perlonjava.runtime.perlmodule.FilterUtilCall;
import org.perlonjava.runtime.perlmodule.Universal;
import org.perlonjava.runtime.HintHashRegistry;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.NumberParser.parseNumber;
import static org.perlonjava.frontend.parser.ParserNodeUtils.atUnderscoreArgs;
import static org.perlonjava.frontend.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.frontend.parser.SpecialBlockParser.runSpecialBlock;
import static org.perlonjava.frontend.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.frontend.parser.StringParser.parseVstring;
import static org.perlonjava.runtime.operators.VersionHelper.normalizeVersion;
import static org.perlonjava.runtime.perlmodule.Feature.featureManager;
import static org.perlonjava.runtime.perlmodule.Strict.useStrict;
import static org.perlonjava.runtime.runtimetypes.WarningFlags.getLastScopeId;
import static org.perlonjava.runtime.runtimetypes.WarningFlags.clearLastScopeId;
import static org.perlonjava.runtime.perlmodule.Warnings.useWarnings;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

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
        HintHashRegistry.enterScope(); // Save compile-time %^H

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
        HintHashRegistry.exitScope(); // Restore compile-time %^H
        int postBlockHintHashId = HintHashRegistry.snapshotCurrentHintHash();

        For3Node result = new For3Node(label, true, null,
                condition, null, body, continueNode, false, false, parser.tokenIndex);
        result.setAnnotation("postBlockHintHashId", postBlockHintHashId);
        return result;
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
        HintHashRegistry.enterScope(); // Save compile-time %^H

        // Parse optional loop variable
        Node varNode = null;
        LexerToken token = TokenUtils.peek(parser); // "my" "$" "(" "CORE::my"
        if (token.type == LexerTokenType.IDENTIFIER &&
                (token.text.equals("my") || token.text.equals("our") || token.text.equals("state"))) {
            // Ensure `for my $x (...)` is parsed as a variable declaration, not as `$x`.
            // This is critical for strict-vars correctness inside the loop body.
            int declIndex = parser.tokenIndex;
            parser.parsingForLoopVariable = true;
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER);
            varNode = OperatorParser.parseVariableDeclaration(parser, token.text, declIndex);
            parser.parsingForLoopVariable = false;
        } else if (token.type == LexerTokenType.IDENTIFIER && token.text.equals("CORE")
                && parser.tokens.get(parser.tokenIndex).text.equals("CORE")
                && parser.tokens.size() > parser.tokenIndex + 1
                && parser.tokens.get(parser.tokenIndex + 1).text.equals("::")) {
            // Handle CORE::my/our/state
            TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // CORE
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "::");
            LexerToken coreOp = TokenUtils.peek(parser);
            if (coreOp.type == LexerTokenType.IDENTIFIER &&
                    (coreOp.text.equals("my") || coreOp.text.equals("our") || coreOp.text.equals("state"))) {
                int declIndex = parser.tokenIndex;
                parser.parsingForLoopVariable = true;
                TokenUtils.consume(parser, LexerTokenType.IDENTIFIER);
                varNode = OperatorParser.parseVariableDeclaration(parser, coreOp.text, declIndex);
                parser.parsingForLoopVariable = false;
            } else {
                parser.parsingForLoopVariable = true;
                varNode = ParsePrimary.parsePrimary(parser);
                parser.parsingForLoopVariable = false;
            }
        } else if (token.text.equals("$")) {
            parser.parsingForLoopVariable = true;
            varNode = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
        } else if (token.text.equals("\\")) {
            // Handle reference loop variables: for \$x (...), for \@x (...), for \%x (...)
            // We need to parse the reference manually to avoid parsePrimary trying to parse
            // the following (...) as a function call or hash subscript.
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "\\");
            parser.parsingForLoopVariable = true;
            Node operand = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
            varNode = new OperatorNode("\\", operand, parser.tokenIndex);
        }

        // If we didn't parse a loop variable, Perl expects the '(' of the for(..) header next.
        // When something else appears (e.g. a bare identifier), perl5 reports:
        //   Missing $ on loop variable ...
        if (varNode == null) {
            LexerToken afterVar = TokenUtils.peek(parser);
            if (!afterVar.text.equals("(")) {
                parser.throwCleanError("Missing $ on loop variable " + afterVar.text);
            }
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
                HintHashRegistry.exitScope(); // Restore compile-time %^H
                if (node instanceof AbstractNode an) {
                    an.setAnnotation("postBlockHintHashId", HintHashRegistry.snapshotCurrentHintHash());
                }
                return node;
            }
        }

        // 3-argument for loop
        Node node = parseThreeArgumentForLoop(parser, label, varNode, initialization);
        parser.ctx.symbolTable.exitScope(scopeIndex);
        HintHashRegistry.exitScope(); // Restore compile-time %^H
        if (node instanceof AbstractNode an) {
            an.setAnnotation("postBlockHintHashId", HintHashRegistry.snapshotCurrentHintHash());
        }
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

                    // Mark the For1Node so the emitter knows to evaluate list before local
                    // This ensures list is evaluated while $_ still has its parent scope value
                    For1Node forNode = new For1Node(label, true, varNode, initialization, body, continueNode, parser.tokenIndex);
                    forNode.needsArrayOfAlias = true;  // Signal emitter to use array of aliases
                    return new BlockNode(
                            List.of(
                                    new OperatorNode("local", varNode, parser.tokenIndex),
                                    forNode
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
        return parseIfStatementInternal(parser, true);
    }

    /**
     * Internal helper for parsing if/unless/elsif statements.
     * The enterNewScope parameter controls whether to enter a new scope.
     * For 'if' and 'unless', we enter a new scope; for 'elsif', we don't
     * (to match Perl's behavior where elsif conditions are in the same scope as the if condition).
     *
     * @param parser The Parser instance
     * @param enterNewScope Whether to enter a new scope before parsing
     * @return An IfNode representing the if/unless/elsif statement
     */
    private static Node parseIfStatementInternal(Parser parser, boolean enterNewScope) {
        LexerToken operator = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "if", "unless", "elsif"

        // Enter a new scope for 'if' and 'unless' (but not for 'elsif' which is part of the same chain)
        int scopeIndex = -1;
        if (enterNewScope) {
            scopeIndex = parser.ctx.symbolTable.enterScope();
            HintHashRegistry.enterScope(); // Save compile-time %^H
        }

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
            // Don't enter new scope for elsif - it's in the same scope as the if condition
            elseBranch = parseIfStatementInternal(parser, false);
        }

        // Exit the scope if we entered one
        if (enterNewScope) {
            parser.ctx.symbolTable.exitScope(scopeIndex);
            HintHashRegistry.exitScope(); // Restore compile-time %^H
        }

        // Use a macro to emulate Test::More SKIP blocks
        TestMoreHelper.handleSkipTest(parser, thenBranch);

        IfNode result = new IfNode(operator.text, condition, thenBranch, elseBranch, parser.tokenIndex);
        if (enterNewScope) {
            result.setAnnotation("postBlockHintHashId", HintHashRegistry.snapshotCurrentHintHash());
        }
        return result;
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
        // Suppress strict vars check for the catch variable — catch ($e) implicitly
        // declares $e as a lexical variable, similar to my $e.
        boolean savedParsingDeclaration = parser.parsingDeclaration;
        parser.parsingDeclaration = true;
        Node catchParameter = parser.parseExpression(0); // Parse the exception variable
        parser.parsingDeclaration = savedParsingDeclaration;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        // Register the catch variable in a scope so the parse-time strict vars
        // check can find it inside the catch block body.
        int catchScopeIndex = -1;
        if (catchParameter instanceof OperatorNode catchOp
                && "$@%".contains(catchOp.operator)
                && catchOp.operand instanceof IdentifierNode catchId) {
            catchScopeIndex = parser.ctx.symbolTable.enterScope();
            parser.ctx.symbolTable.addVariable(catchOp.operator + catchId.name, "my", null);
        }

        try {
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
        } finally {
            if (catchScopeIndex >= 0) {
                parser.ctx.symbolTable.exitScope(catchScopeIndex);
            }
        }
    }

    /**
     * Parses a defer statement.
     * <p>
     * defer { BLOCK }
     * <p>
     * The defer block is registered to execute when the enclosing scope exits,
     * regardless of how it exits (normal flow, return, exception, etc.).
     *
     * @param parser The Parser instance
     * @return A DeferNode representing the defer statement
     */
    public static Node parseDeferStatement(Parser parser) {
        int index = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "defer"

        // Emit experimental warning if warnings are enabled
        if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::defer")) {
            try {
                WarnDie.warn(
                        new RuntimeScalar("defer is experimental"),
                        new RuntimeScalar(parser.ctx.errorUtil.warningLocation(index))
                );
            } catch (Exception e) {
                // If warning system isn't initialized yet, fall back to System.err
                System.err.println("defer is experimental" + parser.ctx.errorUtil.warningLocation(index) + ".");
            }
        }

        // Parse the defer block
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node deferBlock = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        return new DeferNode(deferBlock, index);
    }

    /**
     * Parses a when statement (part of given/when feature from Perl 5.10).
     * <p>
     * when(COND) { BLOCK }  becomes:  if ($_ ~~ COND) { BLOCK }
     *
     * @param parser The Parser instance
     * @return A Node representing the when statement as an if statement with smartmatch
     */
    public static Node parseWhenStatement(Parser parser) {
        int index = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "when"

        // Parse the when condition (can be parenthesized or not)
        Node whenCondition;
        if (TokenUtils.peek(parser).text.equals("(")) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
            whenCondition = parser.parseExpression(0);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        } else {
            whenCondition = parser.parseExpression(0);
        }

        // Parse the when block
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode whenBlock = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Create smartmatch condition: $_ ~~ whenCondition
        Node dollarUnderscore = new OperatorNode("$",
                new IdentifierNode("_", index),
                index);
        Node smartmatchCondition = new BinaryOperatorNode("~~",
                dollarUnderscore,
                whenCondition,
                index);

        // Return as an if statement
        return new IfNode("if", smartmatchCondition, whenBlock, null, index);
    }

    /**
     * Parses a default statement (part of given/when feature from Perl 5.10).
     * <p>
     * default { BLOCK }  just returns the BLOCK (it's like an else clause)
     *
     * @param parser The Parser instance
     * @return A BlockNode representing the default block
     */
    public static Node parseDefaultStatement(Parser parser) {
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "default"

        // Parse the default block
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        BlockNode defaultBlock = ParseBlock.parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        return defaultBlock;
    }

    /**
     * Parses a given-when statement (deprecated feature from Perl 5.10).
     * <p>
     * Transforms:
     * given(EXPR) { when(COND1) { BLOCK1 } when(COND2) { BLOCK2 } default { BLOCK3 } }
     * <p>
     * Into AST equivalent of:
     * do { $_ = EXPR; when/default statements }
     * <p>
     * Where when/default are parsed as regular statements that check $_.
     * This is a pure AST transformation - no special emitter code needed.
     *
     * @param parser The Parser instance
     * @return A Node representing the given-when statement as transformed AST
     */
    public static Node parseGivenStatement(Parser parser) {
        int index = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "given"

        int scopeIndex = parser.ctx.symbolTable.enterScope();
        HintHashRegistry.enterScope(); // Save compile-time %^H
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        Node condition = parser.parseExpression(0);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        // Parse the block containing when/default statements
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Parse the entire block content as a normal block
        // This handles regular statements as well as when/default
        BlockNode blockContent = ParseBlock.parseBlock(parser);

        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        parser.ctx.symbolTable.exitScope(scopeIndex);
        HintHashRegistry.exitScope(); // Restore compile-time %^H
        int postBlockHintHashId = HintHashRegistry.snapshotCurrentHintHash();

        // Create the complete block: { $_ = EXPR; blockContent }
        List<Node> statements = new ArrayList<>();

        // $_ = condition  (use proper $_ structure)
        Node dollarUnderscore = new OperatorNode("$",
                new IdentifierNode("_", index),
                index);
        statements.add(new BinaryOperatorNode("=",
                dollarUnderscore,
                condition,
                index));

        // Add all the statements from the block
        statements.addAll(blockContent.elements);

        BlockNode givenBlock = new BlockNode(statements, index, parser);
        givenBlock.setAnnotation("postBlockHintHashId", postBlockHintHashId);
        return givenBlock;
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
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("use: " + token.text);
        boolean isNoDeclaration = token.text.equals("no");

        // Capture token index for caller() before consuming any tokens
        int useTokenIndex = parser.tokenIndex;
        
        TokenUtils.consume(parser);   // "use"
        token = TokenUtils.peek(parser);

        String fullName = null;
        String packageName = null;
        if (token.type != LexerTokenType.NUMBER && !token.text.matches("^v\\d+")) {
            if (token.type != LexerTokenType.IDENTIFIER) {
                // Not a valid module name token
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("use module: " + token);
            packageName = IdentifierParser.parseSubroutineIdentifier(parser);
            if (packageName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
            fullName = NameNormalizer.moduleToFilename(packageName);
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("use fullName: " + fullName);
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
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("use version: " + versionNode + " next:" + TokenUtils.peek(parser));
            // Extract version string using ExtractValueVisitor
            RuntimeList versionValues = ExtractValueVisitor.getValues(versionNode);
            if (!versionValues.isEmpty()) {
                // String versionString = versionValues.elements.getFirst().toString();
                // parser.ctx.logDebug("use version String: " + printable(versionString));
                versionScalar = versionValues.getFirst();
                if (packageName == null) {
                    if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("use version: check Perl version");
                    if (isNoDeclaration) {
                        // "no VERSION" fails if current Perl >= VERSION
                        VersionHelper.compareVersionNoDeclaration(
                                Configuration.getPerlVersionVString(),
                                versionScalar);
                    } else {
                        // "use VERSION" fails if current Perl < VERSION
                        VersionHelper.compareVersion(
                                Configuration.getPerlVersionVString(),
                                versionScalar,
                                "Perl");
                    }

                    if (!isNoDeclaration) {
                        // Enable/disable features based on Perl version (only for "use", not "no")
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
                            // Copy warning flags to ALL levels of the parser's symbol table
                            // This matches what's done after import() for 'use warnings'
                            java.util.BitSet currentWarnings = getCurrentScope().warningFlagsStack.peek();
                            for (int i = 0; i < parser.ctx.symbolTable.warningFlagsStack.size(); i++) {
                                parser.ctx.symbolTable.warningFlagsStack.set(i, (java.util.BitSet) currentWarnings.clone());
                            }
                        }
                    }
                }
            }
            if (packageName == null) {
                // `use` statement can terminate after Version.
                // Do not early-return here; we still want to consume an optional statement terminator
                // and return a CompilerFlagNode so lexical flag changes are applied during codegen.
            }
        }

        // Parse the parameter list
        boolean hasParentheses = TokenUtils.peek(parser).text.equals("(");
        Node list = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use statement list hasParentheses:" + hasParentheses + " ast:" + list);

        StatementResolver.parseStatementTerminator(parser);

        if (fullName != null) {
            // execute the statement immediately, using:
            // `require "fullName.pm"`

            // Setup the caller stack - use getSourceLocationAccurate to honor #line directives
            ErrorMessageUtil.SourceLocation loc = ctx.errorUtil.getSourceLocationAccurate(useTokenIndex);
            CallerStack.push(
                    ctx.symbolTable.getCurrentPackage(),
                    loc.fileName(),
                    loc.lineNumber());
            try {

                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use statement: " + fullName + " called from " + CallerStack.peek(0));

                // execute 'require(fullName)'
                RuntimeScalar ret = ModuleOperators.require(new RuntimeScalar(fullName));
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use statement return: " + ret);

                if (versionNode != null) {
                    // check module version via method dispatch (Module->VERSION(version))
                    // This must go through normal method resolution so that custom VERSION
                    // methods (e.g., sub tests::VERSION { ... }) are called.
                    if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("use version: check module version");

                    // Look up the VERSION method via can()
                    RuntimeArray canArgs = new RuntimeArray();
                    RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
                    RuntimeArray.push(canArgs, new RuntimeScalar("VERSION"));
                    RuntimeList codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);

                    if (codeList.size() == 1) {
                        RuntimeScalar code = codeList.getFirst();
                        if (code.getBoolean()) {
                            // Call the VERSION method: Module->VERSION(version)
                            RuntimeArray versionArgs = new RuntimeArray();
                            RuntimeArray.push(versionArgs, new RuntimeScalar(packageName));
                            RuntimeArray.push(versionArgs, versionScalar);
                            RuntimeCode.apply(code, versionArgs, RuntimeContextType.SCALAR);
                        } else {
                            // No VERSION method found, fall back to Universal.VERSION
                            RuntimeArray versionArgs = new RuntimeArray();
                            RuntimeArray.push(versionArgs, new RuntimeScalar(packageName));
                            RuntimeArray.push(versionArgs, versionScalar);
                            Universal.VERSION(versionArgs, RuntimeContextType.SCALAR);
                        }
                    } else {
                        // can() returned unexpected result, fall back to Universal.VERSION
                        RuntimeArray versionArgs = new RuntimeArray();
                        RuntimeArray.push(versionArgs, new RuntimeScalar(packageName));
                        RuntimeArray.push(versionArgs, versionScalar);
                        Universal.VERSION(versionArgs, RuntimeContextType.SCALAR);
                    }
                }

                // call Module->import( LIST )
                // or Module->unimport( LIST )

                // Execute the argument list immediately in LIST context
                // This is necessary for expressions like: use lib ($path =~ /^(.*)$/);
                // where the regex match must return captured groups, not just success/failure
                RuntimeList args = runSpecialBlock(parser, "BEGIN", list, RuntimeContextType.LIST);

                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use statement list: " + args);
                if (hasParentheses && args.isEmpty()) {
                    // do not import
                } else {
                    // fetch the method using `can` operator
                    String importMethod = isNoDeclaration ? "unimport" : "import";
                    RuntimeArray canArgs = new RuntimeArray();
                    RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
                    RuntimeArray.push(canArgs, new RuntimeScalar(importMethod));

                    RuntimeList codeList = null;
                    InheritanceResolver.setAutoloadEnabled(false);
                    try {
                        codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
                    } finally {
                        InheritanceResolver.setAutoloadEnabled(true);
                    }

                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use can(" + packageName + ", " + importMethod + "): " + codeList);
                    if (codeList.size() == 1) {
                        RuntimeScalar code = codeList.getFirst();
                        if (code.getBoolean()) {
                            // call the method
                            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Use call : " + importMethod + "(" + args + ")");
                            RuntimeArray importArgs = args.getArrayOfAlias();
                            RuntimeArray.unshift(importArgs, new RuntimeScalar(packageName));
                            setCurrentScope(parser.ctx.symbolTable);
                            RuntimeList res = RuntimeCode.apply(code, importArgs, RuntimeContextType.SCALAR);

                            // Handle TAILCALL with trampoline loop (for goto &sub in import methods)
                            // This is needed for Moo::Role which does: goto &Role::Tiny::import
                            while (res.isNonLocalGoto()) {
                                RuntimeControlFlowList flow = (RuntimeControlFlowList) res;
                                if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                                    RuntimeScalar codeRef = flow.getTailCallCodeRef();
                                    RuntimeArray callArgs = flow.getTailCallArgs();
                                    res = RuntimeCode.apply(codeRef, "tailcall", callArgs, RuntimeContextType.SCALAR);
                                } else {
                                    break;
                                }
                            }

                            // Check if a source filter was installed during import()
                            // If so, we need to rejoin remaining tokens, apply the filter, and re-tokenize
                            if (FilterUtilCall.wasFilterInstalled()) {
                                applySourceFilterToRemainingTokens(parser);
                            }
                        }
                    }
                }
            } finally {
                // restore the caller stack
                CallerStack.pop();
            }
        }

        // Get warning scope ID if "no warnings" was called (for runtime propagation)
        int warningScopeId = getLastScopeId();
        clearLastScopeId();

        // return the current compiler flags
        // If warningScopeId > 0, this node needs to emit runtime code for local ${^WARNING_SCOPE}
        java.util.BitSet fatalFlags = (java.util.BitSet) ctx.symbolTable.warningFatalStack.peek().clone();
        java.util.BitSet disabledFlags = (java.util.BitSet) ctx.symbolTable.warningDisabledStack.peek().clone();
        // Snapshot compile-time %^H for caller()[10] support
        int hintHashSnapshotId = HintHashRegistry.snapshotCurrentHintHash();
        CompilerFlagNode result = new CompilerFlagNode(
                (java.util.BitSet) ctx.symbolTable.warningFlagsStack.getLast().clone(),
                fatalFlags,
                disabledFlags,
                ctx.symbolTable.featureFlagsStack.getLast(),
                ctx.symbolTable.strictOptionsStack.getLast(),
                warningScopeId,
                hintHashSnapshotId,
                parser.tokenIndex);
        // Only mark as compileTimeOnly if no runtime code is needed
        if (warningScopeId == 0 && hintHashSnapshotId == 0) {
            result.setAnnotation("compileTimeOnly", true);
        }
        return result;
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
        GlobalVariable.getPackageExistsCacheMap().put(packageName, true);

        boolean isClass = token.text.equals("class");

        // Emit experimental warning for 'class' if warnings are enabled
        if (isClass && parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::class")) {
            try {
                WarnDie.warn(
                        new RuntimeScalar("class is experimental"),
                        new RuntimeScalar(parser.ctx.errorUtil.warningLocation(parser.tokenIndex))
                );
            } catch (Exception e) {
                // If warning system isn't initialized yet, fall back to System.err
                System.err.println("class is experimental" + parser.ctx.errorUtil.warningLocation(parser.tokenIndex) + ".");
            }
        }

        IdentifierNode nameNode = new IdentifierNode(packageName, parser.tokenIndex);
        OperatorNode packageNode = new OperatorNode(token.text, nameNode, parser.tokenIndex);
        packageNode.setAnnotation("isClass", isClass);

        // Register this as a Perl 5.38+ class for proper stringification
        if (isClass) {
            ClassRegistry.registerClass(packageName);
        }

        // Parse Version string and store it in the symbol table
        Node version = parseOptionalPackageVersion(parser);
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("package version: " + version);
        if (version != null) {
            // Extract the actual version value from the node
            String versionString = null;
            if (version instanceof NumberNode) {
                versionString = ((NumberNode) version).value;
            } else if (version instanceof StringNode) {
                versionString = ((StringNode) version).value;
            }
            // Store the version in the symbol table for this package
            if (versionString != null) {
                parser.ctx.symbolTable.setPackageVersion(packageName, versionString);
            }
        }

        // Parse class attributes (e.g., :isa(ParentClass))
        if (isClass) {
            parseClassAttributes(parser, packageNode);
        }

        BlockNode block = parseOptionalPackageBlock(parser, nameNode, packageNode);
        if (block != null) return block;

        StatementResolver.parseStatementTerminator(parser);
        parser.ctx.symbolTable.setCurrentPackage(nameNode.name, isClass);

        // For unit class syntax (class Name;), we need to generate a minimal class
        // with just a constructor, even though there's no block
        if (isClass) {
            // Create an empty block for the class
            BlockNode emptyBlock = new BlockNode(new ArrayList<>(), parser.tokenIndex);
            emptyBlock.elements.add(packageNode);

            // Transform it to generate constructor
            emptyBlock = ClassTransformer.transformClassBlock(emptyBlock, nameNode.name, parser);

            // Register deferred methods (constructor and any accessors)
            // Same logic as in parseOptionalPackageBlock

            // Register user-defined methods (none for unit class)
            @SuppressWarnings("unchecked")
            List<SubroutineNode> deferredMethods = (List<SubroutineNode>) emptyBlock.getAnnotation("deferredMethods");
            if (deferredMethods != null) {
                for (SubroutineNode method : deferredMethods) {
                    SubroutineParser.handleNamedSubWithFilter(parser, method.name, method.prototype,
                            method.attributes, (BlockNode) method.block, false, null);
                }
            }

            // Register generated methods (constructor and accessors)
            SubroutineNode deferredConstructor = (SubroutineNode) emptyBlock.getAnnotation("deferredConstructor");
            if (deferredConstructor != null) {
                SubroutineParser.handleNamedSubWithFilter(parser, deferredConstructor.name, deferredConstructor.prototype,
                        deferredConstructor.attributes, (BlockNode) deferredConstructor.block, true, null);
            }

            @SuppressWarnings("unchecked")
            List<SubroutineNode> deferredAccessors = (List<SubroutineNode>) emptyBlock.getAnnotation("deferredAccessors");
            if (deferredAccessors != null) {
                for (SubroutineNode accessor : deferredAccessors) {
                    SubroutineParser.handleNamedSubWithFilter(parser, accessor.name, accessor.prototype,
                            accessor.attributes, (BlockNode) accessor.block, true, null);
                }
            }

            return emptyBlock;
        }

        // Mark package declarations as not producing a return value.
        // In Perl 5, `package Foo;` is transparent for block return values:
        // `eval "42; package Foo;"` returns 42, not empty string.
        packageNode.setAnnotation("noReturnValue", true);
        return packageNode;
    }

    /**
     * Parses class attributes like :isa(ParentClass)
     *
     * @param parser      The Parser instance
     * @param packageNode The OperatorNode representing the class declaration
     */
    private static void parseClassAttributes(Parser parser, OperatorNode packageNode) {
        LexerToken token = TokenUtils.peek(parser);

        // Check for :isa attribute
        if (token.text.equals(":")) {
            TokenUtils.consume(parser); // consume ':'
            token = TokenUtils.peek(parser);

            if (token.text.equals("isa")) {
                TokenUtils.consume(parser); // consume 'isa'

                // Expect opening parenthesis
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");

                // Parse parent class name
                token = TokenUtils.peek(parser);
                if (token.type != LexerTokenType.IDENTIFIER) {
                    throw new PerlCompilerException(parser.tokenIndex,
                            "Expected class name after :isa(", parser.ctx.errorUtil);
                }

                String parentClass = TokenUtils.consume(parser).text;

                // Handle qualified class names (e.g., Parent::Class)
                while (TokenUtils.peek(parser).text.equals("::")) {
                    TokenUtils.consume(parser); // consume '::'
                    token = TokenUtils.peek(parser);
                    if (token.type != LexerTokenType.IDENTIFIER) {
                        throw new PerlCompilerException(parser.tokenIndex,
                                "Expected identifier after '::'", parser.ctx.errorUtil);
                    }
                    parentClass += "::" + TokenUtils.consume(parser).text;
                }

                // Store parent class in annotations
                packageNode.setAnnotation("parentClass", parentClass);

                // Register in FieldRegistry for field inheritance tracking
                // We'll register this after we know the class name

                // Handle optional version number using the existing version parser
                // This properly handles v-strings, floating point versions, etc.
                Node versionNode = parseOptionalPackageVersion(parser);
                if (versionNode != null) {
                    // System.err.println("DEBUG: :isa() has version requirement");
                    // Store version node for version checking
                    packageNode.setAnnotation("parentVersion", versionNode);

                    // Use the same approach as parseUseDeclaration for version checking
                    // Extract version value using ExtractValueVisitor
                    RuntimeList versionValues = ExtractValueVisitor.getValues(versionNode);
                    // System.err.println("DEBUG: ExtractValueVisitor returned " + versionValues.size() + " values");
                    if (!versionValues.isEmpty()) {
                        RuntimeScalar requiredVersion = versionValues.getFirst();
                        // System.err.println("DEBUG: Required version for " + parentClass + ": " + requiredVersion);

                        // Get the actual version of the parent class from the symbol table
                        String parentVersionStr = parser.ctx.symbolTable.getPackageVersion(parentClass);
                        // System.err.println("DEBUG: Parent " + parentClass + " version from symbol table: " + parentVersionStr);
                        if (parentVersionStr != null) {
                            // Use VersionHelper.compareVersion for consistent version checking
                            // This handles v-strings, underscores, and all version formats properly
                            RuntimeScalar parentVersion = new RuntimeScalar(parentVersionStr);

                            // System.err.println("DEBUG: Comparing versions - has: " + parentVersion + ", wants: " + requiredVersion);
                            // This will throw the appropriate exception if version is insufficient
                            VersionHelper.compareVersion(parentVersion, requiredVersion, parentClass);
                            // System.err.println("DEBUG: Version check passed!");
                        } else {
                            // System.err.println("DEBUG: No version found for parent class " + parentClass);
                        }
                    } else {
                        // System.err.println("DEBUG: ExtractValueVisitor returned empty list");
                    }
                } else {
                    // System.err.println("DEBUG: :isa() has no version requirement for " + parentClass);
                }

                // Expect closing parenthesis
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
            } else {
                // Unknown attribute - throw error for now
                throw new PerlCompilerException(parser.tokenIndex,
                        "Unknown class attribute: :" + token.text, parser.ctx.errorUtil);
            }
        }
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
            // 
            // Two-scope design:
            // 1. Outer scope (scopeIndex): Created here for the package/class block
            // 2. Inner scope (blockScopeIndex): Created by ParseBlock for the block contents
            //
            // For packages: Both scopes exit normally during parseBlock
            // For classes: Inner scope exit is delayed until after ClassTransformer
            //              so methods can capture class-level lexical variables
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            int scopeIndex = parser.ctx.symbolTable.enterScope();
            HintHashRegistry.enterScope(); // Save compile-time %^H

            boolean isClass = packageNode.getBooleanAnnotation("isClass");

            // Save the current package and class state to restore later
            String previousPackage = parser.ctx.symbolTable.getCurrentPackage();
            boolean previousPackageIsClass = parser.ctx.symbolTable.currentPackageIsClass();

            parser.ctx.symbolTable.setCurrentPackage(nameNode.name, isClass);

            // Set flag if we're entering a class block
            boolean wasInClassBlock = parser.isInClassBlock;
            if (isClass) {
                parser.isInClassBlock = true;
            }

            BlockNode block;
            int blockScopeIndex;

            try {
                if (isClass) {
                    // For classes, delay scope exit until after ClassTransformer runs
                    // This allows methods to capture class-level lexical variables
                    ParseBlock.BlockWithScope result = ParseBlock.parseBlock(parser, false);
                    block = result.block();
                    blockScopeIndex = result.scopeIndex();
                } else {
                    // For packages, exit scope normally
                    block = ParseBlock.parseBlock(parser);
                    blockScopeIndex = -1; // Already exited
                }
            } finally {
                // Always restore the isInClassBlock flag
                parser.isInClassBlock = wasInClassBlock;
            }

            // Mark as scoped so BytecodeCompiler emits PUSH_PACKAGE (not SET_PACKAGE)
            // and BlockNode.visit() brackets the block with GET_LOCAL_LEVEL/POP_LOCAL_LEVEL
            // to restore the runtime package after the block exits.
            packageNode.setAnnotation("isScoped", Boolean.TRUE);

            // Insert packageNode as first statement in block
            block.elements.addFirst(packageNode);

            // Transform class blocks
            // For classes: scope is still active, methods can capture lexicals
            // For packages: subroutines were already registered during parseBlock
            if (isClass) {
                block = ClassTransformer.transformClassBlock(block, nameNode.name, parser);

                // Register user-defined methods BEFORE exiting scope
                // This allows them to capture class-level lexicals
                @SuppressWarnings("unchecked")
                List<SubroutineNode> deferredMethods = (List<SubroutineNode>) block.getAnnotation("deferredMethods");
                if (deferredMethods != null) {
                    for (SubroutineNode method : deferredMethods) {
                        SubroutineParser.handleNamedSubWithFilter(parser, method.name, method.prototype,
                                method.attributes, (BlockNode) method.block, false, null);
                    }
                }

                // NOW exit the block scope AFTER user-defined methods are registered
                parser.ctx.symbolTable.exitScope(blockScopeIndex);

                // Register generated methods WITH filtering (skip lexical sub/method hidden variables)
                SubroutineNode deferredConstructor = (SubroutineNode) block.getAnnotation("deferredConstructor");
                if (deferredConstructor != null) {
                    SubroutineParser.handleNamedSubWithFilter(parser, deferredConstructor.name, deferredConstructor.prototype,
                            deferredConstructor.attributes, (BlockNode) deferredConstructor.block, true, null);
                }

                @SuppressWarnings("unchecked")
                List<SubroutineNode> deferredAccessors = (List<SubroutineNode>) block.getAnnotation("deferredAccessors");
                if (deferredAccessors != null) {
                    for (SubroutineNode accessor : deferredAccessors) {
                        SubroutineParser.handleNamedSubWithFilter(parser, accessor.name, accessor.prototype,
                                accessor.attributes, (BlockNode) accessor.block, true, null);
                    }
                }

                // Restore the package context after class transformation
                parser.ctx.symbolTable.setCurrentPackage(previousPackage, previousPackageIsClass);
            } else {
                // For regular packages, just restore context (scope already exited)
                parser.ctx.symbolTable.setCurrentPackage(previousPackage, previousPackageIsClass);
            }

            // Exit the outer scope (from line 644)
            parser.ctx.symbolTable.exitScope(scopeIndex);
            HintHashRegistry.exitScope(); // Restore compile-time %^H

            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            block.setAnnotation("postBlockHintHashId", HintHashRegistry.snapshotCurrentHintHash());
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
        if (token.type == LexerTokenType.IDENTIFIER && token.text.matches("v\\d+(\\.\\d+)*")) {
            return parseVstring(parser, TokenUtils.consume(parser).text, parser.tokenIndex);
        }
        return null;
    }

    /**
     * Apply source filters to remaining tokens after a use statement installed a filter.
     * <p>
     * This implements the "token rejoin and re-tokenize" approach described in the design doc:
     * 1. Rejoin remaining tokens back to source text
     * 2. Apply the installed filters
     * 3. Re-tokenize the filtered source
     * 4. Replace the remaining tokens in the parser
     *
     * @param parser The Parser instance with tokens to filter
     */
    private static void applySourceFilterToRemainingTokens(Parser parser) {
        int currentPos = parser.tokenIndex;

        // Step 1: Rejoin remaining tokens back to source text
        // Skip EOF tokens as they contain invalid characters
        StringBuilder sb = new StringBuilder();
        for (int i = currentPos; i < parser.tokens.size(); i++) {
            LexerToken token = parser.tokens.get(i);
            if (token.type != LexerTokenType.EOF) {
                sb.append(token.text);
            }
        }
        String remainingSource = sb.toString();

        // Step 2: Apply the installed filters
        String filteredSource = FilterUtilCall.applyFilters(remainingSource);

        // If the source wasn't changed, nothing to do
        if (filteredSource.equals(remainingSource)) {
            return;
        }

        if (CompilerOptions.DEBUG_ENABLED) {
            parser.ctx.logDebug("Source filter applied. Before: " + remainingSource.length() + " chars, After: " + filteredSource.length() + " chars");
        }

        // Step 3: Re-tokenize the filtered source
        Lexer lexer = new Lexer(filteredSource);
        List<LexerToken> newTokens = lexer.tokenize();

        // Step 4: Replace remaining tokens with filtered tokens
        // Keep tokens[0..currentPos-1], replace tokens from currentPos onwards with newTokens
        // Since parser.tokens is final, we modify the list in place
        int tokensToRemove = parser.tokens.size() - currentPos;
        for (int i = 0; i < tokensToRemove; i++) {
            parser.tokens.remove(parser.tokens.size() - 1);  // Remove from end
        }
        parser.tokens.addAll(newTokens);

        // Update the ErrorMessageUtil with the new token list
        parser.ctx.errorUtil.updateTokens(parser.tokens);

        // Clear the filter after applying (it's been consumed)
        FilterUtilCall.clearFilters();
    }
}
