package org.perlonjava.astrefactor;

import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.SubroutineNode;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
    public static BinaryOperatorNode createAnonSubCall(int tokenIndex, BlockNode nestedBlock) {
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, nestedBlock, false, tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );
    }
}
