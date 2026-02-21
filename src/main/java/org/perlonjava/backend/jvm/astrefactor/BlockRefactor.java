package org.perlonjava.backend.jvm.astrefactor;

import org.perlonjava.frontend.astnode.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
    public static final int LARGE_BYTECODE_SIZE = 40000;   // Maximum bytecode size before refactoring
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

    /**
     * Builds nested closure structure from segments.
     * Structure: direct1, direct2, sub{ chunk1, sub{ chunk2, chunk3 }->(@_) }->(@_)
     * Closures are always placed at tail position to preserve variable scoping.
     *
     * @param segments         List of segments (either Node for direct elements or List<Node> for chunks)
     * @param tokenIndex       token index for new nodes
     * @param minChunkSize     minimum size for a chunk to be wrapped in a closure
     * @param returnTypeIsList if true, wrap elements in ListNode to return list; if false, execute statements
     * @param skipRefactoring  thread-local flag to prevent recursion during BlockNode construction
     * @return List of processed elements with nested structure
     */
    @SuppressWarnings("unchecked")
    public static List<Node> buildNestedStructure(
            List<Object> segments,
            int tokenIndex,
            int minChunkSize,
            boolean returnTypeIsList,
            ThreadLocal<Boolean> skipRefactoring) {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        int firstBigIndex = -1;
        int endExclusive = segments.size();
        Node tailClosure = null;

        for (int i = segments.size() - 1; i >= 0; i--) {
            Object segment = segments.get(i);
            if (!(segment instanceof List)) {
                continue;
            }
            List<Node> chunk = (List<Node>) segment;
            if (chunk.size() < minChunkSize) {
                continue;
            }

            firstBigIndex = i;

            List<Node> blockElements = new ArrayList<>();
            blockElements.addAll(chunk);
            for (int s = i + 1; s < endExclusive; s++) {
                Object seg = segments.get(s);
                if (seg instanceof Node directNode) {
                    blockElements.add(directNode);
                } else {
                    blockElements.addAll((List<Node>) seg);
                }
            }
            if (tailClosure != null) {
                blockElements.add(tailClosure);
            }

            List<Node> wrapped = returnTypeIsList ? wrapInListNode(blockElements, tokenIndex) : blockElements;
            BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
            tailClosure = createAnonSubCall(tokenIndex, block);

            endExclusive = i;
        }

        if (tailClosure == null) {
            List<Node> result = new ArrayList<>();
            for (Object segment : segments) {
                if (segment instanceof Node directNode) {
                    result.add(directNode);
                } else {
                    result.addAll((List<Node>) segment);
                }
            }
            return result;
        }

        List<Node> result = new ArrayList<>();
        for (int s = 0; s < firstBigIndex; s++) {
            Object seg = segments.get(s);
            if (seg instanceof Node directNode) {
                result.add(directNode);
            } else {
                result.addAll((List<Node>) seg);
            }
        }
        result.add(tailClosure);
        return result;
    }

    /**
     * Wraps elements in a ListNode to ensure the closure returns a list of elements.
     */
    private static List<Node> wrapInListNode(List<Node> elements, int tokenIndex) {
        ListNode listNode = new ListNode(elements, tokenIndex);
        listNode.setAnnotation("chunkAlreadyRefactored", true);
        return List.of(listNode);
    }

    /**
     * Creates a BlockNode using thread-local flag to prevent recursion.
     */
    private static BlockNode createBlockNode(List<Node> elements, int tokenIndex, ThreadLocal<Boolean> skipRefactoring) {
        BlockNode block;
        skipRefactoring.set(true);
        try {
            block = new BlockNode(elements, tokenIndex);
        } finally {
            skipRefactoring.set(false);
        }
        return block;
    }

}
