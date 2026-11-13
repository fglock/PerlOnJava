package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DbicTailCallArgumentFrameCleanupTest {

    @Test
    void nestedTryTinyGotoReleasesCallbackAndArgumentFrame() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<dbic-tail-call-owner-cleanup>";
            options.code = """
                    use strict;
                    use warnings;
                    use Scalar::Util qw(weaken);
                    use Try::Tiny;
                    our %weak_callbacks;
                    for my $function (qw(try catch finally)) {
                        no strict 'refs';
                        no warnings 'redefine';
                        my $original = \\&{"Try::Tiny::$function"};
                        *{"Try::Tiny::$function"} = sub (&;@) {
                            if ($function eq 'try') {
                                push @{$weak_callbacks{$function}}, $_[0];
                                weaken($weak_callbacks{$function}[-1]);
                            }
                            goto $original;
                        };
                    }
                    {
                        package Unit::TailCallOwner::Storage;
                        sub txn_do {
                            my ($self, $callback) = @_;
                            return Try::Tiny::try { $callback->() }
                                Try::Tiny::catch { die $_ };
                        }
                        sub _dbh_last_insert_id {
                            my ($self, $dbh, $source, $column) = @_;
                            my $id = Try::Tiny::try {
                                $dbh->last_insert_id(undef, undef, $source->name, $column);
                            };
                            return $id if defined $id;
                        }
                        package Unit::TailCallOwner::DBH;
                        sub last_insert_id { $_[0]->{id} }
                        package Unit::TailCallOwner::Source;
                        sub name { $_[0]->{name} }
                    }
                    {
                        my $dbh = bless { id => 42 }, 'Unit::TailCallOwner::DBH';
                        my $source = bless { name => 'Artist' }, 'Unit::TailCallOwner::Source';
                        weaken(my $weak_dbh = $dbh);
                        weaken(my $weak_source = $source);
                        Unit::TailCallOwner::Storage->txn_do(sub {
                            Unit::TailCallOwner::Storage->_dbh_last_insert_id(
                                $dbh, $source, 'artist_id');
                        });
                    }
                    1;
                    """;
            RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
            RuntimeArray.push(options.inc, new RuntimeScalar("src/test/resources/unit/lib"));
            PerlLanguageProvider.executePerlCode(options, true);

            RuntimeHash weakCallbacks = GlobalVariable.getGlobalHash("main::weak_callbacks");
            assertNotNull(weakCallbacks);
            RuntimeScalar triesValue = weakCallbacks.elements.get("try");
            assertNotNull(triesValue);
            assertTrue(triesValue.value instanceof RuntimeArray);
            RuntimeArray tries = (RuntimeArray) triesValue.value;
            assertEquals(2, tries.elements.size(), "both try callback blocks must be observed");

            RuntimeScalar retainedCallback = tries.elements.get(1);
            assertNotNull(retainedCallback);
            RuntimeBase callback = retainedCallback.value instanceof RuntimeBase base ? base : null;
            int argsDepth = runtime.executionState().argsStack.size();
            String ownerTrace = callback == null ? "cleared"
                    : callback.ownerTraceSnapshot() + "; " + untrackedOwnerDetails(callback);
            assertEquals(0, argsDepth,
                    "tail-call left an argument frame active; " + ownerTrace);
            if (callback != null) {
                assertTrue(WeakRefRegistry.isweak(retainedCallback),
                        "a retained callback probe must be weak; " + ownerTrace);
            }
            assertEquals(RuntimeScalarType.UNDEF, retainedCallback.type,
                    "inner Try::Tiny callback remains after goto; weakCell#"
                            + System.identityHashCode(retainedCallback) + " argsDepth="
                            + argsDepth + "; " + ownerTrace);
            assertNull(retainedCallback.value,
                    "cleared callback probe still contains a referent; " + ownerTrace);
        }
    }

    private static String untrackedOwnerDetails(RuntimeBase referent) {
        StringBuilder details = new StringBuilder("untrackedOwners=[");
        boolean first = true;
        for (PerlOwnerSlot slot : referent.ownerSlotSnapshot()) {
            if (!slot.isActive() || slot.kind() != PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) {
                continue;
            }
            RuntimeScalar owner = slot.untrackedScalarOwner();
            if (!first) details.append(", ");
            first = false;
            details.append("slot#").append(slot.identity()).append(" scalar#");
            if (owner == null) {
                details.append("collected");
                continue;
            }
            details.append(System.identityHashCode(owner))
                    .append(" weak=").append(WeakRefRegistry.isweak(owner))
                    .append(" owns=").append(owner.refCountOwned)
                    .append(" scopeExited=").append(owner.scopeExited)
                    .append(" local=").append(owner.localBindingExists)
                    .append(" transient=").append(owner.isTransientArgumentValueOwner())
                    .append(" registered=").append(MyVarCleanupStack.isRegistered(owner));
            if (owner.containerOwner != null) {
                details.append(" container=")
                        .append(owner.containerOwner.getClass().getSimpleName())
                        .append('#').append(System.identityHashCode(owner.containerOwner))
                        .append(" perlOwned=").append(owner.containerOwner.hasPerlStrongOwner())
                        .append(" packageRoot=").append(owner.containerOwner.isPackageGlobalRoot)
                        .append(" local=").append(owner.containerOwner.localBindingExists)
                        .append(" registered=")
                        .append(MyVarCleanupStack.isRegistered(owner.containerOwner));
            }
        }
        return details.append(']').toString();
    }
}
