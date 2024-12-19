package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.lexer.LexerTokenType.NEWLINE;
import static org.perlonjava.lexer.LexerTokenType.WHITESPACE;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The ParseCoreOperator class is responsible for parsing core operators in the Perl language.
 * It provides a static method to interpret tokens and convert them into corresponding AST nodes.
 * This class handles a wide range of Perl operators, including unary, binary, and operators with
 * varying numbers of arguments.
 */
public class ParseCoreOperator {

    /**
     * Parses a core operator from the given token and returns the corresponding AST node.
     *
     * @param parser     The parser instance containing the current parsing context.
     * @param token      The current lexer token representing the operator to be parsed.
     * @param startIndex The starting index of the token in the parser's token list.
     * @return A Node representing the parsed operator and its operands.
     */
    public static Node parseCoreOperator(Parser parser, LexerToken token, int startIndex) {
        Node operand;
        int currentIndex = parser.tokenIndex;

        // Switch statement to handle different operator cases based on the token text
        switch (token.text) {
            case "__LINE__":
                // Returns the current line number as a NumberNode
                return new NumberNode(Integer.toString(parser.ctx.errorUtil.getLineNumber(parser.tokenIndex)), parser.tokenIndex);
            case "__FILE__":
                // Returns the current file name as a StringNode
                return new StringNode(parser.ctx.compilerOptions.fileName, parser.tokenIndex);
            case "__PACKAGE__":
                // Returns the current package name as a StringNode
                return new StringNode(parser.ctx.symbolTable.getCurrentPackage(), parser.tokenIndex);
            case "__SUB__", "time", "times", "fork", "wait", "wantarray":
                // Handle operators with zero arguments
                return new OperatorNode(token.text, null, currentIndex);
            case "not":
                // Handle 'not' keyword as a unary operator with an operand
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(token.text, operand, currentIndex);
            case "abs", "caller", "chdir", "chomp", "chop", "chr", "cos", "exit", "exp", "fc",
                 "glob", "gmtime", "hex", "int", "lc", "lcfirst", "length", "localtime", "log",
                 "oct", "ord", "pop", "pos", "prototype", "quotemeta", "rand", "ref", "reset",
                 "rmdir", "select", "shift", "sin", "sleep", "sqrt", "srand", "study", "uc",
                 "ucfirst", "undef":
                // Handle operators with one optional argument
                return OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
            case "stat", "lstat":
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
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
                    }
                    return new OperatorNode(token.text,
                            new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
                }
                parser.tokenIndex = currentIndex;
                return OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
            case "readpipe":
                // Handle 'readpipe' operator with one optional argument
                operand = ListParser.parseZeroOrOneList(parser, 0);
                if (((ListNode) operand).elements.isEmpty()) {
                    // Create `$_` variable if no argument is provided
                    operand = OperatorParser.scalarUnderscore(parser);
                }
                return new OperatorNode("qx", operand, parser.tokenIndex);
            case "unpack":
                // Handle 'unpack' operator with one mandatory and one optional argument
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                if (((ListNode) operand).elements.size() == 1) {
                    // Create `$_` variable if only one argument is provided
                    ((ListNode) operand).elements.add(
                            OperatorParser.scalarUnderscore(parser)
                    );
                }
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "readdir", "closedir", "telldir", "rewinddir":
                // Handle operators with one mandatory argument
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "rindex", "index", "atan2", "crypt", "opendir", "seekdir":
                // Handle operators with two mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 2, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "vec":
                // Handle operators with three mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "socket":
                // Handle operators with four mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 4, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "bless":
                // Handle 'bless' operator with special handling for class name
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                Node ref = ((ListNode) operand).elements.get(0);
                Node className = ((ListNode) operand).elements.get(1);
                if (className == null) {
                    className = new StringNode("main", currentIndex);
                }
                return new BinaryOperatorNode("bless", ref, className, currentIndex);
            case "split":
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
            case "push", "unshift", "join", "substr", "sprintf":
                // Handle operators with a RuntimeList operand
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                separator = ((ListNode) operand).elements.removeFirst();
                return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
            case "sort":
                // Handle 'sort' operator
                return OperatorParser.parseSort(parser, token);
            case "map", "grep":
                // Handle 'map' and 'grep' operators
                return OperatorParser.parseMapGrep(parser, token);
            case "pack":
                // Handle 'pack' operator with one or more arguments
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "reverse", "splice", "unlink", "mkdir", "die", "warn":
                // Handle operators with any number of arguments
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "readline", "eof", "tell", "getc", "open", "close", "fileno", "truncate":
                // Handle file-related operators with special handling for default handles
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                Node handle = null;
                if (((ListNode) operand).elements.isEmpty()) {
                    String defaultHandle = switch (token.text) {
                        case "readline":
                            yield "main::ARGV";
                        case "eof":
                            yield "main::STDIN";
                        case "tell":
                            yield "main::^LAST_FH";
                        case "getc":
                            yield "main::STDIN";
                        case "open":
                        case "fileno":
                            throw new PerlCompilerException(parser.tokenIndex, "Not enough arguments for " + token.text, parser.ctx.errorUtil);
                        case "close":
                            yield "main::STDIN";    // XXX TODO use currently selected file handle
                        default:
                            throw new IllegalStateException("Unexpected value: " + token.text);
                    };
                    handle = new IdentifierNode(defaultHandle, currentIndex);
                } else {
                    handle = ((ListNode) operand).elements.removeFirst();
                }
                return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
            case "seek":
                // Handle 'seek' operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
                handle = ((ListNode) operand).elements.removeFirst();
                return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
            case "printf", "print", "say":
                // Handle print-related operators
                return OperatorParser.parsePrint(parser, token, currentIndex);
            case "delete", "exists":
                // Handle 'delete' and 'exists' operators with special parsing context
                parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                operand = ListParser.parseZeroOrOneList(parser, 1);
                parser.parsingTakeReference = false;
                return new OperatorNode(token.text, operand, currentIndex);
            case "defined":
                // Handle 'defined' operator with special parsing context
                parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                operand = ListParser.parseZeroOrOneList(parser, 0);
                parser.parsingTakeReference = false;
                if (((ListNode) operand).elements.isEmpty()) {
                    // `defined` without arguments means `defined $_`
                    ((ListNode) operand).elements.add(
                            OperatorParser.scalarUnderscore(parser)
                    );
                }
                return new OperatorNode(token.text, operand, currentIndex);
            case "scalar", "values", "keys", "each":
                // Handle operators with a single operand
                operand = ParsePrimary.parsePrimary(parser);
                if (operand instanceof ListNode listNode) {
                    if (listNode.elements.size() != 1) {
                        throw new PerlCompilerException(parser.tokenIndex, "Too many arguments for " + token.text, parser.ctx.errorUtil);
                    }
                    operand = listNode.elements.get(0);
                }
                return new OperatorNode(token.text, operand, currentIndex);
            case "our", "state", "my":
                // Handle variable declaration operators
                return OperatorParser.parseVariableDeclaration(parser, token.text, currentIndex);
            case "local":
                // Handle 'local' keyword as a unary operator with an operand
                if (peek(parser).text.equals("(")) {
                    operand = ParsePrimary.parsePrimary(parser);
                } else {
                    operand = parser.parseExpression(parser.getPrecedence("++"));
                }
                return new OperatorNode(token.text, operand, currentIndex);
            case "last", "next", "redo":
                // Handle loop control operators
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "goto":
                // Handle 'goto' keyword as a unary operator with an operand
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
                return new OperatorNode("return", operand, currentIndex);
            case "return":
                // Handle 'return' keyword as a unary operator with an operand
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                return new OperatorNode("return", operand, currentIndex);
            case "eval", "evalbytes":
                // Handle 'eval' and 'evalbytes' operators
                return OperatorParser.parseEval(parser, token.text);
            case "do":
                // Handle 'do' operator
                return OperatorParser.parseDoOperator(parser);
            case "require":
                // Handle 'require' operator
                return OperatorParser.parseRequire(parser);
            case "sub":
                // Handle 'sub' keyword to parse an anonymous subroutine
                return SubroutineParser.parseSubroutineDefinition(parser, false, null);
            case "q", "qq", "qx", "qw", "qr", "tr", "y", "s", "m":
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
        return null;
    }
}