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

Run either example from the repository root:

```bash
./jperl examples/threads/isolated_create_join.pl
./jperl examples/threads/shared_lock_condition.pl
./jperl examples/threads/dynamic_map_reduce.pl
```

The same source runs on standard threaded Perl:

```bash
perl examples/threads/isolated_create_join.pl
perl examples/threads/shared_lock_condition.pl
perl examples/threads/dynamic_map_reduce.pl
```

PerlOnJava supports platform threads by default. Virtual threads are an
experimental process-wide execution mode; selecting them does not change Perl
snapshot or shared-storage semantics:

```bash
JPERL_OPTS=-Djperl.thread.mode=virtual \
  ./jperl examples/threads/isolated_create_join.pl
```

Live attached children support targeted thread signals. Platform-backed
ithreads accept an effective Java stack-size request; virtual mode rejects a
nonzero request because virtual-thread stacks are JVM-managed. Sharing tied or
blessed values remains outside the supported tranche. A captured PSGI runtime
is also not made concurrently callable merely by enabling ithreads.
