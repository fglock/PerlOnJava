package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.NumberParser.parseNumber;
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
        String operatorName = token.text;

        // Switch statement to handle different operator cases based on the token text
        switch (operatorName) {
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
                return OperatorParser.parseNot(parser, token, currentIndex);
            case "abs", "caller", "chdir", "chomp", "chop", "chr", "cos", "exit", "exp", "fc",
                  "glob", "gmtime", "hex", "int", "lc", "lcfirst", "length", "localtime", "log",
                  "oct", "ord", "pop", "pos", "prototype", "quotemeta", "rand", "ref", "reset",
                  "rmdir", "shift", "sin", "sleep", "sqrt", "srand", "study", "uc",
                  "ucfirst", "undef":
                 // Handle operators with one optional argument
                 return OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
             case "select":
                 return OperatorParser.parseSelect(parser, token, currentIndex);
            case "stat", "lstat":
                return OperatorParser.parseStat(parser, token, currentIndex);
            case "readpipe":
                return OperatorParser.parseReadpipe(parser);
            case "unpack":
                return OperatorParser.parseUnpack(parser, token);
            case "bless":
                return OperatorParser.parseBless(parser, currentIndex);
            case "split":
                return OperatorParser.parseSplit(parser, token, currentIndex);
            case "push", "unshift", "join", "substr", "sprintf":
                return OperatorParser.parseJoin(parser, token, operatorName, currentIndex);
            case "sort":
                // Handle 'sort' operator
                return OperatorParser.parseSort(parser, token);
            case "map", "grep", "all", "any":
                // Handle 'map' and 'grep' operators
                return OperatorParser.parseMapGrep(parser, token);
            case "pack":
                return OperatorParser.parsePack(parser, token, currentIndex);
            case "reverse", "splice", "unlink", "mkdir", "die", "warn":
                return OperatorParser.parseReverse(parser, token, currentIndex);
            case  "system", "exec":
                return OperatorParser.parseSystem(parser, token, currentIndex);
            case "readline", "eof", "tell", "getc", "open", "close", "fileno", "truncate":
                return OperatorParser.parseReadline(parser, token, currentIndex);
            case "binmode":
                return OperatorParser.parseBinmodeOperator(parser, token, currentIndex);
            case "seek":
                return OperatorParser.parseSeek(parser, token, currentIndex);
            case "printf", "print", "say":
                // Handle print-related operators
                return OperatorParser.parsePrint(parser, token, currentIndex);
            case "delete", "exists":
                return OperatorParser.parseDelete(parser, token, currentIndex);
            case "defined":
                return OperatorParser.parseDefined(parser, token, currentIndex);
            case "scalar", "values", "keys", "each":
                return OperatorParser.parseKeys(parser, token, currentIndex);
            case "our", "state", "my":
                // Handle variable declaration operators
                return OperatorParser.parseVariableDeclaration(parser, token.text, currentIndex);
            case "local":
                return OperatorParser.parseLocal(parser, token, currentIndex);
            case "last", "next", "redo":
                return OperatorParser.parseLast(parser, token, currentIndex);
            case "goto":
                return OperatorParser.parseGoto(parser, currentIndex);
            case "return":
                return OperatorParser.parseReturn(parser, currentIndex);
            case "eval", "evalbytes":
                // Handle 'eval' and 'evalbytes' operators
                return OperatorParser.parseEval(parser, token.text);
            case "do":
                // Handle 'do' operator
                return OperatorParser.parseDoOperator(parser);
            case "require":
                return OperatorParser.parseRequire(parser);
            case "sub":
                // Handle 'sub' keyword to parse an anonymous subroutine
                return SubroutineParser.parseSubroutineDefinition(parser, false, null);
            case "q", "qq", "qx", "qw", "qr", "tr", "y", "s", "m":
                return OperatorParser.parseSpecialQuoted(parser, token, startIndex);
            case "dump", "format", "dbmclose", "dbmopen":
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
