package org.perlonjava.frontend.analysis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PristineArgsSnapshotAnalysisTest extends PerlRuntimeTestBase {

    @Test
    void generatedNamedSubsCarryOnlyProvenSafeSnapshotMetadata() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "pristine_args_snapshot_analysis.t";
        options.code = "package PristineArgsSnapshotAnalysis; use Time::HiRes; "
                + "use Scalar::Util;"
                + "sub stable { my ($self, $name) = @_; return $self; }"
                + "sub arity_read { my ($self, $value) = @_; return @_ == 1; }"
                + "sub default_scalar_read { my ($self) = @_; "
                + "for (@$self) { $_->[1]--; } return $self; }"
                + "our @timer; our $MNOW;"
                + "sub timer_unpack($$$) { my ($a, $b, $c) = @_; return $a; }"
                + "sub timer_explicit_call($$$) { my ($a, $b, $c) = @_; "
                + "_update_clock(); return $a; }"
                + "sub timer_weaken_call($$$) { my ($a, $b, $c) = @_; "
                + "weaken $a; return $a; }"
                + "sub timer_nested_write($$$) { my ($a, $b, $c) = @_; "
                + "my $self = sub { $_[0][0] = $b; }; return $a; }"
                + "sub timer_nested_shared_call($$$) { my ($a, $b, $c) = @_; "
                + "my $self = sub { &$c; }; return $a; }"
                + "sub timer_candidate($$$) { my ($after, $interval, $cb) = @_; "
                + "_update_clock(); my $self; if ($interval) { $self = "
                + "[$MNOW + $after, sub { $_[0][0] = $_[0][0] + $interval; "
                + "push @timer, $_[0]; weaken $timer[-1]; &$cb; }]; } "
                + "else { $self = [$MNOW + $after, $cb]; } "
                + "push @timer, $self; weaken $timer[-1]; $self; }"
                + "sub shifted { my ($self, $name) = @_; shift @_; return $self; }"
                + "sub slot_write { $_[0] = 3; return $_[0]; }"
                + "my $arg = 1; stable(1, 2); arity_read(1, 2); "
                + "default_scalar_read([], 2); shifted(1, 2); slot_write($arg); 1;";
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeCode stable = namedCode("PristineArgsSnapshotAnalysis::stable");
        RuntimeCode arityRead = namedCode("PristineArgsSnapshotAnalysis::arity_read");
        RuntimeCode defaultScalarRead = namedCode(
                "PristineArgsSnapshotAnalysis::default_scalar_read");
        RuntimeCode timerCandidate = materializeNamedCode(
                "PristineArgsSnapshotAnalysis::timer_candidate");
        boolean timerUnpack = materializeNamedCode("PristineArgsSnapshotAnalysis::timer_unpack")
                .requiresPristineArgsSnapshot();
        boolean timerCall = materializeNamedCode("PristineArgsSnapshotAnalysis::timer_explicit_call")
                .requiresPristineArgsSnapshot();
        boolean timerWeaken = materializeNamedCode("PristineArgsSnapshotAnalysis::timer_weaken_call")
                .requiresPristineArgsSnapshot();
        boolean timerNestedWrite = materializeNamedCode("PristineArgsSnapshotAnalysis::timer_nested_write")
                .requiresPristineArgsSnapshot();
        boolean timerNestedSharedCall = materializeNamedCode(
                "PristineArgsSnapshotAnalysis::timer_nested_shared_call")
                .requiresPristineArgsSnapshot();
        RuntimeCode shifted = namedCode("PristineArgsSnapshotAnalysis::shifted");
        RuntimeCode slotWrite = namedCode("PristineArgsSnapshotAnalysis::slot_write");
        assertFalse(stable.requiresPristineArgsSnapshot());
        assertFalse(arityRead.requiresPristineArgsSnapshot());
        assertFalse(defaultScalarRead.requiresPristineArgsSnapshot());
        assertFalse(timerCandidate.requiresPristineArgsSnapshot(),
                "component effects: unpack=" + timerUnpack + ", explicit call=" + timerCall
                        + ", weaken=" + timerWeaken + ", nested write=" + timerNestedWrite
                        + ", nested shared call=" + timerNestedSharedCall);
        assertTrue(shifted.requiresPristineArgsSnapshot());
        assertTrue(slotWrite.requiresPristineArgsSnapshot());
        RuntimeCode time = namedCode("Time::HiRes::time");
        RuntimeCode clockGettime = namedCode("Time::HiRes::clock_gettime");
        RuntimeCode weaken = namedCode("Scalar::Util::weaken");
        assertFalse(time.requiresJvmClosureFrame());
        assertFalse(clockGettime.requiresJvmClosureFrame());
        assertFalse(weaken.requiresJvmClosureFrame());
        assertFalse(time.requiresPristineArgsSnapshot());
        assertFalse(clockGettime.requiresPristineArgsSnapshot());
        assertFalse(weaken.requiresPristineArgsSnapshot());
    }

    @Test
    void simpleLexicalUnpackDoesNotMutateArgumentSlots() {
        OperatorNode lexicalTarget = new OperatorNode("my",
                new ListNode(List.of(scalar("self"), scalar("name")), 0), 0);
        BinaryOperatorNode assignment = new BinaryOperatorNode(
                "=", lexicalTarget, argumentArray(), 0);

        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(assignment));

        OperatorNode outerMy = new OperatorNode("my",
                new BinaryOperatorNode("=",
                        new ListNode(List.of(scalar("self")), 0), argumentArray(), 0), 0);
        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(outerMy));
    }

    @Test
    void parsedPrototypedSubroutineUnpackIsProvenSafe() {
        String source = "my $timer0 = sub ($$$) { my ($a, $b, $c) = @_; return $a; };"
                + "my $timer1 = sub ($$$) { my ($a, $b, $c) = @_; _update_clock(); return $a; };"
                + "my $timer2 = sub ($$$) { my ($a, $b, $c) = @_; weaken $a; return $a; };"
                + "my $timer3 = sub ($$$) { my ($a, $b, $c) = @_; "
                + "my $self = sub { $_[0][0] = $b; }; return $a; };"
                + "my $timer4 = sub ($$$) { my ($a, $b, $c) = @_; "
                + "my $self = sub { &$c; }; return $a; };"
                + "my $timer5 = sub ($$$) { my ($after, $interval, $cb) = @_; "
                + "_update_clock(); my $self; if ($interval) { $self = "
                + "[$MNOW + $after, sub { $_[0][0] = $_[0][0] + $interval; "
                + "push @timer, $_[0]; weaken $timer[-1]; &$cb; }]; } "
                + "else { $self = [$MNOW + $after, $cb]; } "
                + "push @timer, $self; weaken $timer[-1]; $self; };";
        CompilerOptions options = new CompilerOptions();
        options.fileName = "pristine_args_prototype.t";
        options.code = source;
        EmitterContext context = new EmitterContext(new JavaClassInfo(),
                new ScopedSymbolTable(), null, null, RuntimeContextType.VOID, true,
                new ErrorMessageUtil(options.fileName, List.of()), options, new RuntimeArray());
        Parser parser = new Parser(context, new Lexer(source).tokenize());
        Node root = parser.parse();
        List<SubroutineNode> subroutines = new ArrayList<>();
        collectSubroutines(root, Collections.newSetFromMap(new IdentityHashMap<>()), subroutines);
        List<Boolean> snapshotRequired = subroutines.stream()
                .map(subroutine -> PristineArgsSnapshotAnalysis.requiresSnapshot(subroutine.block))
                .toList();

        assertFalse(snapshotRequired.get(snapshotRequired.size() - 1),
                "component effects: " + snapshotRequired);
    }

    private static void collectSubroutines(Node node, Set<Node> seen,
                                           List<SubroutineNode> found) {
        if (node == null || !seen.add(node)) return;
        if (node instanceof SubroutineNode subroutine) {
            found.add(subroutine);
            return;
        }
        try {
            for (Field field : node.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value = field.get(node);
                if (value instanceof Node child) {
                    collectSubroutines(child, seen, found);
                } else if (value instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item instanceof Node child) {
                            collectSubroutines(child, seen, found);
                        }
                    }
                } else if (value instanceof Map<?, ?> map) {
                    for (Object item : map.values()) {
                        if (item instanceof Node child) {
                            collectSubroutines(child, seen, found);
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void mutatingArgumentArrayOrSlotKeepsSnapshot() {
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new BinaryOperatorNode("=", argumentArray(), new ListNode(0), 0)));

        BinaryOperatorNode argumentSlot = new BinaryOperatorNode(
                "[", scalar("_"), new NumberNode("0", 0), 0);
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new BinaryOperatorNode("=", argumentSlot, new NumberNode("1", 0), 0)));

        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("shift", null, 0)));
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("shift", argumentArray(), 0)));
    }

    @Test
    void defaultScalarAndArityReadDoNotAliasOrMutateAtSignUnderscore() {
        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(
                scalar("_")));
        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new BinaryOperatorNode("==", argumentArray(),
                        new NumberNode("1", 0), 0)));
    }

    @Test
    void unknownArgumentArrayUseKeepsSnapshot() {
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("unknown", argumentArray(), 0)));
    }

    @Test
    void aliasesEvalAndSharedArgumentControlFlowKeepSnapshot() {
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("\\", argumentArray(), 0)));
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("eval", new StringNode("$_[0] = 1", 0), 0)));
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(
                new OperatorNode("goto", new ListNode(0), 0)));

        OperatorNode sharedCallTarget = new OperatorNode("&",
                new IdentifierNode("callee", 0), 0);
        BinaryOperatorNode sharedCall = new BinaryOperatorNode("(",
                sharedCallTarget, new ListNode(0), 0);
        sharedCall.setAnnotation("shareCallerArgs", true);
        assertTrue(PristineArgsSnapshotAnalysis.requiresSnapshot(sharedCall));
    }

    @Test
    void explicitNamedCallDoesNotShareCallerArguments() {
        OperatorNode directCallTarget = new OperatorNode("&",
                new IdentifierNode("callee", 0), 0);
        directCallTarget.setAnnotation("directNamedCall", true);
        BinaryOperatorNode explicitCall = new BinaryOperatorNode("(",
                directCallTarget, new ListNode(0), 0);

        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(explicitCall));
    }

    @Test
    void nestedSubroutineArgumentMutationDoesNotAffectEnclosingCv() {
        SubroutineNode nested = new SubroutineNode(null, null, List.of(),
                new BlockNode(List.of(new OperatorNode("shift", null, 0)), 0),
                false, 0);
        BlockNode enclosing = new BlockNode(List.of(nested), 0);

        assertFalse(PristineArgsSnapshotAnalysis.requiresSnapshot(enclosing));
    }

    private static OperatorNode argumentArray() {
        return new OperatorNode("@", new IdentifierNode("_", 0), 0);
    }

    private static OperatorNode scalar(String name) {
        return new OperatorNode("$", new IdentifierNode(name, 0), 0);
    }

    private static RuntimeCode namedCode(String name) {
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        return (RuntimeCode) codeRef.value;
    }

    private static RuntimeCode materializeNamedCode(String name) {
        RuntimeCode code = namedCode(name);
        if (code.compilerSupplier != null) code.compilerSupplier.get();
        return code;
    }
}
