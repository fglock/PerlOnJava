package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.lexer.LexerTokenType.*;
import static org.perlonjava.parser.NumberParser.parseNumber;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.SubroutineParser.consumeAttributes;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

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
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            boolean parsingTakeReference = parser.parsingTakeReference;
            parser.parsingTakeReference = false;
            block = ParseBlock.parseBlock(parser);
            parser.parsingTakeReference = parsingTakeReference;
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
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
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            block = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new SubroutineNode(null, null, null, block, true, parser.tokenIndex), new ListNode(parser.tokenIndex), index);
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

        // Create OperatorNode ($, @, %), ListNode (includes undef), SubroutineNode
        Node operand = ParsePrimary.parsePrimary(parser);
        parser.ctx.logDebug("parseVariableDeclaration " + operator + ": " + operand);

        // Add variables to the scope
        if (operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode operandNode) {
                    addVariableToScope(parser.ctx, operator, operandNode);
                }
            }
        } else if (operand instanceof OperatorNode operandNode) {

            if (operator.equals("state")) {
                // Give the variable a persistent id (See: PersistentVariable.java)
                if (operandNode.id == 0) {
                    operandNode.id = EmitterMethodCreator.classCounter++;
                }
            }

            addVariableToScope(parser.ctx, operator, operandNode);
        }

        OperatorNode decl = new OperatorNode(operator, operand, currentIndex);

        // Initialize a list to store any attributes the declaration might have.
        List<String> attributes = new ArrayList<>();
        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (peek(parser).text.equals(":")) {
            consumeAttributes(parser, attributes);
        }
        if (!attributes.isEmpty()) {
            // Add the attributes to the operand
            decl.annotations = Map.of("attributes", attributes);
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
                    // XXX in main program, use `@ARGV`
                    operand = atUnderscore(parser);
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
        operand = ParsePrimary.parsePrimary(parser);
        // scalar() can have more operands if they are inside parenthesis
        if (!operator.equals("scalar")) {
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
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseUnpack(Parser parser, LexerToken token) {
        Node operand;
        // Handle 'unpack' operator with one mandatory and one optional argument
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        if (((ListNode) operand).elements.size() == 1) {
            // Create `$_` variable if only one argument is provided
            ((ListNode) operand).elements.add(
                    ParserNodeUtils.scalarUnderscore(parser)
            );
        }
        return new OperatorNode(token.text, operand, parser.tokenIndex);
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

    static OperatorNode parseDefined(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle 'defined' operator with special parsing context
        boolean parsingTakeReference = parser.parsingTakeReference;
        parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
        operand = ListParser.parseZeroOrOneList(parser, 0);
        parser.parsingTakeReference = parsingTakeReference;
        if (((ListNode) operand).elements.isEmpty()) {
            // `defined` without arguments means `defined $_`
            ((ListNode) operand).elements.add(
                    ParserNodeUtils.scalarUnderscore(parser)
            );
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
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
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
            // Handle `stat _`
            TokenUtils.consume(parser);
            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            return new OperatorNode(token.text,
                    new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
        }
        parser.tokenIndex = currentIndex;
        return parseOperatorWithOneOptionalArgument(parser, token);
    }

    static BinaryOperatorNode parseReadline(Parser parser, LexerToken token, int currentIndex) {
        String operator = token.text;
        // Handle file-related operators with special handling for default handles
        ListNode operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        Node handle;
        if (operand.elements.isEmpty()) {
            String defaultHandle = switch (operator) {
                case "readline" -> "main::ARGV";
                case "eof" -> "main::STDIN";
                case "tell" -> "main::^LAST_FH";
                case "fileno" ->
                        throw new PerlCompilerException(parser.tokenIndex, "Not enough arguments for " + token.text, parser.ctx.errorUtil);
                default ->
                        throw new PerlCompilerException(parser.tokenIndex, "Unexpected value: " + token.text, parser.ctx.errorUtil);
            };
            handle = new IdentifierNode(defaultHandle, currentIndex);
        } else {
            handle = operand.elements.removeFirst();
        }
        return new BinaryOperatorNode(operator, handle, operand, currentIndex);
    }

    static BinaryOperatorNode parseSplit(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle 'split' operator with special handling for separator
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, true);
        Node separator =
                ((ListNode) operand).elements.isEmpty()
                        ? new StringNode(" ", currentIndex)
                        : ((ListNode) operand).elements.removeFirst();
        if (separator instanceof OperatorNode) {
            if (((OperatorNode) separator).operator.equals("matchRegex")) {
                ((OperatorNode) separator).operator = "quoteRegex";
            }
        }
        return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
    }

    static BinaryOperatorNode parseJoin(Parser parser, LexerToken token, String operatorName, int currentIndex) {
        Node separator;
        Node operand;
        // Handle operators with a RuntimeList operand
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        separator = ((ListNode) operand).elements.removeFirst();

        if (token.text.equals("push") || token.text.equals("unshift")) {
            // assert that separator is an array
            if (!(separator instanceof OperatorNode operatorNode && operatorNode.operator.equals("@"))) {
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
        Node operand;
        // Handle 'local' keyword as a unary operator with an operand
        if (peek(parser).text.equals("(")) {
            operand = ParsePrimary.parsePrimary(parser);
        } else {
            operand = parser.parseExpression(parser.getPrecedence("++"));
        }
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseReverse(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle operators with any number of arguments
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static OperatorNode parseSystem(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle `system {$program} @args`
        operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, true, false);
        return new OperatorNode(token.text, operand, currentIndex);
    }

    static BinaryOperatorNode parseBinmodeOperator(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        // Handle 'binmode' operator with a FileHandle and List operands
        Node handle;
        operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
        handle = ((ListNode) operand).elements.removeFirst();
        return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
    }

    static BinaryOperatorNode parseSeek(Parser parser, LexerToken token, int currentIndex) {
        Node operand;
        Node handle;
        // Handle 'seek' operator with a FileHandle and List operands
        operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
        handle = ((ListNode) operand).elements.removeFirst();
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
        } else {
            // Check for the specific pattern: :: followed by identifier (which is invalid for require)
            if (token.type == LexerTokenType.OPERATOR && token.text.equals("::")) {
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