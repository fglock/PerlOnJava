package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class TemporaryReturnOwnerTraversalTest {
    @Test
    void implicitSubReturnPopsTheRootSlotItPushedBeforeScopeCleanup() throws Exception {
        for (boolean useInterpreter : new boolean[] {false, true}) {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                java.util.List<RuntimeBase> rootsBefore = MortalList.snapshotTemporaryRoots();
                CompilerOptions options = new CompilerOptions();
                options.fileName = "temporary_return_owner_root_balance.t";
                options.useInterpreter = useInterpreter;
                options.code = "our $destroyed = 0;"
                        + "sub TemporaryReturnOwnerTraversal::Object::DESTROY "
                        + "{ ++$main::destroyed }"
                        + "sub make_result {"
                        + "  my $value = bless {}, 'TemporaryReturnOwnerTraversal::Object';"
                        + "  my @cleanup_scope = (1);"
                        + "  $value;"
                        + "}"
                        + "my $returned = make_result();"
                        + "die 'returned object destroyed too early' if $destroyed;"
                        + "undef $returned;"
                        + "die 'returned object not destroyed after release' "
                        + "  unless $destroyed == 1;"
                        + "1;";

                PerlLanguageProvider.executePerlCode(options, false);

                assertEquals(rootsBefore, MortalList.snapshotTemporaryRoots(),
                        "implicit return scope cleanup must balance its temporary-root stack "
                                + "(interpreter=" + useInterpreter + ")");
            }
        }
    }

    @Test
    void lexicalCleanupDoesNotWalkUnrelatedTemporaryReferentGraphs() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash metadata = new RuntimeHash();
            metadata.elements = new AbstractMap<>() {
                @Override
                public Set<Map.Entry<String, RuntimeScalar>> entrySet() {
                    throw new AssertionError("lexical cleanup walked an unrelated referent graph");
                }
            };
            RuntimeScalar expression = metadata.createAnonymousReference();
            RuntimeArray local = new RuntimeArray();
            local.localBindingExists = true;
            MortalList.pushTemporaryRoot(expression);
            try {
                assertDoesNotThrow(() -> MortalList.scopeExitCleanupArray(local));
                assertFalse(local.localBindingExists);
            } finally {
                MortalList.popTemporaryRoot(expression);
            }
        }
    }

    @Test
    void expressionListsStillBorrowTheirDirectAggregateReferents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeList expression = new RuntimeList();
            expression.elements.add(target.createReference());
            MortalList.pushTemporaryRoot(expression);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(target));
            } finally {
                MortalList.popTemporaryRoot(expression);
            }
        }
    }
}
