# Reachability and lifecycle: independent WIP handoff

## Status and scope (2026-10-05)

This candidate is independent of PR #1628 and based on master c38ef8adf.
All reachability and lifecycle changes and their new tests moved together.
Do not merge until the unresolved Catalyst cost is understood and this independent
candidate passes its own build, both execution backends, and CPAN acceptance.
The release acceptance goal remains open.

Included: early direct-root proofs, shared root snapshots, batched strong-cycle
checks, deferred cleanup of live DOM arrays with weak parent links, correct
package-root resurrection checks, accepted socket ownership, and process-pipe
alias ownership (#1597). Related lifecycle coverage also supports #1336.
The accepted-socket hunk in IOOperator moves here; directory fileno stays in UAT.
No existing master tests were removed. The six new tests move unchanged.

## Evidence and limitations

The combined parent d3f9ae5ac31b51810c0d3b83f302438e656a496a passed full make
and focused JVM/interpreter tests. The DOM regression passed 4/4 on system Perl,
failed 3/4 on the unfixed JVM, and passed 4/4 on both fixed backends. Unchanged
upstream dom.t then passed 132 top-level subtests / 1,418 assertions. Final run 11
also passed dom.t; it did not complete overall acceptance. These results are
historical evidence, not validation of this independent split.

Run 11 used a fresh isolated CPAN home. Catalyst-Runtime 5.90132 failed its
15,000-second prerequisite/setup guard before reaching the normal test suite.
A 60-second JFR recording contained 4,836 execution samples; 4,830 contained
ReachabilityWalker.isReachableFromRoots, 4,544 followScalar, 4,831
MortalList.processDeferredBase, and 1,850 RuntimeScalar.setLargeRefCounted.
The main stack included IdentityHashMap.resize/put, SetFromMap.add,
followScalar, isReachableFromRoots, processDeferredBase, flushAboveMark,
DestroyDispatch and CPAN/Module.pm:1152 / :1469. Counts are observations of this
recording, not throughput estimates or proof of an infinite loop. Dependency
installation continued later, so progress was observed.

The unblessed aggregate branch in processDeferredBase calls a fresh root walk
for each zero-count aggregate and can bypass lifecycleRootProof snapshot reuse.
Each query allocates an identity visited set and queue and seeds runtime roots.
MAX_VISITS bounds visited nodes per query; it does not bound the number of
queries or all edge/root-seeding work. This is the next investigation target.
Raw JFR contains environment/process metadata and must not be published.

## Reproduction and next steps

1. Build with `nice -n 19 make`, save full output, and keep the source immutable.
2. Run the permanent Perl regressions below on system Perl first, then JVM and
   interpreter with a timeout and captured output. Run the seven Java counter
   tests through make. Do not use elapsed timing as a performance assertion.
3. Add deterministic counts for the unblessed aggregate fallback: root queries,
   roots seeded, edges inspected, visited nodes and snapshot builds. Reproduce
   repeated cleanup through MortalList, not just a direct walker invocation.
4. Preserve weak semantics, deterministic DESTROY, resurrection detection,
   root mutation invalidation, sockets and pipes while eliminating repeated work.
5. Review sibling PR #1623's related cleanup changes before integration; they
   were not automatically imported and do not establish a Catalyst fix.
6. Rerun Catalyst normally in an isolated home; distinguish prerequisite setup
   from its test suite. Rerun unchanged Mojolicious dom.t and full acceptance.
   Only parser, #1269 and unsupported fork are authorized exclusions.

Fresh-home end-to-end trigger (after building this checkout):

```sh
acceptance_home=$(mktemp -d)
nice -n 19 timeout 28800 env PERLONJAVA_HOME="$acceptance_home" make test-cpan-release-acceptance > acceptance.log 2>&1
```

A timeout is a runaway guard, not a performance threshold. Preserve complete
logs and per-target outcomes. A previous isolated Catalyst suite ran 200 programs
and 3,798 assertions; that separate run cannot replace this candidate's gate.

## Permanent reproduction code

These project-owned tests are included verbatim so the handoff survives without
investigator-local artifacts. Run from a checkout of this PR.

### Migration manifest

```text
src/main/java/org/perlonjava/runtime/runtimetypes/DestroyDispatch.java
src/main/java/org/perlonjava/runtime/runtimetypes/GlobalDestruction.java
src/main/java/org/perlonjava/runtime/runtimetypes/LifecycleRuntimeState.java
src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java
src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java
src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java
src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java
src/test/java/org/perlonjava/runtime/runtimetypes/ReachabilityQueryCostTest.java
src/test/resources/unit/process_select_open2_gzip.t
src/test/resources/unit/socket_tcp_scope_exit_eof.t
src/test/resources/unit/refcount/global_destruction_weak_owner_not_resurrection.t
src/test/resources/unit/refcount/mojo_dom_incremental_weak_parent.t
src/test/resources/unit/refcount/mojo_dom_nested_li_parent_lifetime.t
src/main/java/org/perlonjava/runtime/operators/IOOperator.java (accepted-socket ownership hunk only)
```

### src/test/java/org/perlonjava/runtime/runtimetypes/ReachabilityQueryCostTest.java

```java
package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    @Test
    void directLiveScalarProofPrecedesGraphSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar root = target.createReference();
            MyVarCleanupStack.register(root);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            try {
                MortalList.LifecycleRootQueryStats stats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.DIRECT_SCALAR,
                        MortalList.lifecycleRootProof(target, stats));
                assertTrue(stats.liveScalarsInspected > 0);
                assertEquals(0, stats.externalRootQueries);
                assertEquals(0, stats.targetedWalkQueries);
                assertEquals(0, stats.fullSnapshotBuilds);
            } finally {
                MyVarCleanupStack.unregister(root);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void multipleLifecycleTargetsShareOneFullRootSnapshotPerDrain() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray third = new RuntimeArray();
            MyVarCleanupStack.register(first);
            MyVarCleanupStack.register(second);
            MyVarCleanupStack.register(third);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            MortalList.invalidateAllRootSnapshots();
            try {
                MortalList.LifecycleRootQueryStats firstStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(first, firstStats));
                assertEquals(1, firstStats.targetedWalkQueries,
                        "the first non-scalar target uses a short-circuit walk");
                assertEquals(0, firstStats.fullSnapshotBuilds);

                MortalList.LifecycleRootQueryStats secondStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(second, secondStats));
                assertEquals(1, secondStats.fullSnapshotBuilds,
                        "the second target promotes the drain to one shared snapshot");
                assertTrue(secondStats.fullSnapshotGraphNodesVisited > 0);

                MortalList.LifecycleRootQueryStats thirdStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(third, thirdStats));
                assertEquals(0, thirdStats.fullSnapshotBuilds,
                        "later targets reuse the snapshot instead of walking again");
                assertEquals(0, thirdStats.targetedWalkQueries);

                MortalList.invalidateLiveRootSnapshot();
                MortalList.LifecycleRootQueryStats invalidatedStats =
                        new MortalList.LifecycleRootQueryStats();
                assertEquals(MortalList.LifecycleRootProof.FULL_ROOT,
                        MortalList.lifecycleRootProof(third, invalidatedStats));
                assertEquals(1, invalidatedStats.targetedWalkQueries,
                        "root mutation invalidates the cached drain snapshot");
                assertEquals(0, invalidatedStats.fullSnapshotBuilds);
            } finally {
                MyVarCleanupStack.unregister(third);
                MyVarCleanupStack.unregister(second);
                MyVarCleanupStack.unregister(first);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void liveWeakReferentsSkipCycleGraphQueries() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            live.add(referent);
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(stats.weakReferentsExamined > 0);
                assertTrue(stats.liveReferentsSkipped > 0);
                assertEquals(0, stats.graphBuilds,
                        "live referents are retained by the ordinary root walk");
                assertEquals(0, stats.strongGraphNodesExpanded);
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void unreachableStrongCycleStillGetsCycleProtection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.elements.put("self", referent.createReference());
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(protectedSet.contains(referent));
                assertEquals(1, stats.graphBuilds);
                assertEquals(1, stats.strongGraphNodesExpanded);
                assertEquals(1, stats.cyclicNodesFound);
                assertTrue(stats.protectedGraphNodesVisited > 0);
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void acyclicWeakReferentThatLeadsToStrongCycleGetsCycleProtection() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeHash cycle = new RuntimeHash();
            referent.elements.add(cycle.createReference());
            cycle.elements.put("self", cycle.createReference());
            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, null);
                assertTrue(protectedSet.contains(referent));
                assertTrue(protectedSet.contains(cycle));
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void sharedStrongGraphIsExpandedOnceForMultipleWeakReferents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray shared = new RuntimeArray();
            RuntimeHash leaf = new RuntimeHash();
            first.elements.add(shared.createReference());
            second.elements.add(shared.createReference());
            shared.elements.add(leaf.createReference());
            RuntimeScalar firstWeak = first.createReference();
            RuntimeScalar secondWeak = second.createReference();
            WeakRefRegistry.weaken(firstWeak);
            WeakRefRegistry.weaken(secondWeak);
            Set<RuntimeBase> live = Collections.newSetFromMap(new IdentityHashMap<>());
            ReachabilityWalker.StrongCycleQueryStats stats =
                    new ReachabilityWalker.StrongCycleQueryStats();
            try {
                Set<RuntimeBase> protectedSet =
                        ReachabilityWalker.collectStrongCycleProtected(live, stats);
                assertTrue(protectedSet.isEmpty());
                assertEquals(1, stats.graphBuilds);
                assertEquals(4, stats.strongGraphNodesExpanded,
                        "the shared child graph must be indexed only once");
                assertEquals(0, stats.cyclicNodesFound);
            } finally {
                WeakRefRegistry.unweaken(secondWeak);
                WeakRefRegistry.unweaken(firstWeak);
            }
        }
    }

    @Test
    void targetSpecificWalkStopsAtTheFirstContainerForAnEarlyTarget() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray global = new RuntimeArray();
            global.elements.add(target.createReference());
            for (int i = 0; i < 500; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            GlobalVariable.globalArrays.put(
                    "ReachabilityQueryCostTest::global", global);
            int[] visitedNodes = {0};
            try {
                assertTrue(ReachabilityWalker.isReachableFromRoots(
                        target, false, visitedNodes));
                assertEquals(1, visitedNodes[0],
                        "the walk should return after inspecting the root array");
            } finally {
                GlobalVariable.globalArrays.remove(
                        "ReachabilityQueryCostTest::global");
            }
        }
    }
}
```

### src/test/resources/unit/process_select_open2_gzip.t

```perl
use strict;
use warnings;
use Test::More;
use IPC::Open2;
use IO::Select;
use File::Temp qw(tempfile);

plan skip_all => 'requires POSIX process pipes and gzip'
    if $^O eq 'MSWin32';
my $gzip = grep { -x "$_/gzip" } split /:/, ($ENV{PATH} // '');
plan skip_all => 'gzip is not installed' unless $gzip;

# Keep two regular file descriptors open before the process pipes. MIME-tools
# uses this same filter shape: read the input while both writing to and reading
# from a child process.
open my $input, '<', __FILE__ or die "open test source: $!";
my ($output, $output_path) = tempfile();
my ($child_out, $child_in);
my $pid = open2($child_out, $child_in, 'gzip -c');
my $read_select = IO::Select->new($child_out);
my $write_select = IO::Select->new($child_in);
my $bytes_read = 0;
my $timed_out = 0;
my $iterations = 0;
my $writer_fd_survived_ready_loop = 0;
my $reader_fd_survived_ready_loop = 0;

while ($read_select->count || $write_select->count) {
    $iterations++;
    my ($read_ready, $write_ready) = IO::Select->select(
        $read_select, $write_select, undef, 1);
    if (!defined $read_ready && !defined $write_ready) {
        $timed_out = 1;
        diag("process pipe select timed out at iteration $iterations; "
            . $write_select->as_string . '; fileno='
            . (defined fileno($child_in) ? fileno($child_in) : 'undef'));
        last;
    }

    for my $fh (@{$read_ready // []}) {
        $reader_fd_survived_ready_loop ||= defined fileno($child_out);
        my $buffer;
        my $count = sysread($fh, $buffer, 1024);
        if ($count) {
            print {$output} $buffer or die "write compressed output: $!";
            $bytes_read += $count;
        } else {
            $read_select->remove($fh);
            close $fh;
        }
    }

    for my $fh (@{$write_ready // []}) {
        $writer_fd_survived_ready_loop ||= defined fileno($child_in);
        my $buffer;
        my $count = read($input, $buffer, 1024);
        if ($count) {
            syswrite($fh, $buffer) == $count or die "write gzip input: $!";
        } else {
            $write_select->remove($fh);
            close $fh;
        }
    }
}

close $child_in if $write_select->count;
close $child_out if $read_select->count;
waitpid($pid, 0);
ok(!$timed_out, 'IO::Select reports the process pipe writer ready');
ok($bytes_read > 0, 'the child gzip output was drained');
ok($writer_fd_survived_ready_loop, 'writer fileno remains registered while ready');
ok($reader_fd_survived_ready_loop, 'reader fileno remains registered while ready');
ok(!defined fileno($child_in), 'closing the writer unregisters its fileno');
ok(!defined fileno($child_out), 'closing the reader unregisters its fileno');
close $input;
close $output;
unlink $output_path;

done_testing();
```

### src/test/resources/unit/socket_tcp_scope_exit_eof.t

```perl
use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use IO::Socket::INET;
use Test::More;

sub make_nonblocking {
    my ($socket) = @_;
    my $flags = fcntl($socket, F_GETFL, 0);
    die "F_GETFL: $!" unless defined $flags;
    my $set = fcntl($socket, F_SETFL, $flags | O_NONBLOCK);
    die "F_SETFL: $!" unless defined $set;
}

my $listener = IO::Socket::INET->new(
    Listen    => 5,
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Proto     => 'tcp',
    ReuseAddr => 1,
);
plan skip_all => "loopback listener unavailable: $!" unless $listener;
plan tests => 3;

my $peer = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $listener->sockport,
    Proto    => 'tcp',
) or die "client: $!";

{
    my $server = $listener->accept or die "accept: $!";
    is(syswrite($server, 'x'), 1, 'TCP peer writes before socket scope exits');
}

close $listener or die "close listener: $!";
make_nonblocking($peer);
is(sysread($peer, my $body, 1), 1, 'client reads the final response byte');
is(sysread($peer, my $eof, 1), 0, 'client observes EOF after the TCP handle leaves scope');
```

### src/test/resources/unit/refcount/global_destruction_weak_owner_not_resurrection.t

```perl
use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

SKIP: {
    skip 'child-process timeout launcher is unavailable on Windows', 2
        if $^O eq 'MSWin32';

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} <<'CHILD';
use strict;
use warnings;
use Scalar::Util qw(weaken);

{
    package GlobalWeakOwner::Destroy;
    sub cleanup { my $self = shift; return $self->{value} }
    sub DESTROY { my $self = shift; $self->cleanup }
}

our $object = bless { value => 1 }, 'GlobalWeakOwner::Destroy';
our $weak = $object;
weaken($weak);
CHILD
    close($script_fh) or die "close child script: $!";

    my ($output_fh, $output_name) = tempfile();
    open(my $saved_stderr, '>&', \*STDERR) or die "save stderr: $!";
    open(STDERR, '>&', $output_fh) or die "redirect stderr: $!";
    my $runner = $^X eq 'jperl' ? './jperl' : $^X;
    my $status = system('timeout', '60', $runner, $script_name);
    open(STDERR, '>&', $saved_stderr) or die "restore stderr: $!";
    close($saved_stderr);

    seek($output_fh, 0, 0);
    my $output = do { local $/; <$output_fh> };
    close($output_fh);
    unlink($script_name);
    unlink($output_name);

    is($status, 0, 'global destruction completes cleanly');
    unlike($output,
        qr/DESTROY created new reference to dead object 'GlobalWeakOwner::Destroy'/,
        'temporary destructor references are not reported as resurrection');
}

done_testing;
```

### src/test/resources/unit/refcount/mojo_dom_incremental_weak_parent.t

```perl
use strict;
use warnings;
use Test::More;

my $loaded = eval { require Mojo::DOM::HTML; 1 };
plan skip_all => 'Mojolicious is not installed' unless $loaded;

my @warnings;
my $dom;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $dom = Mojo::DOM::HTML->new;
    $dom->parse(
        '<html><head><title>Generated page</title></head>'
          . '<body><nav>Suggestion</nav></body></html>'
    );
}

is_deeply(\@warnings, [], 'incremental HTML parsing does not lose weak parent nodes');
my $tree = $dom->tree;
is($tree->[1][1], 'html', 'root retains the html node');
is($tree->[1][4][1], 'head', 'html retains the head node');
is($tree->[1][4][4][1], 'title', 'head retains the title node');
is($tree->[1][4][4][3], $tree->[1][4], 'title weak parent points to head');
is($tree->[1][5][1], 'body', 'html retains the body node');
is($tree->[1][5][4][1], 'nav', 'body retains the navigation node');

done_testing;
```

### src/test/resources/unit/refcount/mojo_dom_nested_li_parent_lifetime.t

```perl
use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

# Model Mojo::DOM::HTML's strong child arrays and weak parent links while it
# closes nested optional <li> tags and then starts the next outer list item.
sub close_tag {
    my ($current, $tag) = @_;
    my $next = $$current;
    while ($next && $next->[0] ne 'root') {
        if ($next->[1] eq $tag) {
            $$current = $next->[3];
            return;
        }
        $next = $next->[3];
    }
}

sub start_tag {
    my ($current, $tag) = @_;
    if ($tag eq 'li' && $$current->[0] ne 'root') {
        my $parent = $$current;
        while ($parent->[0] ne 'root' && $parent->[1] ne 'ul' && $parent->[1] ne 'ol') {
            close_tag($current, 'li') if $parent->[1] eq 'li';
            $parent = $parent->[3];
            last unless ref $parent;
        }
    }
    push @$$current, my $new = ['tag', $tag, {}, $$current];
    weaken $new->[3];
    $$current = $new;
}

sub text_node {
    my ($current, $text) = @_;
    push @$$current, my $new = ['text', $text, $$current];
    weaken $new->[2];
}

sub parse_list {
    my $current = my $tree = ['root'];
    start_tag(\$current, 'ul');
    text_node(\$current, "\n  ");
    start_tag(\$current, 'li');
    text_node(\$current, "\n    ");
    start_tag(\$current, 'ol');
    text_node(\$current, "\n      ");
    start_tag(\$current, 'li');
    text_node(\$current, "F\n      ");
    start_tag(\$current, 'li');
    text_node(\$current, "G\n    ");
    close_tag(\$current, 'ol');
    start_tag(\$current, 'li');
    return ($tree, $current);
}

my ($tree, $current) = parse_list();
ok(defined $current->[3], 'new outer list item retains its parent');
is($current->[3][1] // 'undef', 'ul', 'new outer list item points to the ul');
ok(isweak($current->[3]), 'parent link remains weak');
is($tree->[1][1], 'ul', 'the root retains the list tree');

done_testing;
```

## Related work

- [UAT PR #1628](https://github.com/fglock/PerlOnJava/pull/1628)
- [Related sibling PR #1623](https://github.com/fglock/PerlOnJava/pull/1623)
- [Pipe ownership #1597](https://github.com/fglock/PerlOnJava/issues/1597)
- [Lifecycle investigation #1336](https://github.com/fglock/PerlOnJava/issues/1336)

## Investigation tickets

- [Reachability/lifecycle #1642](https://github.com/fglock/PerlOnJava/issues/1642)
- [Mixed arithmetic UTF-8 flags #1643](https://github.com/fglock/PerlOnJava/issues/1643)
- [Interpreter Test::Mojo application #1644](https://github.com/fglock/PerlOnJava/issues/1644)

## Independent candidate validation (2026-10-05)

Source commit `63b9463d5` passed full `nice -n 19 make` (exit 0), including
seven ReachabilityQueryCostTest cases (0 failures/errors/skips). The five moved
Perl tests passed 22/22 assertions on system Perl, JVM and interpreter, with
unchanged Mojolicious 9.49 available for the optional incremental DOM test.
The global-destruction child program also ran directly on each backend (exit 0,
no resurrection warning), avoiding reliance on the parent test's default child
launcher. Documentation link checks passed. These gates qualify the independent
code/test split; Catalyst's repeated-root-query investigation and final CPAN
acceptance remain open.

Historical combined Math::Decimal 0.004 evidence: all ten *_pp.t files passed,
107,058 system-Perl assertions with one optional pod-coverage skip and 107,059
assertions on each PerlOnJava backend; cmp_pp.t contributed 62,210 per backend.
This was not rerun on the independent split and is not claimed as its acceptance.

- [Remaining release qualification #1646](https://github.com/fglock/PerlOnJava/issues/1646)

Independent delivery: [draft PR #1647](https://github.com/fglock/PerlOnJava/pull/1647).
Resume through [tracking issue #1642](https://github.com/fglock/PerlOnJava/issues/1642);
keep PR #1628 separate until an explicitly validated integration barrier.
