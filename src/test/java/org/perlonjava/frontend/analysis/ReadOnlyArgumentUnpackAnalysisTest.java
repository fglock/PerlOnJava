package org.perlonjava.frontend.analysis;

import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.SubroutineNode;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyArgumentUnpackAnalysisTest {
    @Test
    void recognizesStableArrayBackedMethodFields() throws Exception {
        String source = "package ArrayMethodFields; "
                + "sub add { my ($self, $amount) = @_; "
                + "$self->[0] += $amount; $self->[1] += $amount; "
                + "return $self->[0] + $self->[1]; }";
        ReadOnlyArgumentUnpackAnalysis.Candidate candidate = analyzeSub(source, "add");

        assertNotNull(candidate);
        assertEquals(List.of(0, 1), candidate.arrayIndices());
        assertTrue(candidate.freshScalarReturn());
        assertTrue(candidate.stableArrayFieldCells());
    }

    @Test
    void keepsTheExistingStableHashFieldAnalysis() throws Exception {
        String source = "package HashMethodFields; "
                + "sub add { my ($self, $amount) = @_; "
                + "$self->{x} += $amount; $self->{y} += $amount; "
                + "return $self->{x} + $self->{y}; }";
        ReadOnlyArgumentUnpackAnalysis.Candidate candidate = analyzeSub(source, "add");

        assertNotNull(candidate);
        assertEquals(List.of("x", "y"), candidate.hashKeys());
        assertTrue(candidate.freshScalarReturn());
        assertTrue(candidate.stableHashFieldCells());
    }

    private static ReadOnlyArgumentUnpackAnalysis.Candidate analyzeSub(
            String source, String name) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "read_only_argument_unpack_analysis.t";
        options.code = source;
        EmitterContext context = new EmitterContext(new JavaClassInfo(),
                new ScopedSymbolTable(), null, null, RuntimeContextType.VOID, true,
                new ErrorMessageUtil(options.fileName, List.of()), options, new RuntimeArray());
        Node root = new Parser(context, new Lexer(source).tokenize()).parse();
        List<SubroutineNode> subroutines = new ArrayList<>();
        collectSubroutines(root, Collections.newSetFromMap(new IdentityHashMap<>()), subroutines);
        return subroutines.stream().filter(subroutine -> name.equals(subroutine.name))
                .findFirst().map(subroutine -> ReadOnlyArgumentUnpackAnalysis.analyze(subroutine.block))
                .orElse(null);
    }

    private static void collectSubroutines(Node node, Set<Node> seen,
                                           List<SubroutineNode> found) throws IllegalAccessException {
        if (node == null || !seen.add(node)) return;
        if (node instanceof SubroutineNode subroutine) {
            found.add(subroutine);
            return;
        }
        for (Field field : node.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object value = field.get(node);
            if (value instanceof Node child) {
                collectSubroutines(child, seen, found);
            } else if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item instanceof Node child) collectSubroutines(child, seen, found);
                }
            }
        }
    }
}
