package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class StaleStashPackageOwnerReleaseTest {
    @Test
    void compiledPerlRebindReleasesTheSavedMoveOnBothBackends() throws Exception {
        String source = """
                use strict;
                use warnings;
                use Scalar::Util qw(weaken);
                our @DESTROYED;
                our ($weak_value, $weak_stash);
                {
                    package StaleStashPackageOwnerPerlProbe::Value;
                    sub DESTROY { push @main::DESTROYED, 'value'; }
                }
                my $value = bless {}, 'StaleStashPackageOwnerPerlProbe::Value';
                $weak_value = $value;
                weaken($weak_value);
                my $stash;
                {
                    no strict 'refs';
                    ${'StaleStashPackageOwnerPerlProbeAbandoned::item'} = $value;
                    $stash = \\%StaleStashPackageOwnerPerlProbeAbandoned::;
                    $weak_stash = $stash;
                    weaken($weak_stash);
                    undef $value;
                    *StaleStashPackageOwnerPerlProbeAbandoned:: =
                            'StaleStashPackageOwnerPerlProbeOther::';
                }
                undef $stash;
                join ':', defined($weak_stash) ? 1 : 0,
                        defined($weak_value) ? 1 : 0, @DESTROYED;
                """;

        List<String> results = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                CompilerOptions options = new CompilerOptions();
                options.fileName = "<stale-stash-saved-move-owner>";
                options.code = source;
                options.useInterpreter = interpreter;

                String result = PerlLanguageProvider.executePerlCode(options, false)
                        .scalar().toString();

                results.add(result);
                diagnostics.add(describeWeakStashState(interpreter));
            }
        }

        assertEquals(List.of("0:0:value", "0:0:value"), results,
                "compiled Perl must release the saved namespace move on both backends: "
                        + diagnostics);
    }

    private static String describeWeakStashState(boolean interpreter) {
        RuntimeScalar weakStash = GlobalVariable.globalVariables.get("main::weak_stash");
        if (weakStash == null || !(weakStash.value instanceof RuntimeStash stale)) {
            return "interpreter=" + interpreter + " weak stash already cleared";
        }
        String owners = stale.ownerSlotSnapshot().stream()
                .map(slot -> slot.kind().name())
                .sorted()
                .toList().toString();
        RuntimeGlob move = stale.savedNamespaceMove;
        String moveDescription = move == null ? "null"
                : "id=" + System.identityHashCode(move)
                        + " slots=" + move.ownerSlotSnapshot().stream()
                                .map(slot -> slot.kind().name()).sorted().toList()
                        + " scalars=" + (move.namespaceMove == null
                                ? "null" : move.namespaceMove.scalars.keySet())
                        + " hashes=" + (move.namespaceMove == null ? "null"
                                : move.namespaceMove.hashes.entrySet().stream()
                                        .map(entry -> entry.getKey() + "#"
                                                + System.identityHashCode(entry.getValue())
                                                + (entry.getValue() == stale ? "=STALE" : ""))
                                        .toList());
        return "interpreter=" + interpreter + " stale.id=" + System.identityHashCode(stale)
                + " stale.namespace=" + stale.namespace + " stale.refCount=" + stale.refCount
                + " stale.localBinding=" + stale.localBindingExists
                + " stale.owners=" + owners + " move=" + moveDescription;
    }

    @Test
    void rebindingStashReleasesItsPackageRootIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String source = "StaleStashPackageOwnerReleaseTest::Source::";
            String replacement = "StaleStashPackageOwnerReleaseTest::Other::";
            RuntimeStash stale = (RuntimeStash) GlobalVariable.getGlobalHash(source);
            RuntimeStash current = (RuntimeStash) GlobalVariable.getGlobalHash(replacement);
            String childKey = source + "Inner::";
            RuntimeStash child = (RuntimeStash) GlobalVariable.getGlobalHash(childKey);
            String scalarKey = source + "item";
            RuntimeScalar packageScalar = new RuntimeScalar();
            RuntimeHash value = new RuntimeHash();
            RuntimeScalar weakValue = value.createAnonymousReference();
            WeakRefRegistry.weaken(weakValue);
            packageScalar.set(value.createAnonymousReference());
            GlobalVariable.globalVariables.put(scalarKey, packageScalar);
            RuntimeScalar stashReference = stale.createReference();
            RuntimeScalar lexical = new RuntimeScalar();

            try {
                lexical.set(stashReference);
                assertEquals(1, stale.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                GlobalVariable.getGlobalIO(source).set(new RuntimeScalar(replacement));
                RuntimeGlob savedMove = stale.savedNamespaceMove;

                assertSame(current, GlobalVariable.globalHashes.get(source));
                assertNotSame(stale, current);
                assertNotNull(savedMove);
                assertEquals(1, savedMove.ownerSlotCount(
                        PerlOwnerSlot.Kind.STASH_NAMESPACE_MOVE));
                assertEquals(1, child.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
                assertEquals(1, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertFalse(stale.localBindingExists);
                assertEquals(0, stale.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT),
                        "the replaced package hash must release its root owner identity");

                lexical.undefine();

                assertEquals(Integer.MIN_VALUE, stale.refCount,
                        "the final Perl reference must retire the stale stash");
                assertEquals(0, savedMove.ownerSlotCount(
                        PerlOwnerSlot.Kind.STASH_NAMESPACE_MOVE));
                assertEquals(0, child.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(RuntimeScalarType.UNDEF, weakValue.type,
                        "releasing the saved move must clean its package scalar contents");
            } finally {
                lexical.undefine();
                GlobalVariable.globalHashes.remove(source);
                GlobalVariable.globalHashes.remove(replacement);
                GlobalVariable.globalHashes.remove(childKey);
                GlobalVariable.globalVariables.remove(scalarKey);
                GlobalVariable.globalIORefs.remove(source);
                GlobalVariable.globalIORefs.remove(replacement);
                GlobalVariable.clearStashAlias(source);
            }
        }
    }
}
