# Perl ithreads

These examples cover the two fundamental PerlOnJava ithread models:

- [`isolated_create_join.pl`](isolated_create_join.pl) creates an ithread from
  a runtime snapshot, returns a child-owned result through `join`, and proves
  that an ordinary captured scalar remains isolated in the parent.
- [`shared_lock_condition.pl`](shared_lock_condition.pl) marks a scalar with
  `:shared`, protects it with lexical `lock`, and coordinates parent and child
  with `cond_wait` and `cond_signal`. The condition is always checked in a loop
  so an early signal or spurious wakeup cannot violate the state transition.
- [`dynamic_map_reduce.pl`](dynamic_map_reduce.pl) uses a tiny shared work
  index to distribute documents, keeps each worker's word-count hash local,
  and merges ordinary result graphs after `join`. It demonstrates the
  recommended pattern: share coordination, not bulk mutable data.
- [`dbi_parallel_queries.pl`](dbi_parallel_queries.pl) creates a small SQLite
  database, lets three ithreads open and own independent DBI connections, and
  returns ordinary aggregate data through `join`. Native DBI handles are never
  inherited or returned across runtime boundaries.

Run either example from the repository root:

```bash
./jperl examples/threads/isolated_create_join.pl
./jperl examples/threads/shared_lock_condition.pl
./jperl examples/threads/dynamic_map_reduce.pl
./jperl examples/threads/dbi_parallel_queries.pl
```

The same source runs on standard threaded Perl:

```bash
perl examples/threads/isolated_create_join.pl
perl examples/threads/shared_lock_condition.pl
perl examples/threads/dynamic_map_reduce.pl
perl examples/threads/dbi_parallel_queries.pl
```

PerlOnJava uses Java 24 virtual threads by default. Platform threads remain a
process-wide compatibility mode; selecting them does not change Perl snapshot
or shared-storage semantics:

```bash
JPERL_THREAD_MODE=platform \
  ./jperl examples/threads/isolated_create_join.pl
```

Live attached children support targeted thread signals. A nonzero stack-size
request automatically selects a platform-backed child because virtual-thread
stacks are JVM-managed. Shared blessed aggregates use runtime-local proxy
views over common backing; the advanced edge cases and tie-order rules are
documented in the [Perl threads reference](../../docs/reference/threads.md).
A captured PSGI runtime is not made
concurrently callable merely by enabling ithreads; use the opt-in PSGI runtime
pool for concurrent request execution.
