package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.lexer.LexerTokenType.NEWLINE;
import static org.perlonjava.lexer.LexerTokenType.WHITESPACE;
import static org.perlonjava.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The CoreOperatorResolver class is responsible for parsing core operators in the Perl language.
 * It provides a static method to interpret tokens and convert them into corresponding AST nodes.
 * This class handles a wide range of Perl operators, including unary, binary, and operators with
 * varying numbers of arguments.
 */
public class CoreOperatorResolver {

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
                handleEmptyParentheses(parser);
                return new NumberNode(Integer.toString(parser.ctx.errorUtil.getLineNumber(parser.tokenIndex)), parser.tokenIndex);
            case "__FILE__":
                // Returns the current file name as a StringNode
                handleEmptyParentheses(parser);
                return new StringNode(parser.ctx.errorUtil.getFileName(), parser.tokenIndex);
            case "__PACKAGE__":
                // Returns the current package name as a StringNode
                handleEmptyParentheses(parser);
                return new StringNode(parser.ctx.symbolTable.getCurrentPackage(), parser.tokenIndex);
            case "__SUB__", "time", "times", "wait", "wantarray":
                // Handle operators with zero arguments
                handleEmptyParentheses(parser);
                return new OperatorNode(token.text, null, currentIndex);
            case "fork":
                handleEmptyParentheses(parser);
                return new OperatorNode(token.text, new ListNode(currentIndex), currentIndex);
            case "not":
                // Handle 'not' keyword as a unary operator with an operand
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(token.text, operand, currentIndex);
             case "abs", "caller", "chdir", "chomp", "chop", "chr", "cos", "exit", "exp", "fc",
                  "glob", "gmtime", "hex", "int", "lc", "lcfirst", "length", "localtime", "log",
                  "oct", "ord", "pop", "pos", "prototype", "quotemeta", "rand", "ref", "reset",
                  "rmdir", "shift", "sin", "sleep", "sqrt", "srand", "study", "uc",
                  "ucfirst", "undef":
                 // Handle operators with one optional argument
                 return OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
             case "select":
                 // Handle 'select' operator with two different syntaxes:
                 // 1. select FILEHANDLE or select (returns/sets current filehandle)
                 // 2. select RBITS,WBITS,EBITS,TIMEOUT (syscall)
                 operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                 int argCount = ((ListNode) operand).elements.size();
                 if (argCount == 0 || argCount == 1 || argCount == 4) {
                     // select or select FILEHANDLE
                     // select RBITS,WBITS,EBITS,TIMEOUT (syscall version)
                     return new OperatorNode(token.text, operand, currentIndex);
                 } else {
                     throw new PerlCompilerException(parser.tokenIndex,
                         "Wrong number of arguments for select: expected 0, 1, or 4, got " + argCount,
                         parser.ctx.errorUtil);
                 }
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
                    operand = ParserNodeUtils.scalarUnderscore(parser);
                }
                return new OperatorNode("qx", operand, parser.tokenIndex);
            case "unpack":
                // Handle 'unpack' operator with one mandatory and one optional argument
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                if (((ListNode) operand).elements.size() == 1) {
                    // Create `$_` variable if only one argument is provided
                    ((ListNode) operand).elements.add(
                            ParserNodeUtils.scalarUnderscore(parser)
                    );
                }
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "bless":
                // Handle 'bless' operator with special handling for class name
                ListNode operand1 = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                Node ref = operand1.elements.get(0);
                Node className;
                if (operand1.elements.size() > 1) {
                    className = operand1.elements.get(1);
                    if (className instanceof StringNode && ((StringNode) className).value.isEmpty()) {
                        // default to main package if empty class name is provided
                        className = new StringNode("main", currentIndex);
                    }
                } else {
                    // default to current package if no class name is provided
                    className = new StringNode(parser.ctx.symbolTable.getCurrentPackage(), currentIndex);
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
            case "map", "grep", "all", "any":
                // Handle 'map' and 'grep' operators
                return OperatorParser.parseMapGrep(parser, token);
            case "pack":
                // Handle 'pack' operator with one or more arguments
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "reverse", "splice", "unlink", "mkdir", "die", "warn", "system", "exec":
                // Handle operators with any number of arguments
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "readline", "eof", "tell", "getc", "open", "close", "fileno", "truncate":
                // Handle file-related operators with special handling for default handles
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                Node handle;
                if (((ListNode) operand).elements.isEmpty()) {
                    String defaultHandle = switch (token.text) {
                        case "readline" -> "main::ARGV";
                        case "eof" -> "main::STDIN";
                        case "tell" -> "main::^LAST_FH";
                        case "getc" -> "main::STDIN";
                        case "open", "fileno" ->
                                throw new PerlCompilerException(parser.tokenIndex, "Not enough arguments for " + token.text, parser.ctx.errorUtil);
                        case "close" -> "main::STDIN";    // XXX TODO use currently selected file handle
                        default -> throw new PerlCompilerException(parser.tokenIndex, "Unexpected value: " + token.text, parser.ctx.errorUtil);
                    };
                    handle = new IdentifierNode(defaultHandle, currentIndex);
                } else {
                    handle = ((ListNode) operand).elements.removeFirst();
                }
                return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
            case "binmode":
                // Handle 'binmode' operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                handle = ((ListNode) operand).elements.removeFirst();
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
                            ParserNodeUtils.scalarUnderscore(parser)
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
                    operand = listNode.elements.getFirst();
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
                boolean isSubroutine = peek(parser).text.equals("&");  // goto &subr
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
                if (isSubroutine) {
                    // goto &sub form
                    return new OperatorNode("return", operand, currentIndex);
                }
                // goto LABEL form
                return new OperatorNode("goto", operand, currentIndex);
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
                OperatorNode node = (OperatorNode) OperatorParser.parseRequire(parser);
                // Is `module_true` feature enabled?
                if (parser.ctx.symbolTable.isFeatureCategoryEnabled("module_true")) {
                    node.setAnnotation("module_true", true);
                }
                return node;
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
            case "dump", "format", "write", "dbmclose", "dbmopen":
                // Not implemented
                throw new PerlCompilerException(parser.tokenIndex, "Not implemented: operator: " + token.text, parser.ctx.errorUtil);
            case "accept", "bind", "connect", "getpeername", "getsockname", "getsockopt",
                 "listen", "recv", "send", "setsockopt", "shutdown", "socketpair":
                // Socket function
                throw new PerlCompilerException(parser.tokenIndex, "Not implemented: socket operator: " + token.text, parser.ctx.errorUtil);
            case "msgctl", "msgget", "msgrcv", "msgsnd", "semctl", "semget", "semop",
                 "shmctl", "shmget", "shmread", "shmwrite":
                // System V IPC functions
                throw new PerlCompilerException(parser.tokenIndex, "Not implemented: System V IPC operator: " + token.text, parser.ctx.errorUtil);
            case "endgrent", "endhostent", "endnetent", "endpwent", "getgrent", "getgrgid",
                 "getgrnam", "getlogin", "getpwent", "getpwnam", "getpwuid", "setgrent", "setpwent":
                // User/Group info functions
                throw new PerlCompilerException(parser.tokenIndex, "Not implemented: User/Group info operator: " + token.text, parser.ctx.errorUtil);
            case "endprotoent", "endservent", "gethostbyaddr", "gethostbyname", "gethostent",
                 "getnetbyaddr", "getnetbyname", "getnetent", "getprotobyname", "getprotobynumber",
                 "getprotoent", "getservbyname", "getservbyport", "getservent", "sethostent",
                 "setnetent", "setprotoent", "setservent":
                // Network info functions
                throw new PerlCompilerException(parser.tokenIndex, "Not implemented: Network info operator: " + token.text, parser.ctx.errorUtil);
            default:
                String operator2 = token.text;
                String prototype = CORE_PROTOTYPES.get(operator2);

                // Parse using the operator prototype string
                // Example: "read",
                if (prototype != null) {
                    parser.ctx.logDebug("CORE operator " + operator2 + " with prototype " + prototype);
                    // Set the operator name as the subroutine name for better error messages
                    String previousSubName = parser.ctx.symbolTable.getCurrentSubroutine();
                    parser.ctx.symbolTable.setCurrentSubroutine(operator2);
                    try {
                        ListNode arguments = consumeArgsWithPrototype(parser, prototype);
                        return new OperatorNode(
                                operator2,
                                arguments,
                                currentIndex);
                    } finally {
                        // Restore the previous subroutine name
                        parser.ctx.symbolTable.setCurrentSubroutine(previousSubName);
                    }
                }
        }
        return null;
    }

    private static void handleEmptyParentheses(Parser parser) {
        LexerToken nextToken2 = peek(parser);
        // Handle optional empty parentheses
        if (nextToken2.text.equals("(")) {
            TokenUtils.consume(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }
    }
}
