package org.perlonjava.astrefactor;

import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.SubroutineNode;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
    /**
     * Creates an anonymous subroutine call node that invokes a subroutine with the @_ array as arguments.
     *
     * @param tokenIndex  the token index for AST node positioning
     * @param nestedBlock the block node representing the subroutine body
     * @return a BinaryOperatorNode representing the anonymous subroutine call
     */
    public static BinaryOperatorNode createAnonSubCall(int tokenIndex, BlockNode nestedBlock) {
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, nestedBlock, false, tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );
    }
}
