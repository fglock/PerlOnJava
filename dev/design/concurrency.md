# Perl Threads Completion Plan

**Status:** Complete
**Version:** 4.0
**Date:** 2026-08-17

## Goal

Deliver Perl 5 interpreter threads (ithreads) on the JVM with the same
clone-versus-share model as threaded Perl:

- every child owns an isolated snapshot of its parent's `PerlRuntime`;
- ordinary values, closures, globals, and interpreter state are cloned;
- storage explicitly marked through `threads::shared` remains common;
- join results and errors cross back through the graph-cloning boundary;
- JVM and interpreter backends behave identically on virtual and platform Java
  carriers.

The callout-enabled Joni engine is integrated. Remaining failures shared by a
direct regex test and its thread wrapper belong to the independent direct-regex
compatibility project. A thread wrapper may never lose TAP, add failures, time
out, or become incomplete relative to its same-commit direct companion.

Implementation history and superseded phase notes are intentionally omitted.
They are recoverable from commit messages and merged pull requests.

## Non-Negotiable Runtime Contracts

### Runtime ownership

`PerlRuntime` is an interpreter instance. Perl execution binds it explicitly
and exclusively for the duration of the call. Background workers and native
callbacks must capture and bind their owning runtime; runtime state is never
inherited implicitly through `InheritableThreadLocal`.

### Compilation

Parsing and code generation remain protected by the global reentrant compile
lock. Generated code executes outside that lock. The lock may be narrowed only
after a separate audit proves the affected compiler state immutable or
runtime-local.

### Graph cloning

`RuntimeGraphCloner` preserves aliases and cycles within one snapshot. Ordinary
values become child-owned copies. Every Java/native resource has an explicit
inheritance adapter; there is no generic shallow-resource fallback.

### Shared storage

Shared scalars, arrays, and hashes expose common storage through runtime-local
views. A reference may be stored in shared storage only when its resolved
referent is already shared. Invalid assignments must throw `Invalid value for
shared scalar` before changing the destination. Blessing publication occurs
only after validation succeeds.

Bulk aggregate operations validate each destination write before that write.
Like threaded Perl, values preceding an invalid value may already have been
stored, but the invalid reference itself and all later values are not stored.
This applies to element assignment, proxies, push/unshift/splice, list/hash
assignment, slices, refaliasing, localization restore, and collection-backed
internal writes.

## Completion Phases

### Phase 1 — Refresh the plan and baseline

1. Start from current merged master and retain its test reports.
2. Keep the existing hard timeout and cleanup policies; do not explain semantic
   failures by increasing timeouts.
3. Record the exact direct/thread results that the completion fixes must
   preserve or improve.

**Exit criteria:** `make` is green and every completion blocker has a minimal
standard-Perl oracle and a same-commit PerlOnJava baseline.

### Phase 2 — Reject private references in shared aggregates

1. Centralize the shared-value validator used by scalar, array, and hash
   storage.
2. Allow undef, ordinary scalar values, dual/UTF-8 values, and references whose
   resolved referents are already shared.
3. Reject private scalar, array, hash, blessed, nested, and cyclic references
   before mutation.
4. Preserve threaded Perl's ordered bulk behavior: earlier valid writes remain,
   while the invalid write and later writes do not occur.
5. Preserve shared blessing publication and lifecycle ownership for accepted
   references.

**Exit criteria:** system Perl, JVM, and interpreter agree on the error,
atomicity, allowed values, and parent/child isolation. Existing shared-storage,
locking, condition, weak-reference, blessing/tie, queue, and destruction tests
remain green.

### Phase 3 — Restore trusted regex-value provenance

1. Treat `firstClassRegexScalar` as trusted compiled-regex provenance when a
   scalar created by `${qr//}` contains executable groups.
2. Admit that trusted source internally without enabling lexical `re 'eval'`
   for ordinary strings.
3. Preserve provenance through constant storage and thread cloning, and clear
   it on ordinary scalar mutation.

**Exit criteria:** `op/index.t` and `op/index_thr.t` are 415/415 on both
backends; an untrusted string containing `(?{})` remains rejected.

### Phase 4 — Preserve nested lexical cells in child runtime regexes

1. Make the currently executing active lexical frame authoritative when a
   lazily cloned or adopted CV registers cells.
2. Build the runtime-regex variable registry and captured-value array from one
   filtered ordered binding list so reserved/skipped names cannot shift values.
3. Preserve lexical shadowing, PadWalker, and Devel::LexAlias semantics.
4. Fix the shared runtime path; do not add wrapper-specific or Joni-specific
   behavior.

**Exit criteria:** direct `pat_re_eval.t` remains 420/555 and
`pat_re_eval_thr.t` recovers from 384/555 to 420/555 with no added failures on
both backends and both carrier policies.

### Phase 5 — Activate the release contract

1. Run the focused standard-Perl, JVM, interpreter, virtual, and platform
   matrices for every new test.
2. Run the complete public thread distributions and source-first Perl-core
   matrix.
3. Run the ecosystem gate, including Test2, Storable, Moose, Net::SSLeay, DBI
   ownership, and `timeout 3600 ./jcpan --jobs 8 -t DBIx::Class`.
4. Run a full imported-core differential against merged master and reject every
   per-file pass-count regression.
5. Update the threads reference and feature matrix only with measured results.
6. Open one pull request and require green Ubuntu and Windows CI.

**Exit criteria:** all commands below pass, the PR is open with captured
evidence, and GitHub Actions is green on both operating systems.

## Release Gates

### Current release evidence

- `make` passes after the five implementation phases.
- `make test-threads-release` passes all four backend/carrier configurations: 64 distribution files and 1,891 assertions per mode, the four-mode direct/wrapper core parity matrix, and 48 strict regex anchors per backend.
- `make test-threads-ecosystem` passes pinned Test2, Storable, Moose, DBI, and Net::SSLeay coverage plus the DBIx::Class corpus (325 files and 43,017 assertions).
- The complete 622-file imported-core differential against the exact base commit reports zero regressions and 38 additional passing assertions across three files. GitHub Actions passes on Ubuntu and Windows.

All output is captured. Every `jperl`, `jcpan`, and `prove` command is wrapped
in `timeout`.

```bash
make
make check-links
make test-threads-core
make test-threads-core-platform
make test-threads-release
make test-threads-ecosystem
```

New Perl tests run under system Perl first. Semantic tests then run on the JVM
and interpreter backends. The release matrix covers both virtual and platform
carriers. Resource-sensitive core fixtures stay in the runner's exclusive
lane.

The permanent pull-request gate remains `make test-threads`; the full release
gate is mandatory for thread/runtime releases. Windows additionally runs the
shell-independent `make test-threads-windows` JUnit matrix.

## Delivered Public Surface

- `threads` 2.43 lifecycle, identity, creation context, stack policy,
  join/detach, errors, exit, signals, and terminal aliases;
- `threads::shared` scalar/array/hash storage, recursive `shared_clone`, locks,
  condition variables, blessings, ties, weak views, aliases, cycles, and
  deterministic destruction;
- `Thread::Queue` and `Thread::Semaphore` unchanged upstream distributions;
- explicit file, pipe, socket, process, scalar, layered, duplicate, borrowed,
  directory, native-descriptor, standard-handle, callback, and DBI ownership
  policies;
- Java 24 virtual carriers by default and platform carriers explicitly or for
  nonzero stack-size requests.

Runtime pooling is independent of ithreads. It remains bounded and opt-in; a
returned application runtime is replaced according to the reset contract, not
partially reused.

## Maintenance Rules

1. New runtime state is classified as immutable process data, synchronized
   service state, per-runtime state, or per-execution state in the same change
   that introduces it.
2. A state migration and every worker touching that state land atomically.
3. Every new native resource, I/O handle, callback, and DBI adapter declares
   snapshot, aliasing, ownership, and close policy.
4. Direct regex compatibility is fixed in the direct implementation first;
   unchanged thread wrappers remain preservation tests.
5. Do not weaken upstream tests, parity thresholds, timeouts, or process
   cleanup to make a release gate pass.

## Related Documents

- `dev/design/attributes.md` — shared attributes
- `dev/design/runtime-pooling-reset-contract.md` — bounded runtime pooling
- `dev/design/phase36-regex-parity.md` — remaining direct regex compatibility
- `docs/reference/threads.md` — user-facing thread behavior and commands
- `docs/reference/feature-matrix.md` — public compatibility matrix
- `.agents/skills/debug-perlonjava/SKILL.md` — differential debugging workflow
