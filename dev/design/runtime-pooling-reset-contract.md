# Runtime Pooling Reset Contract

## Status

Fresh-runtime reset was implemented on 2026-08-14. Runtime pooling remains
deliberately disabled until Phase 42's checkout stress, retention measurements,
and performance gate pass. `PerlRuntime.close()` remains a terminal
resource-release operation; reusable runtimes use the separate exclusive
`reset()` transition.

This document defines the proof required before a pool may be implemented. It
does not authorize clearing state opportunistically or enabling pooling behind
an experimental flag.

This is the Phase 34 outcome, not an untracked implementation shortcut. Runtime
pooling is optional and is not required for Perl ithread correctness. The
negative automated contract below passes, while every positive equivalence
item remains a prerequisite for any future pooling PR.

## Fresh-runtime equivalence

A reusable runtime must expose the same observable state as a newly constructed
`PerlRuntime` initialized under the same process configuration. Reset is not
equivalent merely because no task is running and open handles were closed.

For workloads `A` and `B`, the required differential is:

```text
run A; reset; run B  ==  new runtime; run B
```

The equality applies to Perl results, diagnostics, exceptions, package and CODE
visibility, file descriptors, callbacks, cache behavior, destruction timing,
thread registries, and Java-side runtime inspection. It must hold after normal
return, `die`, timeout, child-thread creation, detached child completion, and
partial initialization failure.

## State inventory

The runtime owns all of the following reset domains. Every domain needs either
an explicit reset implementation and differential test or a written proof that
the state is immutable process configuration.

| Domain | Representative state | Current `close()` coverage |
|---|---|---|
| Package graph | globals, CODE refs, stashes, classes, aliases, `%INC`, generated classloader | retained |
| Execution | caller/dynamic stacks, special variables, END/INIT/CHECK blocks, eval frames, taint, async queues | retained |
| Regex | matches/captures, `pos`, `/o`, match-once, compiled and Unicode-property caches | retained |
| Dispatch/compilation | eval classes, method handles, inline caches, pad constants, compiler/source maps | retained |
| MRO and module services | method resolution, filters, module guards, shim-loading markers | retained |
| Lifecycle | weak references, mortal queues, tracked owners, destruction roots | partially released |
| Threads | registry membership, detached workers, shared storage/lock ownership, waiters | not resettable while live |
| Process services | signals, alarms, random state, pending fork-open, native/module registries | partially released |
| I/O | standard-handle replacement/visibility, selected/last handles, open-handle registry, diamond state, file-test cache | open resources released; topology retained |
| Data/debug/native | DATA sections, debugger state, pointer packing, Net::SSLeay queues/state | partially released |
| Identity/counters | bless and compiled-code identities, eval filenames, callsite IDs, name-normalizer state | retained |

`close()` currently cancels the active alarm, clears queued signals, closes
registered handles, resets Net::SSLeay and mortal state, and clears selected
native/I/O and operator registries before marking the runtime closed. That is a
correct terminal lifecycle, but it is intentionally insufficient for pooling.

## Acceptance checklist

Pooling remains disabled until all items below are complete:

- [x] Introduce one exclusive lifecycle transition that prevents reset while
  execution, compilation, callbacks, ithreads, detached children, shared-lock
  ownership, or condition waiters remain active.
- [x] Define whether core bootstrap state is reconstructed or restored from an
  immutable template; user package/CODE/class state and `%INC` must never leak.
- [x] Recreate standard I/O wrappers and glob topology without closing borrowed
  JVM streams, and restore selected/last-handle and visibility defaults.
- [x] Drain END/destruction work according to normal Perl semantics before
  clearing lifecycle roots; prove weak references and rescued objects do not
  cross tenants.
- [x] Clear every state domain in the inventory, including counters and caches,
  without retaining generated classes or prior workload object graphs.
- [x] Restore process-derived defaults (`cwd`, environment view, random policy,
  warning/feature defaults) according to an explicitly documented checkout
  contract.
- [x] Reject or quarantine a runtime after reset failure; a partially reset
  runtime must never return to the pool.
- [x] Prove `A; reset; B == fresh; B` on both compiler backends across globals,
  closures, eval/require, regex, warnings/hints, MRO, I/O, lifecycle, signals,
  native modules, DATA, debugger state, and exceptions.
- [ ] Add concurrency/stress coverage for checkout ownership, cancellation,
  detached-thread completion, repeated churn, and shared-storage lifetime.
- [ ] Add retention measurements showing that workload classloaders, package
  graphs, handles, and shared-lock entries become collectible after reset.
- [ ] Benchmark against fresh construction and snapshot cloning. Enable pooling
  only for a measured benefit large enough to justify the new lifecycle risk.

## Current automated guard

`PerlRuntimePoolingResetContractTest` preserves the negative `close()` contract.
`PerlRuntimeResetTest` proves the positive transition: representative package,
CODE, `%INC`, regex, execution, thread-option, and standard-I/O state matches a
fresh runtime on both backends; reset rejects bindings, child threads, and
shared locks; pending END work drains; failed reset poisons the runtime; and the
same Java runtime identity executes again after successful reset.

## Related documents

- `dev/design/concurrency.md` — multiplicity, ithreads, and virtual-thread policy
- `docs/reference/architecture.md` — public runtime ownership overview
- `docs/reference/memory-management.md` — lifecycle and weak-reference behavior
