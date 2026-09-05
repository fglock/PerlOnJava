package org.perlonjava.backend.jvm.astrefactor;

import org.perlonjava.frontend.astnode.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
    // The estimator's historical 40 KB threshold keeps ordinary large source
    // blocks in their lexical method while leaving headroom before the JVM's
    // 64 KB verifier limit.  Extract only genuinely oversized methods: an
    // anonymous-subroutine boundary changes dynamic match/eval state.
    public static final int LARGE_BYTECODE_SIZE = 40000;
    public static final int MIN_CHUNK_SIZE = 4;            // Minimum statements to extract as a chunk

    /**
     * Creates an anonymous subroutine call node that invokes a subroutine with the @_ array as arguments.
     *
     * @param tokenIndex  the token index for AST node positioning
     * @param nestedBlock the block node representing the subroutine body
     * @return a BinaryOperatorNode representing the anonymous subroutine call
     */
    public static BinaryOperatorNode createAnonSubCall(int tokenIndex, BlockNode nestedBlock) {
        ArrayList<Node> args = new ArrayList<>(1);
        args.add(variableAst("@", "_", tokenIndex));
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, nestedBlock, false, tokenIndex),
                new ListNode(args, tokenIndex),
                tokenIndex
        );
    }
}
