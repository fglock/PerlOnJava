package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Parser integration for PerlOnJava's native Future::AsyncAwait syntax. */
public final class FutureAsyncAwaitParser {
    static final String HINT_KEY = "Future::AsyncAwait/async";
    static final String CANCEL_HINT_KEY = "Future::AsyncAwait/cancel";
    private static final String AWAITABLE_HINT_KEY = "Future::AsyncAwait/awaitable";
    public static final String BACKEND_MESSAGE =
            "Future::AsyncAwait async execution requires a loaded Awaitable Future implementation"
                    + " (load Future before declaring async subs)";
    private FutureAsyncAwaitParser() {
    }

    static boolean isEnabled() {
        return hintEnabled(HINT_KEY);
    }

    static boolean isCancelEnabled() {
        return hintEnabled(CANCEL_HINT_KEY);
    }

    public static boolean hasAwaitableFuture() {
        return hintEnabled(AWAITABLE_HINT_KEY)
                || org.perlonjava.runtime.runtimetypes.RuntimeCode.isCodeDefined(
                GlobalVariable.getGlobalCodeRef("Future::new"));
    }

    private static boolean hintEnabled(String key) {
        RuntimeHash hints = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        RuntimeScalar value = hints.elements.get(key);
        return value != null && value.getBoolean();
    }

    static Node parseAsyncSubStatement(Parser parser) {
        int asyncIndex = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER, "async");
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER, "sub");
        Node result = SubroutineParser.parseSubroutineDefinition(parser, true, "our", true);
        markAsync(result, asyncIndex);
        return result;
    }

    static Node parseAsyncSubExpression(Parser parser, int asyncIndex) {
        if (TokenUtils.peek(parser).type != LexerTokenType.IDENTIFIER
                || !TokenUtils.peek(parser).text.equals("sub")) {
            return null;
        }
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER, "sub");
        Node result = SubroutineParser.parseSubroutineDefinition(parser, false, null, true);
        markAsync(result, asyncIndex);
        return result;
    }

    static void markAsync(Node result, int asyncIndex) {
        if (result instanceof org.perlonjava.frontend.astnode.AbstractNode abstractNode) {
            abstractNode.setAnnotation("futureAsyncAwaitSub", true);
            abstractNode.setAnnotation("futureAsyncAwaitTokenIndex", asyncIndex);
        }
        if (result instanceof org.perlonjava.frontend.astnode.SubroutineNode subroutine) {
            subroutine.block.setAnnotation("futureAsyncAwaitSub", true);
        }
    }

    static OperatorNode parseAwait(Parser parser, int awaitIndex) {
        if (!parser.parsingFutureAsyncAwaitSub
                && parser.ctx.symbolTable.isInSubroutineBody()) {
            parser.throwError(awaitIndex, "Cannot 'await' outside of an 'async sub'");
        }

        Node operand = parser.parseExpression(parser.getPrecedence("=~"));
        if (operand == null) {
            parser.throwError(awaitIndex, "Missing expression after 'await'");
        }

        OperatorNode await = new OperatorNode("await", operand, awaitIndex);
        await.setAnnotation("futureAsyncAwait", true);
        return await;
    }
}
