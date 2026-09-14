package org.perlonjava.frontend.analysis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.astnode.ArrayLiteralNode;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.For1Node;
import org.perlonjava.frontend.astnode.SubroutineNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class PrivateNativeArrayAnalyzerTest {
    @Test
    void annotatesAnEmptyArrayWithDirectLiteralWordWrites() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new BinaryOperatorNode("=", element("grid", 0), new NumberNode("42", 0), 0),
                new BinaryOperatorNode("=", element("grid", 1),
                        new BinaryOperatorNode("^", element("grid", 0), new NumberNode("7", 0), 0), 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void acceptsTheParserShapeForMyArrayEqualsEmptyList() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, new ListNode(List.of(), 0), 0),
                new BinaryOperatorNode("=", element("grid", 0), new NumberNode("42", 0), 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void allowsAnElementReferenceAsAMaterializationBoundary() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new BinaryOperatorNode("=", element("grid", 0), new NumberNode("1", 0), 0),
                new OperatorNode("\\", element("grid", 0), 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void allowsAnArgumentEscapeAsAMaterializationBoundary() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new BinaryOperatorNode("=", element("grid", 0), new NumberNode("1", 0), 0),
                new BinaryOperatorNode("(", new IdentifierNode("retain", 0), array("grid"), 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void rejectsNestedClosureEvenWhenItDoesNotMentionTheArray() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new SubroutineNode(null, null, List.of(), block(), false, 0)));

        assertNull(declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void allowsADynamicIndexAsAMaterializationBoundary() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new BinaryOperatorNode("=", element("grid", 0), new NumberNode("1", 0), 0),
                new BinaryOperatorNode("=", element("grid", scalar("index")), new NumberNode("1", 0), 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void rejectsAWordReadBeforeItsElementWasInitialized() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new BinaryOperatorNode("=", element("grid", 1),
                        new BinaryOperatorNode("^", element("grid", 0), new NumberNode("7", 0), 0), 0)));

        assertNull(declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void annotatesAWriteOnlyBoundedLoopInitializer() {
        OperatorNode declaration = declaration("grid");
        OperatorNode index = scalar("i");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new For1Node(null, true, new OperatorNode("my", scalar("i"), 0),
                        new BinaryOperatorNode("..", new NumberNode("0", 0), new NumberNode("31", 0), 0),
                        block(new BinaryOperatorNode("=", element("grid", index),
                                new BinaryOperatorNode("^", scalar("i"), new NumberNode("7", 0), 0), 0)),
                        null, 0)));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
        assertEquals(Boolean.TRUE, index.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_LOOP_INDEX));
    }

    @Test
    void rejectsABoundedLoopThatReadsAnUninitializedCarrierElement() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                new For1Node(null, true, new OperatorNode("my", scalar("i"), 0),
                        new BinaryOperatorNode("..", new NumberNode("0", 0), new NumberNode("31", 0), 0),
                        block(new BinaryOperatorNode("=", element("grid", scalar("i")),
                                new BinaryOperatorNode("^", element("grid", scalar("i")), new NumberNode("7", 0), 0), 0)),
                        null, 0)));

        assertNull(declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    @Test
    void acceptsABoundedLoopReadAfterACompletePriorInitializer() {
        OperatorNode declaration = declaration("grid");
        PrivateNativeArrayAnalyzer.analyze(block(
                new BinaryOperatorNode("=", declaration, emptyArray(), 0),
                loop("i", new BinaryOperatorNode("^", scalar("i"), new NumberNode("7", 0), 0)),
                loop("i", new BinaryOperatorNode("^", element("grid", scalar("i")), new NumberNode("17", 0), 0))));

        assertEquals(Boolean.TRUE, declaration.getAnnotation(PrivateNativeArrayAnalyzer.PRIVATE_NATIVE_ARRAY));
    }

    private static For1Node loop(String indexName, Node expression) {
        return new For1Node(null, true, new OperatorNode("my", scalar(indexName), 0),
                new BinaryOperatorNode("..", new NumberNode("0", 0), new NumberNode("31", 0), 0),
                block(new BinaryOperatorNode("=", element("grid", scalar(indexName)), expression, 0)), null, 0);
    }

    private static BlockNode block(Node... statements) {
        return new BlockNode(List.of(statements), 0);
    }

    private static OperatorNode declaration(String name) {
        return new OperatorNode("my", array(name), 0);
    }

    private static OperatorNode array(String name) {
        return new OperatorNode("@", new IdentifierNode(name, 0), 0);
    }

    private static BinaryOperatorNode element(String name, int index) {
        return element(name, new NumberNode(Integer.toString(index), 0));
    }

    private static BinaryOperatorNode element(String name, Node index) {
        return new BinaryOperatorNode("[", scalar(name), new ArrayLiteralNode(List.of(index), 0), 0);
    }

    private static OperatorNode scalar(String name) {
        return new OperatorNode("$", new IdentifierNode(name, 0), 0);
    }

    private static ArrayLiteralNode emptyArray() {
        return new ArrayLiteralNode(List.of(), 0);
    }
}
