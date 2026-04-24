package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.perlmodule.Universal;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.frontend.lexer.LexerTokenType.*;
import static org.perlonjava.frontend.parser.NumberParser.parseNumber;
import static org.perlonjava.frontend.parser.ParserNodeUtils.*;
import static org.perlonjava.frontend.parser.SubroutineParser.consumeAttributes;
import static org.perlonjava.frontend.parser.TokenUtils.consume;
import static org.perlonjava.frontend.parser.TokenUtils.peek;

/**
 * This class provides methods for parsing various Perl operators and constructs.
 */
public class OperatorParser {

    /**
     * Parses the 'do' operator.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed 'do' operator.
     */
    static Node parseDoOperator(Parser parser) {
        LexerToken token;
        Node block;
        // Handle 'do' keyword which can be followed by a block or filename
        token = TokenUtils.peek(parser);
        if (token.type == OPERATOR && token.text.equals("{")) {
            TokenUtils.consume(parser, OPERATOR, "{");
            boolean parsingTakeReference = parser.parsingTakeReference;
            parser.parsingTakeReference = false;
            block = ParseBlock.parseBlock(parser);
            parser.parsingTakeReference = parsingTakeReference;
            TokenUtils.consume(parser, OPERATOR, "}");
            return block;
        }
        // `do` file
        Node operand = ListParser.parseZeroOrOneList(parser, 1);
        return new OperatorNode("doFile", operand, parser.tokenIndex);
    }

    /**
     * Parses the 'eval' operator.
     *
     * @param parser The Parser instance.
     * @return An AbstractNode representing the parsed 'eval' operator.
     */
    static AbstractNode parseEval(Parser parser, String operator) {
        Node block;
        Node operand;
        LexerToken token;
        // Handle 'eval' keyword which can be followed by a block or an expression
        token = TokenUtils.peek(parser);
        var index = parser.tokenIndex;
        if (token.type == OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            TokenUtils.consume(parser, OPERATOR, "{");
            // Set subroutine context to "(eval)" BEFORE parsing the block
            // This ensures source locations are saved with the correct context
            String previousSubroutine = parser.ctx.symbolTable.getCurrentSubroutine();
            parser.ctx.symbolTable.setCurrentSubroutine("(eval)");
            try {
                block = ParseBlock.parseBlock(parser);
            } finally {
                parser.ctx.symbolTable.setCurrentSubroutine(previousSubroutine);
            }
            TokenUtils.consume(parser, OPERATOR, "}");
            // Perl semantics: eval BLOCK behaves like a bare block for loop control.
            // `last/next/redo` inside the eval block must target the eval block itself,
            // not escape as non-local control flow.
            if (block instanceof BlockNode blockNode) {
                blockNode.isLoop = true;
            }
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            // Use name "(eval)" so caller() reports this as an eval block (Perl behavior)
            return new BinaryOperatorNode("->",
                    new SubroutineNode("(eval)", null, null, block, true, parser.tokenIndex), ParserNodeUtils.atUnderscoreArgs(parser), index);
        } else {
            // Otherwise, parse an expression, and default to $_
            operand = ListParser.parseZeroOrOneList(parser, 0);
            if (((ListNode) operand).elements.isEmpty()) {
                // create `$_` variable
                operand = ParserNodeUtils.scalarUnderscore(parser);
            }
        }
        return new EvalOperatorNode(
                operator,
                operand,
                parser.ctx.symbolTable.snapShot(), // Freeze the scoped symbol table for the eval context
                index);
    }

    /**
     * Parses the diamond operator (<>).
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A Node representing the parsed diamond operator.
     */
    static Node parseDiamondOperator(Parser parser, LexerToken token) {
        // Save the current token index to restore later if needed
        int currentTokenIndex = parser.tokenIndex;
        if (token.text.equals("<")) {
            LexerToken operand = parser.tokens.get(parser.tokenIndex);
            String tokenText = operand.text;

            // Check if the token looks like a Bareword file handle
            if (operand.type == IDENTIFIER) {
                Node fileHandle = FileHandle.parseFileHandle(parser);
                if (fileHandle != null) {
                    if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                        TokenUtils.consume(parser); // Consume the '>' token
                        // Return a BinaryOperatorNode representing a readline operation
                        BinaryOperatorNode readlineNode = new BinaryOperatorNode("readline",
                                fileHandle,
                                new ListNode(parser.tokenIndex), parser.tokenIndex);
                        // Annotate with handle name for error messages (e.g., "FILE")
                        if (fileHandle instanceof IdentifierNode idNode) {
                            String name = idNode.name;
                            int colonIdx = name.lastIndexOf("::");
                            if (colonIdx >= 0 && colonIdx + 2 < name.length()) {
                                name = name.substring(colonIdx + 2);
                            }
                            readlineNode.setAnnotation("handleName", name);
                        }
                        return readlineNode;
                    }
                }
            }

            // Check if the token is a dollar sign, indicating a variable
            if (tokenText.equals("$")) {
                // Handle the case for <$fh>
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;
                Node var = Variable.parseVariable(parser, "$"); // Parse the variable following the dollar sign
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("diamond operator var " + var);

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    BinaryOperatorNode readlineNode = new BinaryOperatorNode("readline",
                            var,
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                    // Annotate with handle name for error messages (e.g., "$f", "$fh")
                    if (var instanceof OperatorNode opNode && opNode.operator.equals("$")
                            && opNode.operand instanceof IdentifierNode idNode) {
                        readlineNode.setAnnotation("handleName", "$" + idNode.name);
                    }
                    return readlineNode;
                }
            }

            // Restore the token index
            parser.tokenIndex = currentTokenIndex;

            // Check if the token is one of the standard input sources
            if (tokenText.equals("STDIN") || tokenText.equals("DATA") || tokenText.equals("ARGV")) {
                // Handle the case for <STDIN>, <DATA>, or <ARGV>
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    BinaryOperatorNode readlineNode = new BinaryOperatorNode("readline",
                            new IdentifierNode("main::" + tokenText, currentTokenIndex),
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                    // Annotate with handle name for error messages
                    readlineNode.setAnnotation("handleName", tokenText);
                    return readlineNode;
                }
            }
        }
        // Restore the token index
        parser.tokenIndex = currentTokenIndex;

        if (token.text.equals("<<")) {
            String tokenText = parser.tokens.get(parser.tokenIndex).text;
            if (!tokenText.equals(">>")) {
                return ParseHeredoc.parseHeredoc(parser, tokenText);
            }
        }

        // Handle other cases like <>, <<>>, or <*.*> by parsing as a raw string
        return StringParser.parseRawString(parser, token.text);
    }


    static BinaryOperatorNode parsePrint(Parser parser, LexerToken token, int currentIndex) {
        Node handle;
        ListNode operand;

        parser.debugHeredocState("PRINT_START");

        try {
            operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, true, false);
            parser.debugHeredocState("PRINT_PARSE_SUCCESS");
        } catch (PerlCompilerException e) {
            parser.debugHeredocState("PRINT_BEFORE_BACKTRACK");
            // print $fh (1,2,3)
            parser.tokenIndex = currentIndex;
            parser.debugHeredocState("PRINT_AFTER_BACKTRACK");

            boolean paren = false;
            if (peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                paren = true;
            }

            parser.parsingForLoopVariable = true;
            Node var = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
            operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
            operand.handle = var;

            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parsePrint: " + operand.handle + " : " + operand);
        }

        handle = operand.handle;
        operand.handle = null;
        if (handle == null) {
            // `print` without arguments means `print to last selected filehandle`
            handle = new OperatorNode("select", new ListNode(currentIndex), currentIndex);
        }
        if (operand.elements.isEmpty()) {
            // `print` without arguments means `print $_`
            operand.elements.add(
                    ParserNodeUtils.scalarUnderscore(parser)
            );
        }
        return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
    }

    /**
     * Check if a variable name refers to a forced-global variable that cannot
     * be lexicalized with 'my' or 'state'.
     *
     * Perl rule: the following are always global:
     * - $_, @_, %_ (the underscore variables, since Perl 5.30)
     * - $0, $1, $2, ... (digit-only names)
     * - $!, $/, $@, $;, $,, $., $|, etc. (single punctuation character names)
     * - $^W, $^H, etc. (control character / caret variable names)
     */
    private static boolean isGlobalOnlyVariable(String name) {
        if (name == null || name.isEmpty()) return false;

        // Underscore: $_, @_, %_ are all forced global (since Perl 5.30)
        if (name.equals("_")) return true;

        // Digit-only names: $0, $1, $2, ...
        boolean allDigits = true;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return true;

        // Single ASCII non-alphanumeric, non-underscore character: $!, $/, $@, $;, etc.
        // Only check ASCII range — Unicode characters (>= 128) may be valid identifiers
        // even if Java's Character.isLetterOrDigit() doesn't recognize them.
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c < 128 && !Character.isLetterOrDigit(c) && c != '_') return true;
        }

        // Control character prefix (caret variables like $^W stored as chr(23))
        if (name.charAt(0) < 32) return true;

        return false;
    }

    /**
     * Format a variable name for display in error messages.
     * Converts internal control character representation back to ^X form.
     * E.g., chr(23) + "" becomes "^W", chr(8) + "MATCH" becomes "^HMATCH".
     */
    private static String formatVarNameForDisplay(String name) {
        if (name == null || name.isEmpty()) return name;
        char first = name.charAt(0);
        if (first < 32) {
            // Control character: convert to ^X notation
            return "^" + (char) (first + 'A' - 1) + name.substring(1);
        }
        return name;
    }

    private static void addVariableToScope(EmitterContext ctx, String operator, OperatorNode node) {
        String sigil = node.operator;
        if ("$@%".contains(sigil)) {
            // not "undef"
            Node identifierNode = node.operand;
            if (identifierNode instanceof IdentifierNode) { // my $a
                String name = ((IdentifierNode) identifierNode).name;
                String var = sigil + name;

                // Check for global-only variables in my/state declarations
                // Perl: "Can't use global $0 in "my""
                if ((operator.equals("my") || operator.equals("state"))
                        && isGlobalOnlyVariable(name)) {
                    throw new PerlCompilerException(
                            node.getIndex(),
                            "Can't use global " + sigil + formatVarNameForDisplay(name)
                                    + " in \"" + operator + "\"",
                            ctx.errorUtil
                    );
                }

                // Check for redeclaration warnings
                if (operator.equals("our")) {
                    // For 'our', only warn if redeclared in the same package (matching Perl behavior)
                    if (ctx.symbolTable.isOurVariableRedeclaredInSamePackage(var)) {
                        System.err.println(
                                ctx.errorUtil.errorMessage(node.getIndex(),
                                        "\"our\" variable " + var + " redeclared"));
                    }
                } else {
                    // For 'my'/'local', warn if redeclared in the same scope
                    if (ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                        System.err.println(
                                ctx.errorUtil.errorMessage(node.getIndex(),
                                        "\"" + operator + "\" variable " + var + " masks earlier declaration in same scope"));
                    }
                }
                
                int varIndex = ctx.symbolTable.addVariable(var, operator, node);
                // Note: the isDeclaredReference flag is stored in node.annotations
                // and will be used during code generation
            }
        }
    }

    static OperatorNode parseVariableDeclaration(Parser parser, String operator, int currentIndex) {

        String varType = null;
        if (peek(parser).type == IDENTIFIER) {
            String tokenText = peek(parser).text;

            // Handle 'my __PACKAGE__ $var' and 'my __CLASS__ $var' syntax.
            // __PACKAGE__ is a compile-time constant that resolves to the current
            // package name when used as a type annotation in variable declarations.
            if (tokenText.equals("__PACKAGE__") || tokenText.equals("__CLASS__")) {
                TokenUtils.consume(parser); // consume __PACKAGE__/__CLASS__
                varType = parser.ctx.symbolTable.getCurrentPackage();
            } else {
                // If a package name follows, then it is a type declaration.
                // In Perl, `my Foo::Bar $x` is valid when Foo::Bar is loaded.
                // We accept the type name at parse time when unambiguously followed
                // by a sigil ($, @, %, \) or opening paren, and defer the "No such class"
                // check to runtime — matching Perl's behavior while handling the JVM
                // architectural difference (entire file compiled before execution).
                int currentIndex2 = parser.tokenIndex;
                String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
                LexerToken afterType = peek(parser);
                boolean followedBySigil = "$".equals(afterType.text) || "@".equals(afterType.text)
                        || "%".equals(afterType.text) || "\\".equals(afterType.text)
                        || "(".equals(afterType.text);
                if (followedBySigil) {
                    // Unambiguously a type annotation (followed by a variable sigil or paren list)
                    varType = packageName;
                } else if (GlobalVariable.isPackageLoaded(packageName)) {
                    varType = packageName;
                } else {
                    // Backtrack
                    parser.tokenIndex = currentIndex2;
                }
            }
        }

        // Check if this is a declared reference (my \$x, our \@array, etc.)
        boolean isDeclaredReference = false;
        if (peek(parser).type == OPERATOR && peek(parser).text.equals("\\")) {
            isDeclaredReference = true;

            // Check if declared_refs feature is enabled
            if (!parser.ctx.symbolTable.isFeatureCategoryEnabled("declared_refs")) {
                throw new PerlCompilerException(
                        currentIndex,
                        "The experimental declared_refs feature is not enabled",
                        parser.ctx.errorUtil
                );
            }

            // Emit experimental warning if warnings are enabled
            if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::declared_refs")) {
                // Use WarnDie.warn to respect $SIG{__WARN__} handler
                try {
                    WarnDie.warn(
                            new RuntimeScalar("Declaring references is experimental"),
                            new RuntimeScalar(parser.ctx.errorUtil.warningLocation(currentIndex))
                    );
                } catch (Exception e) {
                    // If warning system isn't initialized yet, fall back to System.err
                    System.err.println("Declaring references is experimental" + parser.ctx.errorUtil.warningLocation(currentIndex) + ".");
                }
            }

            TokenUtils.consume(parser, OPERATOR, "\\");
        }

        // Create OperatorNode ($, @, %), ListNode (includes undef), SubroutineNode
        // Suppress strict vars check while parsing the variable being declared
        boolean savedParsingDeclaration = parser.parsingDeclaration;
        parser.parsingDeclaration = true;
        Node operand = ParsePrimary.parsePrimary(parser);
        parser.parsingDeclaration = savedParsingDeclaration;
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parseVariableDeclaration " + operator + ": " + operand + " (ref=" + isDeclaredReference + ")");

        // Add variables to the scope
        if (operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            List<Node> transformedElements = new ArrayList<>();
            boolean hasTransformation = false;

            for (int i = 0; i < listNode.elements.size(); i++) {
                Node element = listNode.elements.get(i);
                if (element instanceof OperatorNode operandNode) {
                    // Check if this element is a reference operator (backslash)
                    // This handles cases like my(\$x) where the backslash is inside the parentheses
                    if (operandNode.operator.equals("\\") && operandNode.operand instanceof OperatorNode varNode) {
                        // This is a declared reference inside parentheses: my(\$x), my(\@arr), my(\%hash)

                        // Check if declared_refs feature is enabled
                        if (!parser.ctx.symbolTable.isFeatureCategoryEnabled("declared_refs")) {
                            throw new PerlCompilerException(
                                    operandNode.tokenIndex,
                                    "The experimental declared_refs feature is not enabled",
                                    parser.ctx.errorUtil
                            );
                        }

                        // Emit experimental warning if warnings are enabled
                        if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::declared_refs")) {
                            // Use WarnDie.warn to respect $SIG{__WARN__} handler
                            try {
                                WarnDie.warn(
                                        new RuntimeScalar("Declaring references is experimental"),
                                        new RuntimeScalar(parser.ctx.errorUtil.warningLocation(operandNode.tokenIndex))
                                );
                            } catch (Exception e) {
                                // If warning system isn't initialized yet, fall back to System.err
                                System.err.println("Declaring references is experimental" + parser.ctx.errorUtil.warningLocation(operandNode.tokenIndex) + ".");
                            }
                        }

                        // Declared references always create scalar variables
                        // Convert the variable to a scalar if it's an array or hash
                        OperatorNode scalarVarNode = varNode;
                        if (varNode.operator.equals("@") || varNode.operator.equals("%")) {
                            // Create a scalar version of the variable
                            scalarVarNode = new OperatorNode("$", varNode.operand, varNode.tokenIndex);
                        }
                        scalarVarNode.setAnnotation("isDeclaredReference", true);
                        scalarVarNode.setAnnotation("declaredReferenceOriginalSigil", varNode.operator);
                        addVariableToScope(parser.ctx, operator, scalarVarNode);
                        // Also mark the original nodes
                        varNode.setAnnotation("isDeclaredReference", true);
                        operandNode.setAnnotation("isDeclaredReference", true);

                        // Transform the AST: replace \@arr with $arr in the list
                        transformedElements.add(scalarVarNode);
                        hasTransformation = true;
                    } else {
                        if (isDeclaredReference) {
                            operandNode.setAnnotation("isDeclaredReference", true);
                        }
                        addVariableToScope(parser.ctx, operator, operandNode);
                        transformedElements.add(element);
                    }
                } else {
                    transformedElements.add(element);
                }
            }

            // If we transformed any elements, replace the list elements
            if (hasTransformation) {
                listNode.elements.clear();
                listNode.elements.addAll(transformedElements);
            }
        } else if (operand instanceof OperatorNode operandNode) {

            if (operator.equals("state")) {
                // Give the variable a persistent id (See: PersistentVariable.java)
                if (operandNode.id == 0) {
                    operandNode.id = EmitterMethodCreator.classCounter++;
                }
            }

            if (isDeclaredReference) {
                operandNode.setAnnotation("isDeclaredReference", true);
            }
            addVariableToScope(parser.ctx, operator, operandNode);
        }

        OperatorNode decl = new OperatorNode(operator, operand, currentIndex);
        if (isDeclaredReference) {
            decl.setAnnotation("isDeclaredReference", true);
        }
        if (varType != null) {
            decl.setAnnotation("varType", varType);
        }

        // Initialize a list to store any attributes the declaration might have.
        List<String> attributes = new ArrayList<>();
        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        //
        // But the ':' may also belong to an enclosing ternary expression — e.g.
        // `COND ? my $var : $fallback`. We disambiguate by looking past the ':':
        //   - IDENTIFIER       → attribute name                 → parse
        //   - `=` `;` `,` `)`  → empty attribute list           → parse (consume ':')
        //   - anything else    → looks like a ternary alt       → break
        //
        // The look-ahead scans the raw tokens array and does not mutate
        // parser.tokenIndex so the rollback is always exact.
        while (peek(parser).text.equals(":")) {
            int lookIdx = parser.tokenIndex + 1;
            while (lookIdx < parser.tokens.size()
                    && parser.tokens.get(lookIdx).type == WHITESPACE) {
                lookIdx++;
            }
            if (lookIdx >= parser.tokens.size()) break;
            LexerToken after = parser.tokens.get(lookIdx);
            boolean looksLikeAttr =
                    after.type == IDENTIFIER
                            || after.text.equals("=")
                            || after.text.equals(";")
                            || after.text.equals(",")
                            || after.text.equals(")");
            if (!looksLikeAttr) {
                break;
            }
            consumeAttributes(parser, attributes);
        }

        // Detect scalar/array/hash dereferences in my/our/state declarations.
        // E.g., "our ${""}", "my $$foo" — these are dereferences, not simple variables.
        // Perl 5: "Can't declare scalar dereference in 'our'" etc.
        if (!attributes.isEmpty() || peek(parser).text.equals("=") || peek(parser).text.equals(";")) {
            checkForDereference(parser, operator, operand);
        }

        if (!attributes.isEmpty()) {
            // Determine the package for MODIFY_*_ATTRIBUTES lookup
            String attrPackage = varType != null ? varType : parser.ctx.symbolTable.getCurrentPackage();
            
            // Validate and dispatch variable attributes.
            // For 'our': dispatch at compile time (global vars already exist).
            // For 'my'/'state': validate at compile time, dispatch at runtime
            //   (the actual lexical variable doesn't exist yet during parsing).
            callModifyVariableAttributes(parser, attrPackage, operator, operand, attributes);

            // Add the attributes and package to the operand annotations
            // so the emitter can dispatch at runtime for my/state variables.
            java.util.Map<String, Object> newAnnotations;
            if (decl.annotations != null) {
                newAnnotations = new java.util.HashMap<>(decl.annotations);
            } else {
                newAnnotations = new java.util.HashMap<>();
            }
            newAnnotations.put("attributes", attributes);
            newAnnotations.put("attributePackage", attrPackage);
            decl.annotations = newAnnotations;
        }

        return decl;
    }

    /**
     * Check if a variable in a my/our/state declaration is actually a dereference.
     * E.g., "our ${""}", "my $$foo" — Perl 5 errors with:
     * "Can't declare scalar dereference in 'our'" etc.
     */
    private static void checkForDereference(Parser parser, String operator, Node operand) {
        if (!(operand instanceof OperatorNode opNode)) return;
        String sigil = opNode.operator;
        if (!"$@%".contains(sigil)) return;

        // A simple variable has IdentifierNode as operand.
        // A dereference has OperatorNode, BlockNode, etc.
        if (opNode.operand instanceof IdentifierNode) return;

        String typeName = switch (sigil) {
            case "$" -> "scalar";
            case "@" -> "array";
            case "%" -> "hash";
            default -> "scalar";
        };
        throw new PerlCompilerException(
                opNode.tokenIndex,
                "Can't declare " + typeName + " dereference in \"" + operator + "\"",
                parser.ctx.errorUtil
        );
    }

    static OperatorNode parseOperatorWithOneOptionalArgument(Parser parser, LexerToken token) {
        Node operand;
        // Handle operators with one optional argument
        String text = token.text;
        operand = ListParser.parseZeroOrOneList(parser, 0);
        if (((ListNode) operand).elements.isEmpty()) {
            switch (text) {
                case "sleep":
                    operand = new NumberNode(Long.toString(Long.MAX_VALUE), parser.tokenIndex);
                    break;
                case "pop":
                case "shift":
                    // create `@_` variable
                    // in main program, use `@ARGV`
                    boolean isSub = parser.ctx.symbolTable.isInSubroutineBody();
                    operand = isSub ? atUnderscore(parser) : atArgv(parser);
                    break;
                case "localtime":
                case "gmtime":
                case "caller":
                case "reset":
                case "select":
                    // default to empty list
                    break;
                case "srand":
                    operand = new OperatorNode("undef", null, parser.tokenIndex);
                    break;
                case "exit":
                    // create "0"
                    operand = new NumberNode("0", parser.tokenIndex);
                    break;
                case "undef":
                    operand = null;
                    break;  // leave it empty
                case "rand":
                    // create "1"
                    operand = new NumberNode("1", parser.tokenIndex);
                    break;
                default:
                    // create `$_` variable
                    operand = ParserNodeUtils.scalarUnderscore(parser);
                    break;
            }
        }
        return new OperatorNode(text, operand, parser.tokenIndex);
    }

    public static OperatorNode parseSelect(Parser parser, LexerToken token, int currentIndex) {
        // Handle 'select' operator with two different syntaxes:
        // 1. select FILEHANDLE or select (returns/sets current filehandle)
        // 2. select RBITS,WBITS,EBITS,TIMEOUT (syscall)
        ListNode listNode1 = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        int argCount = listNode1.elements.size();
        if (argCount == 1) {
            // select FILEHANDLE
            if (listNode1.elements.getFirst() instanceof IdentifierNode identifierNode) {
                // Autovivify the filehandle IO slot so parseBarewordHandle succeeds
                GlobalVariable.getGlobalIO(FileHandle.normalizeBarewordHandle(parser, identifierNode.name));
                Node handle = FileHandle.parseBarewordHandle(parser, identifierNode.name);
                if (handle != null) {
                    // handle is Bareword
                    listNode1.elements.set(0, handle);
                }
            }
            return new OperatorNode(token.text, listNode1, currentIndex);
        } else if (argCount == 0 || argCount == 4) {
            // select or
            // select RBITS,WBITS,EBITS,TIMEOUT (syscall version)
            return new OperatorNode(token.text, listNode1, currentIndex);
        } else {
            throw new PerlCompilerException(parser.tokenIndex,
                    "Wrong number of arguments for select: expected 0, 1, or 4, got " + argCount,
                    parser.ctx.errorUtil);
        }
    }

    static OperatorNode parseKeys(Parser parser, LexerToken token, int currentIndex) {
        String operator = token.text;
        Node operand;
        // Handle operators with a single operand
        // For scalar, values, keys, each: parse with precedence that includes postfix operators ([], {}, ->)
        // Named unary operators have precedence between 20 and 21 in Perl
        // This allows expressions like: values $hashref->%* or keys $hashref->%* or scalar((nil) x 3, 1)
        if (operator.equals("scalar") || operator.equals("values") || operator.equals("keys") || operator.equals("each")) {
            operand = parser.parseExpression(parser.getPrecedence("=~")); // precedence 20
            // Check if operand is null (no argument provided)
            if (operand == null) {
                throw new PerlCompilerException(currentIndex, "Not enough arguments for " + operator, parser.ctx.errorUtil);
            }
            // scalar can accept comma expressions like scalar((nil) x 3, 1)
            // but values/keys/each need single operand check
            if (!operator.equals("scalar")) {
                operand = ensureOneOperand(parser, token, operand);
            }
        } else {
            operand = ParsePrimary.parsePrimary(parser);
            // Check if operand is null (no argument provided)
            if (operand == null) {
                throw new PerlCompilerException(currentIndex, "Not enough arguments for " + operator, parser.ctx.errorUtil);
            }
            operand = ensureOneOperand(parser, token, operand);
        }
        return new OperatorNode(operator, operand, currentIndex);
    }

    public static Node ensureOneOperand(Parser parser, LexerToken token, Node operand) {
        if (operand instanceof ListNode listNode) {
            if (listNode.elements.size() != 1) {
                throw new PerlCompilerException(parser.tokenIndex, "Too many arguments for " + token.text, parser.ctx.errorUtil);
            }
            operand = listNode.elements.getFirst();
        }
        return operand;
    }

    static OperatorNode parseDelete(Parser parser, LexerToken token, int currentIndex) {
        Node operand;

        // Check for 'delete local' syntax
        LexerToken nextToken = peek(parser);
        if (nextToken.text.equals("local")) {
            TokenUtils.consume(parser); // consume 'local'
            parser.parsingTakeReference = true;
            operand = ListParser.parseZeroOrOneList(parser, 1);
            parser.parsingTakeReference = false;

            if (operand instanceof ListNode listNode) {
                transformCodeRefPatterns(parser, listNode, token.text);
            }

            return new OperatorNode("delete_local", operand, currentIndex);
        }

        // Handle 'delete' and 'exists' operators with special parsing context
        parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
        operand = ListParser.parseZeroOrOneList(parser, 1);
        parser.parsingTakeReference = false;

        // Handle &{string} patterns for delete/exists operators (no transformation, direct handling)
        if (operand instanceof ListNode listNode) {
            transformCodeRefPatterns(parser, listNode, token.text);
        }

        return new OperatorNode(token.text, operand, currentIndex);
    }

    static BinaryOperatorNode parseBless(Parser parser, int currentIndex) {
        // Handle 'bless' operator with special handling for class name
        Node ref;
        Node className;

        // Parse the first argument (the reference to bless)
        ListNode operand1 = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        ref = operand1.elements.get(0);

        if (operand1.elements.size() > 1) {
            // Second argument provided
            className = operand1.elements.get(1);

            // Handle bareword class names
            if (className instanceof IdentifierNode identifierNode) {
                // Convert bareword to string (like "Moo" -> StringNode("Moo")).
                // A package-literal bareword ending in "::" means the class
                // name without the trailing "::", matching standard Perl:
                //   bless $r, Foo::Bar::;   # class is "Foo::Bar", not "Foo::Bar::"
                String name = identifierNode.name;
                if (name.endsWith("::") && !name.equals("::")) {
                    name = name.substring(0, name.length() - 2);
                }
                className = new StringNode(name, currentIndex);
            } else if (className instanceof StringNode stringNode && stringNode.value.isEmpty()) {
                // default to main package if empty class name is provided
                className = new StringNode("main", currentIndex);
            }
        } else {
            // No class name provided - default to current package
            className = new StringNode(parser.ctx.symbolTable.getCurrentPackage(), currentIndex);
        }

        return new BinaryOperatorNode("bless", ref, className, currentIndex);
    }

    /**
     * Transforms &{string} patterns for defined/exists/delete operators based on standard Perl behavior.
     * - defined: transforms &{string} to \&{string} (both patterns supported)
     * - exists: keeps &{string} as-is (only &{string} supported, \&{string} should error)
     * - delete: keeps &{string} as-is (only &{string} supported, \&{string} should error)
     */
    private static void transformCodeRefPatterns(Parser parser, ListNode operand, String operator) {
        for (int i = 0; i < operand.elements.size(); i++) {
            Node element = operand.elements.get(i);

            // Check for \&{string} patterns - these should error for exists/delete
            if (element instanceof OperatorNode backslashOp &&
                    backslashOp.operator.equals("\\") &&
                    backslashOp.operand instanceof OperatorNode ampOp &&
                    ampOp.operator.equals("&") &&
                    ampOp.operand instanceof BlockNode blockNode &&
                    blockNode.elements.size() == 1 &&
                    blockNode.elements.get(0) instanceof StringNode) {

                if (operator.equals("exists") || operator.equals("delete")) {
                    throw new PerlCompilerException(operator + " argument is not a HASH or ARRAY element" +
                            (operator.equals("exists") ? " or a subroutine" : ""));
                }
                // For defined, \&{string} is allowed as-is
            }

            // Look for &{string} pattern: OperatorNode with "&" operator and BlockNode operand
            if (element instanceof OperatorNode operatorNode &&
                    operatorNode.operator.equals("&") &&
                    operatorNode.operand instanceof BlockNode blockNode &&
                    blockNode.elements.size() == 1 &&
                    blockNode.elements.get(0) instanceof StringNode stringNode) {

                // Check strict refs at parse time - but only for defined operator
                // Standard Perl allows &{string} with strict refs for exists/delete
                if (operator.equals("defined") && parser.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_REFS)) {
                    throw new PerlCompilerException("Can't use string (\"" + stringNode.value + "\") as a subroutine ref while \"strict refs\" in use");
                }

                // Don't transform &{string} patterns - handle them directly in emitter
                // This preserves the semantic difference between &{string} and \&{string}
                // For all operators (defined/exists/delete), keep &{string} as-is and handle in emitter
                // The emitter has proper logic to handle these patterns correctly
            }
        }
    }

    static OperatorNode parseDefined(Parser parser, LexerToken token, int currentIndex) {
        ListNode operand;
        // Handle 'defined' operator with special parsing context
        boolean parsingTakeReference = parser.parsingTakeReference;
        parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
        operand = ListParser.parseZeroOrOneList(parser, 0);
        parser.parsingTakeReference = parsingTakeReference;
        if (operand.elements.isEmpty()) {
            // `defined` without arguments means `defined $_`
            operand.elements.add(
                    ParserNodeUtils.scalarUnderscore(parser)
            );
        }

        // Transform &{string} patterns to \&{string} patterns for defined operator
        transformCodeRefPatterns(parser, operand, "defined");

        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseUndef(Parser parser, LexerToken token, int currentIndex) {
        ListNode operand;
        // Handle 'undef' operator with special parsing context
        // Similar to 'defined', we need to prevent &subr from being auto-called
        boolean parsingTakeReference = parser.parsingTakeReference;
        parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
        operand = ListParser.parseZeroOrOneList(parser, 0);
        parser.parsingTakeReference = parsingTakeReference;
        if (operand.elements.isEmpty()) {
            // `undef` without arguments returns undef
            return new OperatorNode(token.text, null, currentIndex);
        }

        return new OperatorNode(token.text, operand, currentIndex);
    }

    static Node parseSpecialQuoted(Parser parser, LexerToken token, int startIndex) {
        // Handle special-quoted domain-specific arguments
        String operator = token.text;
        // Skip whitespace, but not `#`
        parser.tokenIndex = startIndex;
        consume(parser);
        while (parser.tokenIndex < parser.tokens.size()) {
            LexerToken token1 = parser.tokens.get(parser.tokenIndex);
            if (token1.type == WHITESPACE || token1.type == NEWLINE) {
                parser.tokenIndex++;
            } else {
                break;
            }
        }
        return StringParser.parseRawString(parser, token.text);
    }

    static OperatorNode parseNot(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle 'not' keyword as a unary operator with an operand
        if (TokenUtils.peek(parser).text.equals("(")) {
            TokenUtils.consume(parser);
            if (TokenUtils.peek(parser).text.equals(")")) {
                operand = new OperatorNode("undef", null, currentIndex);
            } else {
                // Parentheses group a full expression; allow low-precedence operators like `and`/`or`.
                operand = parser.parseExpression(0);
            }
            TokenUtils.consume(parser, OPERATOR, ")");
            return new OperatorNode(token.text, operand, currentIndex);
        }
        operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseStat(Parser parser, LexerToken token, int currentIndex) {
        // Handle 'stat' and 'lstat' operators with special handling for `stat _`
        LexerToken nextToken = peek(parser);
        boolean paren = false;
        if (nextToken.text.equals("(")) {
            TokenUtils.consume(parser);
            nextToken = peek(parser);
            paren = true;
        }

        if (nextToken.text.equals("_")) {
            TokenUtils.consume(parser);
            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            return new OperatorNode(token.text,
                    new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
        }

        // stat/lstat: bareword filehandle (typically ALLCAPS) should be treated as a typeglob.
        // Consume it here, before generic expression parsing can turn it into a subroutine call.
        if (nextToken.type == IDENTIFIER) {
            String name = nextToken.text;
            if (name.matches("^[A-Z_][A-Z0-9_]*$")) {
                TokenUtils.consume(parser);
                // autovivify filehandle and convert to globref
                GlobalVariable.getGlobalIO(FileHandle.normalizeBarewordHandle(parser, name));
                Node fh = FileHandle.parseBarewordHandle(parser, name);
                Node operand = fh != null ? fh : new IdentifierNode(name, parser.tokenIndex);
                if (paren) {
                    TokenUtils.consume(parser, OPERATOR, ")");
                }
                return new OperatorNode(token.text, operand, currentIndex);
            }
        }

        // Parse optional single argument (or default to $_)
        // If we've already consumed '(', we must parse a full expression up to ')'.
        // Using parseZeroOrOneList here would parse without parentheses and may stop
        // at low-precedence operators like the ternary ?:, leading to parse errors.
        ListNode listNode;
        if (paren) {
            listNode = new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
        } else {
            listNode = ListParser.parseZeroOrOneList(parser, 0);
        }
        Node operand;
        if (listNode.elements.isEmpty()) {
            // No arg: default to $_ (matches existing behavior of parseOperatorWithOneOptionalArgument)
            operand = ParserNodeUtils.scalarUnderscore(parser);
        } else if (listNode.elements.size() == 1) {
            operand = listNode.elements.getFirst();
        } else {
            parser.throwError("syntax error");
            return null; // unreachable
        }

        return new OperatorNode(token.text, operand, currentIndex);
    }

    static BinaryOperatorNode parseReadline(Parser parser, LexerToken token, int currentIndex) {
        String operator = token.text;
        // Handle file-related operators with special handling for default handles
        ListNode operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        Node handle;
        if (operand.elements.isEmpty()) {
            String defaultHandle = switch (operator) {
                case "readline" -> "main::ARGV";
                case "eof", "tell" -> null;
                case "truncate" ->
                        throw new PerlCompilerException(parser.tokenIndex, "Not enough arguments for " + token.text, parser.ctx.errorUtil);
                default ->
                        throw new PerlCompilerException(parser.tokenIndex, "Unexpected value: " + token.text, parser.ctx.errorUtil);
            };
            if (defaultHandle == null) {
                handle = new OperatorNode("undef", null, currentIndex);
            } else {
                handle = new IdentifierNode(defaultHandle, currentIndex);
            }
        } else {
            handle = operand.elements.removeFirst();

            if (handle instanceof IdentifierNode idNode) {
                String name = idNode.name;
                if (name.matches("^[A-Z_][A-Z0-9_]*$")) {
                    GlobalVariable.getGlobalIO(FileHandle.normalizeBarewordHandle(parser, name));
                    Node fh = FileHandle.parseBarewordHandle(parser, name);
                    if (fh != null) {
                        handle = fh;
                    }
                }
            }
        }
        return new BinaryOperatorNode(operator, handle, operand, currentIndex);
    }

    static BinaryOperatorNode parseSplit(Parser parser, LexerToken token, int currentIndex) {
        ListNode operand;
        // Handle 'split' operator with special handling for separator
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, true);
        Node separator =
                operand.elements.isEmpty()
                        ? new StringNode(" ", currentIndex)
                        : operand.elements.removeFirst();
        if (separator instanceof OperatorNode) {
            if (((OperatorNode) separator).operator.equals("matchRegex")) {
                ((OperatorNode) separator).operator = "quoteRegex";
            }
        }
        // If no string argument provided, default to $_
        // This is needed so both JVM and bytecode backends resolve $_ correctly
        // at runtime (the bytecode backend otherwise compiles the empty ListNode
        // in scalar context, producing a spurious value instead of $_ fallback)
        if (operand.elements.isEmpty()) {
            operand.elements.add(ParserNodeUtils.scalarUnderscore(parser));
        }
        return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
    }

    static BinaryOperatorNode parseJoin(Parser parser, LexerToken token, String operatorName, int currentIndex) {
        Node separator;
        ListNode operand;
        int firstArgIndex = parser.tokenIndex;
        // Handle operators with a RuntimeList operand
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        separator = operand.elements.removeFirst();

        if (token.text.equals("push") || token.text.equals("unshift")) {
            var op = separator;
            // Unwrap my/our/local declarations to get to the underlying array
            if (op instanceof OperatorNode operatorNode && 
                    (operatorNode.operator.equals("my") || 
                     operatorNode.operator.equals("our") || 
                     operatorNode.operator.equals("local"))) {
                op = operatorNode.operand;
            }
            if (!(op instanceof OperatorNode operatorNode && operatorNode.operator.equals("@"))) {
                // Perl 5.24+: pushing/unshifting onto scalar variable or expression is forbidden
                // But literals get a different error message
                if (op instanceof OperatorNode || op instanceof BinaryOperatorNode) {
                    parser.throwError(firstArgIndex, "Experimental " + operatorName + " on scalar is now forbidden");
                }
                parser.throwError(firstArgIndex, "Type of arg 1 to " + operatorName + " must be array (not constant item)");
            }
        }

        return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
    }

    static OperatorNode parseLast(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle loop control operators
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseReturn(Parser parser, int currentIndex) {
        Node operand;
        // Handle 'return' keyword as a unary operator with an operand.
        //
        // Special case: `return ~~ EXPR` — `~~` here is the prefix
        // double-bitwise-complement (numeric-scalar idiom), not binary
        // smartmatch. parseZeroOrMoreList's looksLikeEmptyList sees `~~`
        // as an infix operator and would treat the list as empty,
        // silently dropping EXPR. Force a prefix parse in that case.
        if (TokenUtils.peek(parser).text.equals("~~")) {
            Node expr = parser.parseExpression(parser.getPrecedence(",") + 1);
            ListNode list = new ListNode(currentIndex);
            list.elements.add(expr);
            return new OperatorNode("return", list, currentIndex);
        }
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        return new OperatorNode("return", operand, currentIndex);
    }

    static OperatorNode parseGoto(Parser parser, int currentIndex) {
        Node operand;
        // Handle 'goto' keyword - operand is optional (bare `goto` is a runtime error)
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        // Always return a goto operator - the emitter handles &sub vs LABEL distinction
        return new OperatorNode("goto", operand, currentIndex);
    }

    static OperatorNode parseLocal(Parser parser, LexerToken token, int currentIndex) {
        // Check if this is a declared reference (local \$x, local \@array, etc.)
        boolean isDeclaredReference = false;
        if (peek(parser).type == OPERATOR && peek(parser).text.equals("\\")) {
            isDeclaredReference = true;

            // Check if declared_refs feature is enabled
            if (!parser.ctx.symbolTable.isFeatureCategoryEnabled("declared_refs")) {
                throw new PerlCompilerException(
                        currentIndex,
                        "The experimental declared_refs feature is not enabled",
                        parser.ctx.errorUtil
                );
            }

            // Emit experimental warning if warnings are enabled
            if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::declared_refs")) {
                // Use WarnDie.warn to respect $SIG{__WARN__} handler
                try {
                    WarnDie.warn(
                            new RuntimeScalar("Declaring references is experimental"),
                            new RuntimeScalar(parser.ctx.errorUtil.warningLocation(currentIndex))
                    );
                } catch (Exception e) {
                    // If warning system isn't initialized yet, fall back to System.err
                    System.err.println("Declaring references is experimental" + parser.ctx.errorUtil.warningLocation(currentIndex) + ".");
                }
            }

            TokenUtils.consume(parser, OPERATOR, "\\");
        }

        Node operand;
        // Handle 'local' keyword as a unary operator with an operand
        if (peek(parser).text.equals("(")) {
            operand = ParsePrimary.parsePrimary(parser);
        } else {
            operand = parser.parseExpression(parser.getPrecedence("++"));
        }

        // Check for declared references inside parentheses: local(\$x)
        if (operand instanceof ListNode listNode) {
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode operandNode) {
                    // Check if this element is a reference operator (backslash)
                    // This handles cases like local(\$x) where the backslash is inside the parentheses
                    if (operandNode.operator.equals("\\") && operandNode.operand instanceof OperatorNode) {
                        // This is a declared reference inside parentheses: local(\$x), local(\@arr), local(\%hash)

                        // Check if declared_refs feature is enabled
                        if (!parser.ctx.symbolTable.isFeatureCategoryEnabled("declared_refs")) {
                            throw new PerlCompilerException(
                                    operandNode.tokenIndex,
                                    "The experimental declared_refs feature is not enabled",
                                    parser.ctx.errorUtil
                            );
                        }

                        // Emit experimental warning if warnings are enabled
                        if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::declared_refs")) {
                            // Use WarnDie.warn to respect $SIG{__WARN__} handler
                            try {
                                WarnDie.warn(
                                        new RuntimeScalar("Declaring references is experimental"),
                                        new RuntimeScalar(parser.ctx.errorUtil.warningLocation(operandNode.tokenIndex))
                                );
                            } catch (Exception e) {
                                // If warning system isn't initialized yet, fall back to System.err
                                System.err.println("Declaring references is experimental" + parser.ctx.errorUtil.warningLocation(operandNode.tokenIndex) + ".");
                            }
                        }

                        // Mark the nodes as declared references
                        operandNode.setAnnotation("isDeclaredReference", true);
                        if (operandNode.operand instanceof OperatorNode varNode) {
                            varNode.setAnnotation("isDeclaredReference", true);
                        }
                    }
                }
            }
        }

        OperatorNode localNode = new OperatorNode(token.text, operand, currentIndex);
        if (isDeclaredReference) {
            localNode.setAnnotation("isDeclaredReference", true);
        }
        return localNode;
    }

    static OperatorNode parseReverse(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle operators with any number of arguments
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseDieWarn(Parser parser, LexerToken token, int currentIndex) {
        int dieKeywordIndex = currentIndex;  // Capture token position BEFORE parsing args
        ListNode operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        return dieWarnNode(parser, token.text, operand, dieKeywordIndex);
    }

    static OperatorNode dieWarnNode(Parser parser, String operator, ListNode args, int tokenIndex) {
        var node = new OperatorNode(operator, args, tokenIndex);
        // Use getSourceLocationAccurate to honor #line directives
        var loc = parser.ctx.errorUtil.getSourceLocationAccurate(tokenIndex);
        node.setAnnotation("line", loc.lineNumber());
        node.setAnnotation("file", loc.fileName());
        return node;
    }

    static OperatorNode parseSystem(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle `system {$program} @args`
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, true, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static BinaryOperatorNode parseBinmodeOperator(Parser parser, LexerToken token, int currentIndex) {
        ListNode operand;
        // Handle 'binmode' operator with a FileHandle and List operands
        Node handle;
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        handle = operand.elements.removeFirst();
        return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
    }

    static BinaryOperatorNode parseSeek(Parser parser, LexerToken token, int currentIndex) {
        ListNode operand;
        Node handle;
        // Handle 'seek' operator with a FileHandle and List operands
        operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
        handle = operand.elements.removeFirst();
        return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
    }

    static OperatorNode parseReadpipe(Parser parser) {
        Node operand;
        // Handle 'readpipe' operator with one optional argument
        operand = ListParser.parseZeroOrOneList(parser, 0);
        if (((ListNode) operand).elements.isEmpty()) {
            // Create `$_` variable if no argument is provided
            operand = ParserNodeUtils.scalarUnderscore(parser);
        }
        return new OperatorNode("qx", operand, parser.tokenIndex);
    }

    static OperatorNode parsePack(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle 'pack' operator with one or more arguments
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseRequire(Parser parser) {
        // Handle 'require' operator
        LexerToken token;
        // Handle 'require' keyword which can be followed by a version, bareword or filename
        token = peek(parser);
        Node operand;

        // `require` version
        if (token.type == NUMBER) {
            consume(parser);
            operand = parseNumber(parser, token);
        } else if (token.text.matches("^v\\d+$")) {
            consume(parser);
            operand = StringParser.parseVstring(parser, token.text, parser.tokenIndex);
        } else if (token.type == IDENTIFIER && !ParsePrimary.isIsQuoteLikeOperator(token.text)) {
            // `require` bareword module name - parse directly without going through expression parser
            // This avoids treating module names like "Encode" as subroutine calls when a sub
            // with the same name exists in the current package (e.g., sub Encode in Image::ExifTool)
            // But don't intercept quote-like operators like q(), qq(), etc.
            String moduleName = IdentifierParser.parseSubroutineIdentifier(parser);
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("require module name `" + moduleName + "`");
            if (moduleName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }

            // Check if module name starts with ::
            if (moduleName.startsWith("::")) {
                throw new PerlCompilerException(parser.tokenIndex, "Bareword in require must not start with a double-colon: \"" + moduleName + "\"", parser.ctx.errorUtil);
            }

            String fileName = NameNormalizer.moduleToFilename(moduleName);
            operand = ListNode.makeList(new StringNode(fileName, parser.tokenIndex));
        } else {
            // Check for the specific pattern: :: followed by identifier (which is invalid for require)
            if (token.type == OPERATOR && token.text.equals("::")) {
                // Look ahead to see if there's an identifier after ::
                int savedIndex = parser.tokenIndex;
                consume(parser); // consume ::
                LexerToken nextToken = peek(parser);
                if (nextToken.type == IDENTIFIER) {
                    // This is ::bareword which is not allowed in require
                    throw new PerlCompilerException(parser.tokenIndex, "Bareword in require must not start with a double-colon: \"" + token.text + nextToken.text + "\"", parser.ctx.errorUtil);
                }
                // Restore position if not ::identifier pattern
                parser.tokenIndex = savedIndex;
            }

            ListNode op = ListParser.parseZeroOrOneList(parser, 0);
            if (op.elements.isEmpty()) {
                // `require $_`
                op.elements.add(scalarUnderscore(parser));
                operand = op;
            } else {
                Node firstElement = op.elements.getFirst();

                if (firstElement instanceof IdentifierNode identifierNode) {
                    // `require` module
                    String moduleName = identifierNode.name;
                    if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("name `" + moduleName + "`");
                    if (moduleName == null) {
                        throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
                    }

                    // Check if module name starts with ::
                    if (moduleName.startsWith("::")) {
                        throw new PerlCompilerException(parser.tokenIndex, "Bareword in require must not start with a double-colon: \"" + moduleName + "\"", parser.ctx.errorUtil);
                    }

                    String fileName = NameNormalizer.moduleToFilename(moduleName);
                    operand = ListNode.makeList(new StringNode(fileName, parser.tokenIndex));
                } else {
                    // `require` file
                    operand = op;
                }
            }
        }
        return new OperatorNode("require", operand, parser.tokenIndex);
    }

    /**
     * Dispatch variable attributes via MODIFY_*_ATTRIBUTES at compile time.
     *
     * <p>For each variable in the declaration, checks if the package has
     * MODIFY_SCALAR_ATTRIBUTES, MODIFY_ARRAY_ATTRIBUTES, or MODIFY_HASH_ATTRIBUTES
     * and calls it. Follows the same pattern as SubroutineParser.callModifyCodeAttributes().
     */
    private static void callModifyVariableAttributes(Parser parser, String packageName,
                                                      String operator, Node operand,
                                                      List<String> attributes) {
        // Ensure attributes.pm is loaded so that attributes::get() is available
        org.perlonjava.runtime.operators.ModuleOperators.require(new RuntimeScalar("attributes.pm"));

        // Collect the variables from the declaration
        List<Node> variables = new ArrayList<>();
        if (operand instanceof ListNode listNode) {
            variables.addAll(listNode.elements);
        } else {
            variables.add(operand);
        }

        for (Node varNode : variables) {
            if (!(varNode instanceof OperatorNode opNode)) continue;
            
            // Handle declared refs: \$x, \@x, \%x — unwrap backslash to get inner sigil
            if (opNode.operator.equals("\\") && opNode.operand instanceof OperatorNode innerOp) {
                opNode = innerOp;
            }

            String sigil = opNode.operator;

            // For declared refs in parenthesized form (my (\@h) : attr), the parser
            // transforms \@h to $h and stores the original sigil in an annotation.
            if (opNode.annotations != null && opNode.annotations.containsKey("declaredReferenceOriginalSigil")) {
                sigil = (String) opNode.annotations.get("declaredReferenceOriginalSigil");
            }

            String svtype;
            switch (sigil) {
                case "$": svtype = "SCALAR"; break;
                case "@": svtype = "ARRAY"; break;
                case "%": svtype = "HASH"; break;
                default: continue;
            }

            // Filter out built-in attributes
            List<String> nonBuiltinAttrs = new ArrayList<>();
            for (String attr : attributes) {
                if ("shared".equals(attr)) {
                    // 'shared' is a no-op (no threads in PerlOnJava)
                    continue;
                }
                nonBuiltinAttrs.add(attr);
            }

            if (nonBuiltinAttrs.isEmpty()) {
                return;
            }

            // Check if the package has MODIFY_*_ATTRIBUTES
            String modifyMethod = "MODIFY_" + svtype + "_ATTRIBUTES";
            RuntimeArray canArgs = new RuntimeArray();
            RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
            RuntimeArray.push(canArgs, new RuntimeScalar(modifyMethod));

            InheritanceResolver.autoloadEnabled = false;
            RuntimeList codeList;
            try {
                codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
            } finally {
                InheritanceResolver.autoloadEnabled = true;
            }

            boolean hasHandler = codeList.size() == 1 && codeList.getFirst().getBoolean();

            if (hasHandler) {
                if (operator.equals("our")) {
                    // For 'our' variables: dispatch at compile time (global vars already exist)
                    // Get the variable name for creating a reference
                    String varName;
                    if (opNode.operand instanceof IdentifierNode identNode) {
                        varName = identNode.name;
                    } else {
                        continue;
                    }

                    // Resolve full variable name
                    String fullVarName = NameNormalizer.normalizeVariableName(varName, parser.ctx.symbolTable.getCurrentPackage());

                    // Get a reference to the global variable
                    RuntimeScalar varRef;
                    switch (sigil) {
                        case "$":
                            varRef = GlobalVariable.getGlobalVariable(fullVarName).createReference();
                            break;
                        case "@":
                            varRef = GlobalVariable.getGlobalArray(fullVarName).createReference();
                            break;
                        case "%":
                            varRef = GlobalVariable.getGlobalHash(fullVarName).createReference();
                            break;
                        default:
                            continue;
                    }

                    RuntimeScalar method = codeList.getFirst();
                    // Build args: ($package, \$var, @attributes)
                    RuntimeArray callArgs = new RuntimeArray();
                    RuntimeArray.push(callArgs, new RuntimeScalar(packageName));
                    RuntimeArray.push(callArgs, varRef);
                    for (String attr : nonBuiltinAttrs) {
                        RuntimeArray.push(callArgs, new RuntimeScalar(attr));
                    }

                    // Push caller frames so that Attribute::Handlers can find the source file/line
                    String fileName = parser.ctx.compilerOptions.fileName;
                    int lineNum = parser.ctx.errorUtil != null
                            ? parser.ctx.errorUtil.getLineNumber(parser.tokenIndex) : 0;
                    CallerStack.push(packageName, fileName, lineNum);
                    CallerStack.push(packageName, fileName, lineNum);
                    try {
                        RuntimeList result = RuntimeCode.apply(method, callArgs, RuntimeContextType.LIST);

                        // If MODIFY_*_ATTRIBUTES returns any values, they are unrecognized attributes
                        RuntimeArray resultArray = result.getArrayOfAlias();
                        if (resultArray.size() > 0) {
                            SubroutineParser.throwInvalidAttributeError(svtype, resultArray, parser);
                        }
                    } finally {
                        CallerStack.pop();
                        CallerStack.pop();
                    }
                }
                // For 'my'/'state': handler will be dispatched at runtime by the emitter,
                // after the actual lexical variable is allocated.

                // Emit "may clash with future reserved word" warning at compile time
                emitReservedWordWarning(svtype, nonBuiltinAttrs, parser);
            } else {
                // No MODIFY_*_ATTRIBUTES handler found at compile time.
                // For 'our': error immediately (global vars are compile-time).
                // For 'my'/'state': defer to runtime dispatch — the handler may
                // not be visible yet (e.g., set via glob in enclosing eval).
                if (operator.equals("our")) {
                    SubroutineParser.throwInvalidAttributeError(svtype, nonBuiltinAttrs, parser);
                }
            }
        }
    }

    /**
     * Emit "SCALAR/ARRAY/HASH package attribute(s) may clash with future reserved word(s)"
     * warning for non-built-in attributes accepted by MODIFY_*_ATTRIBUTES.
     * Respects 'no warnings "reserved"'.
     */
    private static void emitReservedWordWarning(String svtype, List<String> attrs, Parser parser) {
        if (attrs.isEmpty()) return;

        // Check compile-time warning scope directly (consistent with experimental:: checks)
        // Use "syntax::reserved" since "reserved" is an alias — use warnings enables
        // "syntax::reserved" via the all→syntax→syntax::reserved hierarchy
        if (!parser.ctx.symbolTable.isWarningCategoryEnabled("syntax::reserved")) return;

        // Only warn for all-lowercase attribute names (matching Perl 5's
        // grep { m/\A[[:lower:]]+(?:\z|\()/ } filter in attributes.pm)
        List<String> lowercaseAttrs = new ArrayList<>();
        for (String attr : attrs) {
            String baseName = attr.contains("(") ? attr.substring(0, attr.indexOf('(')) : attr;
            if (!baseName.isEmpty() && baseName.equals(baseName.toLowerCase())) {
                lowercaseAttrs.add(baseName);
            }
        }
        if (lowercaseAttrs.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lowercaseAttrs.size(); i++) {
            if (i > 0) sb.append(" : ");
            sb.append(lowercaseAttrs.get(i));
        }

        String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
        String word = lowercaseAttrs.size() > 1 ? "words" : "word";
        String attrWord = lowercaseAttrs.size() > 1 ? "attributes" : "attribute";
        String msg = svtype + " package " + attrWord + " may clash with future reserved " + word + ": "
                + sb + loc + ".\n";
        WarnDie.warn(new RuntimeScalar(msg), new RuntimeScalar());
    }
}