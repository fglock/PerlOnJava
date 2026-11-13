package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReentrantScalarOverwriteWeakOwnerTest {
    private static final String SOURCE = """
            use strict;
            use warnings;
            use Scalar::Util qw(weaken);
            {
                package Issue1649::ReentrantOverwriteWeakOwner;
                sub new { bless { name => $_[1] }, $_[0] }
                sub DESTROY {
                    my ($self) = @_;
                    if ($self->{name} eq 'old') {
                        $main::keeper = $main::slot;
                        Scalar::Util::weaken($main::slot);
                    }
                }
            }
            our ($slot, $keeper);
            $slot = Issue1649::ReentrantOverwriteWeakOwner->new('old');
            $slot = Issue1649::ReentrantOverwriteWeakOwner->new('replacement');
            1;
            """;

    @Test
    void destructorWeakeningRemainsTheOnlyOwnerTransition() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                CompilerOptions options = new CompilerOptions();
                options.fileName = "<issue-1649-reentrant-weak-owner>";
                options.code = SOURCE;
                options.useInterpreter = interpreter;
                PerlLanguageProvider.executePerlCode(options, false);

                RuntimeScalar slot = GlobalVariable.getGlobalVariable("main::slot");
                RuntimeScalar keeper = GlobalVariable.getGlobalVariable("main::keeper");
                RuntimeBase referent = (RuntimeBase) keeper.value;

                assertSame(referent, slot.value);
                assertTrue(WeakRefRegistry.isweak(slot));
                assertFalse(slot.refCountOwned,
                        "the outer overwrite must not restore ownership after DESTROY weakens the slot");
                PerlOwnerSlot slotOwner = slot.countedOwnerSlot();
                assertFalse(slotOwner != null && slotOwner.isActive()
                                && slotOwner.referent() == referent,
                        "a weakened slot must not remain in the referent's counted-owner ledger");
                assertEquals(1, referent.refCount,
                        "the keeper is the only strong reference after the callback");
                assertEquals(1, referent.countedOwnerSlotCount(),
                        "the owner ledger must contain only the keeper's counted edge");
            }
        }
    }
}
