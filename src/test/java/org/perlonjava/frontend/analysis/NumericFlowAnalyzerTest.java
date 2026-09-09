package org.perlonjava.frontend.analysis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.For3Node;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class NumericFlowAnalyzerTest {
    @Test
    void annotatesAClosedLexicalAssignmentInsideALoopBody() {
        BinaryOperatorNode assignment = assignment("total", scalar("total"), new NumberNode("2", 0));
        NumericFlowAnalyzer.analyze(block(declaration("total", "0"), loop(assignment)));

        assertEquals("+", assignment.getAnnotation(NumericFlowAnalyzer.PRIMITIVE_INTEGER_ASSIGNMENT));
    }

    @Test
    void rejectsAReferencedLexicalBeforeAnnotatingItsLoopAssignment() {
        BinaryOperatorNode assignment = assignment("total", scalar("total"), new NumberNode("2", 0));
        NumericFlowAnalyzer.analyze(block(
                declaration("total", "0"),
                new OperatorNode("\\", scalar("total"), 0),
                loop(assignment)));

        assertNull(assignment.getAnnotation(NumericFlowAnalyzer.PRIMITIVE_INTEGER_ASSIGNMENT));
    }

    @Test
    void annotatesTheClosedMultiplyAddModulusRecurrenceInsideAForLoop() {
        BinaryOperatorNode recurrence = new BinaryOperatorNode("=", scalar("value"),
                new BinaryOperatorNode("%",
                        new BinaryOperatorNode("+",
                                new BinaryOperatorNode("*", scalar("value"), new NumberNode("33", 0), 0),
                                scalar("_"), 0),
                        new NumberNode("1000003", 0), 0), 0);
        NumericFlowAnalyzer.analyze(block(declaration("value", "11"), loop(recurrence)));

        assertEquals(Boolean.TRUE, recurrence.getAnnotation(
                NumericFlowAnalyzer.PRIMITIVE_MULTIPLY_ADD_MODULUS_ASSIGNMENT));
    }

    private static BlockNode block(Node... statements) {
        return new BlockNode(List.of(statements), 0);
    }

    private static For3Node loop(Node bodyStatement) {
        return new For3Node(null, true, null, null, null, block(bodyStatement), null,
                false, false, 0);
    }

    private static BinaryOperatorNode declaration(String name, String value) {
        return new BinaryOperatorNode("=", new OperatorNode("my", scalar(name), 0),
                new NumberNode(value, 0), 0);
    }

    private static BinaryOperatorNode assignment(String target, Node left, Node right) {
        return new BinaryOperatorNode("=", scalar(target),
                new BinaryOperatorNode("+", left, right, 0), 0);
    }

    private static OperatorNode scalar(String name) {
        return new OperatorNode("$", new IdentifierNode(name, 0), 0);
    }
}
