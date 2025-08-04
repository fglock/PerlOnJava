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
        int currentIndex = parser.tokenIndex;
        String operatorName = token.text;

        return switch (operatorName) {
            case "__LINE__" -> {
                handleEmptyParentheses(parser);
                yield new NumberNode(Integer.toString(parser.ctx.errorUtil.getLineNumber(parser.tokenIndex)), parser.tokenIndex);
            }
            case "__FILE__" -> {
                handleEmptyParentheses(parser);
                yield new StringNode(parser.ctx.errorUtil.getFileName(), parser.tokenIndex);
            }
            case "__PACKAGE__" -> {
                handleEmptyParentheses(parser);
                yield new StringNode(parser.ctx.symbolTable.getCurrentPackage(), parser.tokenIndex);
            }
            case "__SUB__", "time", "times", "wait", "wantarray" -> {
                handleEmptyParentheses(parser);
                yield new OperatorNode(token.text, null, currentIndex);
            }
            case "fork" -> {
                handleEmptyParentheses(parser);
                yield new OperatorNode(token.text, new ListNode(currentIndex), currentIndex);
            }
            case "not" -> OperatorParser.parseNot(parser, token, currentIndex);
            case "abs", "caller", "chdir", "chomp", "chop", "chr", "cos", "exit", "exp", "fc",
                 "glob", "gmtime", "hex", "int", "lc", "lcfirst", "length", "localtime", "log",
                 "oct", "ord", "pop", "pos", "prototype", "quotemeta", "rand", "ref", "reset",
                 "rmdir", "shift", "sin", "sleep", "sqrt", "srand", "study", "uc",
                 "ucfirst", "undef" -> OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
            case "select" -> OperatorParser.parseSelect(parser, token, currentIndex);
            case "stat", "lstat" -> OperatorParser.parseStat(parser, token, currentIndex);
            case "readpipe" -> OperatorParser.parseReadpipe(parser);
            case "unpack" -> OperatorParser.parseUnpack(parser, token);
            case "bless" -> OperatorParser.parseBless(parser, currentIndex);
            case "split" -> OperatorParser.parseSplit(parser, token, currentIndex);
            case "push", "unshift", "join", "substr", "sprintf" -> OperatorParser.parseJoin(parser, token, operatorName, currentIndex);
            case "sort" -> ParseMapGrepSort.parseSort(parser, token);
            case "map", "grep", "all", "any" -> ParseMapGrepSort.parseMapGrep(parser, token);
            case "pack" -> OperatorParser.parsePack(parser, token, currentIndex);
            case "reverse", "splice", "unlink", "mkdir", "die", "warn" -> OperatorParser.parseReverse(parser, token, currentIndex);
            case "system", "exec" -> OperatorParser.parseSystem(parser, token, currentIndex);
            case "readline", "eof", "tell", "getc", "open", "fileno", "truncate" -> OperatorParser.parseReadline(parser, token, currentIndex);
            case "binmode" -> OperatorParser.parseBinmodeOperator(parser, token, currentIndex);
            case "seek" -> OperatorParser.parseSeek(parser, token, currentIndex);
            case "printf", "print", "say" -> OperatorParser.parsePrint(parser, token, currentIndex);
            case "delete", "exists" -> OperatorParser.parseDelete(parser, token, currentIndex);
            case "defined" -> OperatorParser.parseDefined(parser, token, currentIndex);
            case "scalar", "values", "keys", "each" -> OperatorParser.parseKeys(parser, token, currentIndex);
            case "our", "state", "my" -> OperatorParser.parseVariableDeclaration(parser, token.text, currentIndex);
            case "local" -> OperatorParser.parseLocal(parser, token, currentIndex);
            case "last", "next", "redo" -> OperatorParser.parseLast(parser, token, currentIndex);
            case "goto" -> OperatorParser.parseGoto(parser, currentIndex);
            case "return" -> OperatorParser.parseReturn(parser, currentIndex);
            case "eval", "evalbytes" -> OperatorParser.parseEval(parser, token.text);
            case "do" -> OperatorParser.parseDoOperator(parser);
            case "require" -> OperatorParser.parseRequire(parser);
            case "sub" -> SubroutineParser.parseSubroutineDefinition(parser, false, null);
            case "method" -> {
                    Node node = SubroutineParser.parseSubroutineDefinition(parser, false, null);
                    node.setAnnotation("isMethod", true);
                    yield node;
            }
            case "q", "qq", "qx", "qw", "qr", "tr", "y", "s", "m" -> OperatorParser.parseSpecialQuoted(parser, token, startIndex);
            case "dump", "format", "dbmclose", "dbmopen" ->
                    throw new PerlCompilerException(parser.tokenIndex, "Not implemented: operator: " + token.text, parser.ctx.errorUtil);
            case "accept", "bind", "connect", "getpeername", "getsockname", "getsockopt",
                 "listen", "recv", "send", "setsockopt", "shutdown", "socketpair" ->
                    throw new PerlCompilerException(parser.tokenIndex, "Not implemented: socket operator: " + token.text, parser.ctx.errorUtil);
            case "msgctl", "msgget", "msgrcv", "msgsnd", "semctl", "semget", "semop",
                 "shmctl", "shmget", "shmread", "shmwrite" ->
                    throw new PerlCompilerException(parser.tokenIndex, "Not implemented: System V IPC operator: " + token.text, parser.ctx.errorUtil);
            case "endgrent", "endhostent", "endnetent", "endpwent", "getgrent", "getgrgid",
                 "getgrnam", "getlogin", "getpwent", "getpwnam", "getpwuid", "setgrent", "setpwent" ->
                    throw new PerlCompilerException(parser.tokenIndex, "Not implemented: User/Group info operator: " + token.text, parser.ctx.errorUtil);
            case "endprotoent", "endservent", "gethostbyaddr", "gethostbyname", "gethostent",
                 "getnetbyaddr", "getnetbyname", "getnetent", "getprotobyname", "getprotobynumber",
                 "getprotoent", "getservbyname", "getservbyport", "getservent", "sethostent",
                 "setnetent", "setprotoent", "setservent" ->
                    throw new PerlCompilerException(parser.tokenIndex, "Not implemented: Network info operator: " + token.text, parser.ctx.errorUtil);
            default -> parseWithPrototype(parser, token, currentIndex);
        };
    }

    private static Node parseWithPrototype(Parser parser, LexerToken token, int currentIndex) {
        String operator = token.text;
        String prototype = CORE_PROTOTYPES.get(operator);

        if (prototype != null) {
            parser.ctx.logDebug("CORE operator " + operator + " with prototype " + prototype);
            // Set the operator name as the subroutine name for better error messages
            String previousSubName = parser.ctx.symbolTable.getCurrentSubroutine();
            parser.ctx.symbolTable.setCurrentSubroutine(operator);
            try {
                ListNode arguments = consumeArgsWithPrototype(parser, prototype);
                return new OperatorNode(operator, arguments, currentIndex);
            } finally {
                // Restore the previous subroutine name
                parser.ctx.symbolTable.setCurrentSubroutine(previousSubName);
            }
        }
        return null;
    }

    private static void handleEmptyParentheses(Parser parser) {
        LexerToken nextToken = peek(parser);
        // Handle optional empty parentheses
        if (nextToken.text.equals("(")) {
            consume(parser);
            consume(parser, LexerTokenType.OPERATOR, ")");
        }
    }
}