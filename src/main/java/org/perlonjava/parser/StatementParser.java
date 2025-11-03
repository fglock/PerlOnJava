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

import java.util.ArrayList;
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

        // Register this as a Perl 5.38+ class for proper stringification
        if (isClass) {
            org.perlonjava.runtime.ClassRegistry.registerClass(packageName);
        }

        // Parse Version string and store it in the symbol table
        Node version = parseOptionalPackageVersion(parser);
        parser.ctx.logDebug("package version: " + version);
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
                            method.attributes, (BlockNode) method.block, false);
                }
            }
            
            // Register generated methods (constructor and accessors)
            SubroutineNode deferredConstructor = (SubroutineNode) emptyBlock.getAnnotation("deferredConstructor");
            if (deferredConstructor != null) {
                SubroutineParser.handleNamedSubWithFilter(parser, deferredConstructor.name, deferredConstructor.prototype,
                        deferredConstructor.attributes, (BlockNode) deferredConstructor.block, true);
            }
            
            @SuppressWarnings("unchecked")
            List<SubroutineNode> deferredAccessors = (List<SubroutineNode>) emptyBlock.getAnnotation("deferredAccessors");
            if (deferredAccessors != null) {
                for (SubroutineNode accessor : deferredAccessors) {
                    SubroutineParser.handleNamedSubWithFilter(parser, accessor.name, accessor.prototype,
                            accessor.attributes, (BlockNode) accessor.block, true);
                }
            }

            return emptyBlock;
        }

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
                    block = result.block;
                    blockScopeIndex = result.scopeIndex;
                } else {
                    // For packages, exit scope normally
                    block = ParseBlock.parseBlock(parser);
                    blockScopeIndex = -1; // Already exited
                }
            } finally {
                // Always restore the isInClassBlock flag
                parser.isInClassBlock = wasInClassBlock;
            }

            // Insert packageNode as first statement in block
            block.elements.addFirst(packageNode);

            // Transform class blocks
            // For classes: scope is still active, methods can capture lexicals
            // For packages: subroutines were already registered during parseBlock
            if (isClass) {
                block = ClassTransformer.transformClassBlock(block, nameNode.name, parser);
                
                // NOW exit the block scope before registering any methods
                parser.ctx.symbolTable.exitScope(blockScopeIndex);
                
                // Register ALL methods AFTER scope exit
                
                // Register user-defined methods WITHOUT filtering (they should capture class-level lexicals)
                @SuppressWarnings("unchecked")
                List<SubroutineNode> deferredMethods = (List<SubroutineNode>) block.getAnnotation("deferredMethods");
                if (deferredMethods != null) {
                    for (SubroutineNode method : deferredMethods) {
                        SubroutineParser.handleNamedSubWithFilter(parser, method.name, method.prototype,
                                method.attributes, (BlockNode) method.block, false);
                    }
                }
                
                // Register generated methods WITH filtering (skip lexical sub/method hidden variables)
                SubroutineNode deferredConstructor = (SubroutineNode) block.getAnnotation("deferredConstructor");
                if (deferredConstructor != null) {
                    SubroutineParser.handleNamedSubWithFilter(parser, deferredConstructor.name, deferredConstructor.prototype,
                            deferredConstructor.attributes, (BlockNode) deferredConstructor.block, true);
                }
                
                @SuppressWarnings("unchecked")
                List<SubroutineNode> deferredAccessors = (List<SubroutineNode>) block.getAnnotation("deferredAccessors");
                if (deferredAccessors != null) {
                    for (SubroutineNode accessor : deferredAccessors) {
                        SubroutineParser.handleNamedSubWithFilter(parser, accessor.name, accessor.prototype,
                                accessor.attributes, (BlockNode) accessor.block, true);
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
