package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

    /**
     * Builds nested closure structure from segments.
     * Structure: direct1, direct2, sub{ chunk1, sub{ chunk2, chunk3 }->(@_) }->(@_)
     * Closures are always placed at tail position to preserve variable scoping.
     *
     * @param segments      List of segments (either Node for direct elements or List<Node> for chunks)
     * @param tokenIndex    token index for new nodes
     * @param minChunkSize  minimum size for a chunk to be wrapped in a closure
     * @param wrapperFunc   function to wrap chunk elements before creating BlockNode (e.g., ListNode wrapper or identity)
     * @param markBlock     function to mark blocks as already refactored
     * @param createBlock   function to create BlockNode (may use thread-local flags to prevent recursion)
     * @return List of processed elements with nested structure
     */
    @SuppressWarnings("unchecked")
    public static List<Node> buildNestedStructure(
            List<Object> segments,
            int tokenIndex,
            int minChunkSize,
            Function<List<Node>, List<Node>> wrapperFunc,
            Function<BlockNode, BlockNode> markBlock,
            Function<List<Node>, BlockNode> createBlock) {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Node> result = new ArrayList<>();

        // Process segments forward, accumulating direct elements and building nested closures at the end
        for (int i = 0; i < segments.size(); i++) {
            Object segment = segments.get(i);

            if (segment instanceof Node directNode) {
                // Direct elements (labels, variable declarations, control flow) stay at block level
                result.add(directNode);
            } else if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= minChunkSize) {
                    // Create closure for this chunk at tail position
                    // Collect remaining chunks to nest inside this closure
                    List<Node> blockElements = new ArrayList<>(chunk);

                    // Build nested closures for remaining chunks
                    for (int j = i + 1; j < segments.size(); j++) {
                        Object nextSegment = segments.get(j);
                        if (nextSegment instanceof Node) {
                            blockElements.add((Node) nextSegment);
                        } else if (nextSegment instanceof List) {
                            List<Node> nextChunk = (List<Node>) nextSegment;
                            if (nextChunk.size() >= minChunkSize) {
                                // Create nested closure for next chunk
                                List<Node> nestedElements = new ArrayList<>(nextChunk);
                                // Add all remaining segments to the nested closure
                                for (int k = j + 1; k < segments.size(); k++) {
                                    Object remainingSegment = segments.get(k);
                                    if (remainingSegment instanceof Node) {
                                        nestedElements.add((Node) remainingSegment);
                                    } else {
                                        nestedElements.addAll((List<Node>) remainingSegment);
                                    }
                                }
                                List<Node> wrappedNested = wrapperFunc.apply(nestedElements);
                                BlockNode nestedBlock = markBlock.apply(createBlock.apply(wrappedNested));
                                Node nestedClosure = createAnonSubCall(tokenIndex, nestedBlock);
                                blockElements.add(nestedClosure);
                                j = segments.size(); // Break outer loop
                                break;
                            } else {
                                blockElements.addAll(nextChunk);
                            }
                        }
                    }

                    List<Node> wrapped = wrapperFunc.apply(blockElements);
                    BlockNode block = markBlock.apply(createBlock.apply(wrapped));
                    Node closure = createAnonSubCall(tokenIndex, block);
                    result.add(closure);
                    break; // All remaining segments are now inside the closure
                } else {
                    // Chunk too small - add elements directly
                    result.addAll(chunk);
                }
            }
        }

        return result;
    }
}
