package org.perlonjava.frontend.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.PerlJavaUnimplementedException;

import static org.perlonjava.frontend.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.frontend.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.frontend.parser.TokenUtils.consume;
import static org.perlonjava.frontend.parser.TokenUtils.peek;

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
            case "__CLASS__" -> {
                handleEmptyParentheses(parser);
                // Return the compile-time package name
                // TODO: Implement proper runtime class detection that works with field defaults
                // and ADJUST blocks without causing symbol table errors
                yield new StringNode(parser.ctx.symbolTable.getCurrentPackage(), parser.tokenIndex);
            }
            case "__SUB__", "time", "times", "wait", "wantarray" -> {
                handleEmptyParentheses(parser);
                yield new OperatorNode(token.text, null, currentIndex);
            }
            case "not" -> OperatorParser.parseNot(parser, token, currentIndex);
            case "abs", "caller", "chdir", "chr", "cos", "exit", "exp", "fc",
                 "glob", "gmtime", "hex", "int", "lc", "lcfirst", "length", "localtime", "log",
                 "oct", "ord", "pop", "pos", "prototype", "quotemeta", "rand", "ref", "reset",
                 "rmdir", "shift", "sin", "sleep", "sqrt", "srand", "study", "uc",
                 "ucfirst" -> OperatorParser.parseOperatorWithOneOptionalArgument(parser, token);
            case "undef" -> OperatorParser.parseUndef(parser, token, currentIndex);
            case "select" -> OperatorParser.parseSelect(parser, token, currentIndex);
            case "stat", "lstat" -> OperatorParser.parseStat(parser, token, currentIndex);
            case "readpipe" -> OperatorParser.parseReadpipe(parser);
            case "bless" -> OperatorParser.parseBless(parser, currentIndex);
            case "split" -> OperatorParser.parseSplit(parser, token, currentIndex);
            case "push", "unshift", "join", "sprintf" ->
                    OperatorParser.parseJoin(parser, token, operatorName, currentIndex);
            case "sort" -> ParseMapGrepSort.parseSort(parser, token);
            case "map", "grep", "all", "any" -> ParseMapGrepSort.parseMapGrep(parser, token);
            case "pack" -> OperatorParser.parsePack(parser, token, currentIndex);
            case "chomp", "chop", "splice", "mkdir" -> OperatorParser.parseReverse(parser, token, currentIndex);
            case "die", "warn" -> OperatorParser.parseDieWarn(parser, token, currentIndex);
            case "system", "exec" -> OperatorParser.parseSystem(parser, token, currentIndex);
            case "readline", "eof", "tell" -> OperatorParser.parseReadline(parser, token, currentIndex);
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
            case "q", "qq", "qx", "qw", "qr", "tr", "y", "s", "m" ->
                    OperatorParser.parseSpecialQuoted(parser, token, startIndex);
            case "dump", "dbmclose", "dbmopen" ->
                    throw new PerlJavaUnimplementedException(parser.tokenIndex, "Not implemented: operator: " + token.text, parser.ctx.errorUtil);
            case "format" ->
                // Format statements should be handled by StatementResolver, not as operators
                // Return null to allow StatementResolver to handle it
                    null;
            case "msgctl", "msgget", "msgrcv", "msgsnd", "semctl", "semget", "semop",
                 "shmctl", "shmget", "shmread", "shmwrite",
                 "endhostent", "endnetent", "endprotoent", "endservent", "gethostent",
                 "getnetbyaddr", "getnetbyname", "getnetent",
                 "getprotoent", "getservent", "sethostent",
                 "setnetent", "setprotoent", "setservent", "reverse" -> parseWithPrototype(parser, token, currentIndex);
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