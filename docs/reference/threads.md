# Perl Threads

PerlOnJava supports Perl 5 interpreter threads (ithreads) on both execution
backends. Each child owns an isolated snapshot of its parent's `PerlRuntime`.
Ordinary values are cloned; values explicitly shared through
`threads::shared` retain common storage.

The bundled compatibility surface is:

| Module | Version | Status |
|---|---:|---|
| `threads` | 2.43 | Supported |
| `threads::shared` | 1.74 | Supported |
| `Thread::Queue` | 3.14 | Supported |
| `Thread::Semaphore` | 2.13 | Supported |

The unchanged upstream test distributions for these four modules contain 64
files and 1,891 assertions. They pass on the JVM compiler and bytecode
interpreter with Java virtual and platform carriers.

## Snapshot and Shared Storage

`threads->create` snapshots the entry code, arguments, globals, closures, and
reachable Perl graph. Identity, aliases, cycles, weak references, blessing,
ties, and closure state are preserved within the child graph, but ordinary
parent and child storage evolves independently. Results and uncaught errors are
cloned back across the same boundary when the parent calls `join`.

Use `threads::shared` only for state that must be common:

- `share($scalar)` preserves the scalar value and makes its storage shared.
- `share(@array)` and `share(%hash)` follow Perl's destructive aggregate
  semantics and start with empty shared storage.
- `shared_clone($value)` recursively publishes a preserving shared copy.
- `lock`, `cond_wait`, `cond_timedwait`, `cond_signal`, and `cond_broadcast`
  provide recursive lexical locking and condition coordination.
- Nested aggregate fetches return runtime-local proxy references over common
  storage. Local reblessing stays local until that view is stored back, and
  final `DESTROY` ownership remains global across views.
- Tied scalars retain runtime-local callback state. Sharing an already tied
  array or hash converts it to Perl's native shared aggregate behavior.

The recommended design is to share small coordination variables and keep bulk
mutable work child-local. See the
[dynamic map/reduce example](../../examples/threads/dynamic_map_reduce.pl).

## Lifecycle and Carrier Policy

Create/join/detach, identity and listing, creation context, errors, nested
threads, `CLONE`/`CLONE_SKIP`, child exit, stack options, and live attached
thread signals are supported. Completed or detached thread objects are not
signal targets.

Java 24 virtual threads are the default carrier. Select platform carriers for
the process with:

```bash
JPERL_THREAD_MODE=platform ./jperl program.pl
```

The equivalent JVM property is `-Djperl.thread.mode=platform`. A nonzero Perl
thread stack-size request automatically selects a platform carrier because a
virtual thread's stack is JVM-managed. Carrier selection does not change Perl
snapshot or shared-storage semantics.

## Resource Ownership

Native and Java resources do not use a generic shallow-copy rule. Every handle
type has an explicit thread inheritance policy:

- supported file, pipe, socket, process, scalar, layered, duplicate, borrowed,
  directory, native-descriptor, and standard handles use their classified
  clone/share/close behavior;
- callback registrations bind the runtime that registered them;
- DBI connections, statements, and result sets are runtime-owned and cannot be
  used after crossing a thread boundary. Open a connection inside each child
  and return ordinary Perl data through `join`;
- future native or I/O adapters must declare snapshot, aliasing, ownership, and
  last-owner close behavior before they may cross an ithread boundary.

The [parallel DBI example](../../examples/threads/dbi_parallel_queries.pl)
demonstrates the safe database pattern.

## Runtime Pools

PSGI runtime pooling is independent of ithreads and is bounded and opt-in.
`JPERL_RUNTIME_POOL_SIZE=N` prepares independent application snapshots; the
default `0` retains the single-runtime handler. A returned application runtime
is closed and replaced from the authoritative template rather than reused after
partial cleanup.

## Validation

The pull-request compatibility gate runs the complete four-module suite on both
backends with virtual carriers, plus focused lifecycle, signal, stack, wait,
timeout, and deadlock coverage on platform carriers:

```bash
git clone https://github.com/Perl/perl5.git perl5
git -C perl5 checkout de80c8ecd40c6d5b677847699e5482b44bc748c6
make test-threads
```

The clone is required only when an adjacent `perl5/` source tree is not already
present. CI performs a sparse checkout of the four distributions and their core
test harness at this exact compatibility-corpus commit.

The release-only regex anchors also require the imported `perl5_t/t/re` corpus;
run `perl dev/import-perl5/sync.pl` when that gitignored tree is absent.

Before a thread/runtime release, run the complete four-mode matrix:

```bash
make test-threads-release
```

Both targets use eight test jobs, a hard 300-second timeout per file, and write
JSON reports under `build/reports/threads/`. Timing-sensitive join coverage is
given an exclusive runner slot, and strict exit mode makes any non-passing file
fail the gate. The release target also runs the post-Joni regex-thread anchors
on both execution backends: five files and 48 assertions per backend, with a
600-second per-file bound.

Native callback and ORM compatibility form a separate slow ecosystem gate:

```bash
make test-threads-ecosystem
```

It runs the unchanged Net::SSLeay 61/62 thread suites and then executes
`timeout 3600 ./jcpan --jobs 8 -t DBIx::Class`.

The callout-enabled Joni matcher and executable regex callbacks are integrated.
Remaining direct regex-language compatibility is maintained in the separate
Phase 36 project. Threaded regex tests remain preservation checks against their
same-commit direct companions.

See also the [feature matrix](feature-matrix.md#concurrency-and-perl-threads),
[testing guide](testing.md), and the [thread examples](../../examples/threads/).
