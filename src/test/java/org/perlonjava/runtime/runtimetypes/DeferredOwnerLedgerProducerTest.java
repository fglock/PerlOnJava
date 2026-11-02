package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class DeferredOwnerLedgerProducerTest {
    private static final String SOURCE = """
            use strict;
            use warnings;
            use Scalar::Util qw(weaken);
            our (@roots, @unrelated, $destroyed, $weak);
            $destroyed = 0;
            {
                package Issue1649::DeferredOwnerNormalStore;
                sub DESTROY { ++$main::destroyed }
            }
            @unrelated = map { { nested => [$_, { leaf => $_ }] } } 1 .. 256;
            {
                my $value = bless {}, 'Issue1649::DeferredOwnerNormalStore';
                $weak = $value;
                weaken($weak);
                $roots[0] = [$value];
            }
            my $survived_under_root = defined($weak) && $destroyed == 0;
            $roots[0] = undef;
            my $released_after_root = !defined($weak) && $destroyed == 1;
            join ':', $survived_under_root ? 1 : 0,
                      $released_after_root ? 1 : 0, $destroyed;
            """;

    @Test
    void ordinaryNestedPackageStoreUsesOwnerSlotsWithoutRootQueries() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                CompilerOptions options = new CompilerOptions();
                options.fileName = "<issue-1649-deferred-owner-normal-store>";
                options.code = SOURCE;
                options.useInterpreter = interpreter;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);

                    assertEquals("1:1:1", result.scalar().toString(),
                            "the nested package owner preserves and then releases the value");
                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "ordinary deferred cleanup relies on current owner identities");
                    assertEquals(0, stats.scalarReachabilityQueries,
                            "ordinary stores do not need scalar reachability recovery");
                    assertEquals(0, stats.snapshotsBuilt,
                            "unrelated live roots do not cause lifecycle graph snapshots");
                    assertEquals(0, stats.externalRootSnapshotsBuilt,
                            "package roots are represented by owner slots");
                    assertEquals(0, stats.edgesInspected,
                            "cleanup cost does not grow with unrelated live aggregate edges");
                }
            }
        }
    }
}
