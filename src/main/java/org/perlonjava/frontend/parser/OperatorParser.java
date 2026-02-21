package org.perlonjava.frontend.parser;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
            block = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, OPERATOR, "}");
            // Perl semantics: eval BLOCK behaves like a bare block for loop control.
            // `last/next/redo` inside the eval block must target the eval block itself,
            // not escape as non-local control flow.
            if (block instanceof BlockNode blockNode) {
                blockNode.isLoop = true;
            }
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new SubroutineNode(null, null, null, block, true, parser.tokenIndex), ParserNodeUtils.atUnderscoreArgs(parser), index);
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
                        return new BinaryOperatorNode("readline",
                                fileHandle,
                                new ListNode(parser.tokenIndex), parser.tokenIndex);
                    }
                }
            }

            // Check if the token is a dollar sign, indicating a variable
            if (tokenText.equals("$")) {
                // Handle the case for <$fh>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;
                Node var = Variable.parseVariable(parser, "$"); // Parse the variable following the dollar sign
                parser.ctx.logDebug("diamond operator var " + var);

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            var,
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                }
            }

            // Restore the token index
            parser.tokenIndex = currentTokenIndex;

            // Check if the token is one of the standard input sources
            if (tokenText.equals("STDIN") || tokenText.equals("DATA") || tokenText.equals("ARGV")) {
                // Handle the case for <STDIN>, <DATA>, or <ARGV>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            new IdentifierNode("main::" + tokenText, currentTokenIndex),
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
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
            parser.ctx.logDebug("parsePrint: " + operand.handle + " : " + operand);
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

    private static void addVariableToScope(EmitterContext ctx, String operator, OperatorNode node) {
        String sigil = node.operator;
        if ("$@%".contains(sigil)) {
            // not "undef"
            Node identifierNode = node.operand;
            if (identifierNode instanceof IdentifierNode) { // my $a
                String name = ((IdentifierNode) identifierNode).name;
                String var = sigil + name;
                if (ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                    System.err.println(
                            ctx.errorUtil.errorMessage(node.getIndex(),
                                    "Warning: \"" + operator + "\" variable "
                                            + var
                                            + " masks earlier declaration in same ctx.symbolTable"));
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
            // If a package name follows, then it is a type declaration
            int currentIndex2 = parser.tokenIndex;
            String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
            boolean packageExists = GlobalVariable.isPackageLoaded(packageName);
            // System.out.println("maybe type: " + packageName + " " + packageExists);
            if (packageExists) {
                varType = packageName;
            } else {
                // Backtrack
                parser.tokenIndex = currentIndex2;
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
                            new RuntimeScalar(parser.ctx.errorUtil.errorMessage(currentIndex, ""))
                    );
                } catch (Exception e) {
                    // If warning system isn't initialized yet, fall back to System.err
                    System.err.println(parser.ctx.errorUtil.errorMessage(currentIndex, "Declaring references is experimental"));
                }
            }

            TokenUtils.consume(parser, OPERATOR, "\\");
        }

        // Create OperatorNode ($, @, %), ListNode (includes undef), SubroutineNode
        Node operand = ParsePrimary.parsePrimary(parser);
        parser.ctx.logDebug("parseVariableDeclaration " + operator + ": " + operand + " (ref=" + isDeclaredReference + ")");

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
                                        new RuntimeScalar(parser.ctx.errorUtil.errorMessage(operandNode.tokenIndex, ""))
                                );
                            } catch (Exception e) {
                                // If warning system isn't initialized yet, fall back to System.err
                                System.err.println(parser.ctx.errorUtil.errorMessage(operandNode.tokenIndex, "Declaring references is experimental"));
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

        // Initialize a list to store any attributes the declaration might have.
        List<String> attributes = new ArrayList<>();
        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (peek(parser).text.equals(":")) {
            consumeAttributes(parser, attributes);
        }
        if (!attributes.isEmpty()) {
            // Add the attributes to the operand, preserving any existing annotations
            if (decl.annotations != null && decl.annotations.containsKey("isDeclaredReference")) {
                // Create a new map with both the existing isDeclaredReference and new attributes
                java.util.Map<String, Object> newAnnotations = new java.util.HashMap<>(decl.annotations);
                newAnnotations.put("attributes", attributes);
                decl.annotations = newAnnotations;
            } else {
                decl.annotations = Map.of("attributes", attributes);
            }
        }

        return decl;
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
                // Convert bareword to string (like "Moo" -> StringNode("Moo"))
                className = new StringNode(identifierNode.name, currentIndex);
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
        if (nextToken.text.equals("_")) {
            // Handle `stat _`
            TokenUtils.consume(parser);
            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            return new OperatorNode(token.text,
                    new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
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
        return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
    }

    static BinaryOperatorNode parseJoin(Parser parser, LexerToken token, String operatorName, int currentIndex) {
        Node separator;
        ListNode operand;
        // Handle operators with a RuntimeList operand
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        separator = operand.elements.removeFirst();

        if (token.text.equals("push") || token.text.equals("unshift")) {
            // assert that separator is an `@array` or `my @array`
            var op = separator;
            if (op instanceof OperatorNode operatorNode && operatorNode.operator.equals("my")) {
                op = operatorNode.operand;
            }
            if (!(op instanceof OperatorNode operatorNode && operatorNode.operator.equals("@"))) {
                parser.throwError("Type of arg 1 to " + operatorName + " must be array (not constant item)");
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
        // Handle 'return' keyword as a unary operator with an operand
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        return new OperatorNode("return", operand, currentIndex);
    }

    static OperatorNode parseGoto(Parser parser, int currentIndex) {
        Node operand;
        // Handle 'goto' keyword as a unary operator with an operand
        boolean isSubroutine = peek(parser).text.equals("&");  // goto &subr
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
        if (isSubroutine) {
            // goto &sub form
            return new OperatorNode("return", operand, currentIndex);
        }
        // goto LABEL form
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
                            new RuntimeScalar(parser.ctx.errorUtil.errorMessage(currentIndex, ""))
                    );
                } catch (Exception e) {
                    // If warning system isn't initialized yet, fall back to System.err
                    System.err.println(parser.ctx.errorUtil.errorMessage(currentIndex, "Declaring references is experimental"));
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
                                        new RuntimeScalar(parser.ctx.errorUtil.errorMessage(operandNode.tokenIndex, ""))
                                );
                            } catch (Exception e) {
                                // If warning system isn't initialized yet, fall back to System.err
                                System.err.println(parser.ctx.errorUtil.errorMessage(operandNode.tokenIndex, "Declaring references is experimental"));
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
        node.setAnnotation("line", parser.ctx.errorUtil.getLineNumberAccurate(tokenIndex));
        node.setAnnotation("file", parser.ctx.errorUtil.getFileName());
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
            parser.ctx.logDebug("require module name `" + moduleName + "`");
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
                    parser.ctx.logDebug("name `" + moduleName + "`");
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
}