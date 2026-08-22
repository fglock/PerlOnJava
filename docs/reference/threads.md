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
interpreter with Java virtual and platform carriers. This is the delivered
public-module milestone. The source-first core-wrapper gate is implemented: all
five non-regex Perl core thread files pass 849/849 on both backends and
carriers, and the twelve regex wrappers are compared with their same-commit
direct companions. The callout-enabled Joni engine is integrated; remaining
direct regex-language gaps are tracked independently and may not be hidden by
thread wrappers.

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
- Shared aggregate writes accept ordinary scalar values and references whose
  referents are already shared. Inserting a private reference throws `Invalid
  value for shared scalar` before that destination write occurs.
- `lock`, `cond_wait`, `cond_timedwait`, `cond_signal`, and `cond_broadcast`
  provide recursive lexical locking and condition coordination.
- Nested aggregate fetches return runtime-local proxy references over common
  storage. Local reblessing stays local until that view is stored back, and
  final `DESTROY` ownership remains global across views.
- Tied scalars retain runtime-local callback state. Sharing an already tied
  array or hash converts it to Perl's native shared aggregate behavior.

Loading `threads::shared` without first loading `threads` retains threaded
Perl's inactive behavior: `:shared` is accepted but does not publish storage.
This keeps modules such as `Thread::Queue` usable in their documented
single-threaded mode.

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
perl dev/import-perl5/update_perl5.pl
make test-threads
```

The helper clones an adjacent `perl5/` source tree when absent and
fast-forwards its default branch when present. CI performs a current sparse
checkout of the four distributions and their core test harness and records the
resolved commit as provenance. The gate also proves
that a timed-out test cannot leave a nested `fresh_perl` JVM running after its
parent exits.

The release-only regex anchors also require the imported `perl5_t/t/re` corpus;
run `perl dev/import-perl5/sync.pl` when that gitignored tree is absent.

Before a thread/runtime release, run the complete four-mode matrix:

```bash
make test-threads-release
```

The complete Perl core-wrapper matrix can also be run independently:

```bash
make test-threads-core
make test-threads-core-platform
```

Direct companions run before `_thr.t` wrappers on the same commit. The
non-regex thread files remain strict. For partial direct regex files, the parity
checker rejects lost TAP, added failures or incompleteness, timeouts, and
execution errors in the wrapper; an independently tracked direct Phase 36 gap
does not become a thread failure. Resource-sensitive regex families run
serially so their upstream temporary files cannot collide.

The core targets use four parallel jobs with a hard 600-second bound, and a
900-second serialized bound for resource-sensitive families. JSON reports are
written under `build/reports/threads/core/`. The public-module target uses eight
jobs and a 300-second bound. The release target also runs the post-Joni
regex-thread anchors on both execution backends: five files and 48 assertions
per backend, with a 600-second per-file bound.

Native callback and ORM compatibility form a separate slow ecosystem gate:

```bash
make test-threads-ecosystem
```

It runs pinned Storable, Test2, and Moose thread-bearing tests on both execution
backends, preserving Moose's upstream TODO, followed by the unchanged
Net::SSLeay 61/62 thread suites, and then executes
`timeout 3600 ./jcpan --jobs 8 -t DBIx::Class`. Before those slow suites it
compares the DBI thread-ownership contract with system Perl and runs it under
both PerlOnJava backends with virtual and platform carriers.

JDBC primitives are retained under private DBI entry points before the public
Perl methods are wrapped. This makes `prepare`, `execute`, `finish`, transaction,
fetch, and disconnect dispatch identical on the JVM and interpreter backends;
the wrappers do not depend on when a compiler materializes a replaced public
CODE glob.

The callout-enabled Joni matcher and executable regex callbacks are integrated.
Remaining direct regex-language compatibility is maintained in the separate
Phase 36 project. Threaded regex tests remain preservation checks against their
same-commit direct companions.

See also the [feature matrix](feature-matrix.md#concurrency-and-perl-threads),
[testing guide](testing.md), and the [thread examples](../../examples/threads/).
