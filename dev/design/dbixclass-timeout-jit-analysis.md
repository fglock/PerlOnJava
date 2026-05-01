# DBIx::Class t/96_is_deteministic_value.t — Timeout Root Cause Analysis

## Summary

`t/96_is_deteministic_value.t` completes in **~6.7 seconds** on a clean machine.
Under CPU pressure from competing JVM processes it takes **200+ seconds**, exceeding
the 300 s TAP::Parser kill timer.  
The root cause is a **C1 JIT "out of virtual registers" failure** that fires only when
the JVM is starved for CPU during Perl module loading.

---

## Reproduction Evidence

| Environment        | Real time | All 8 tests pass? |
|--------------------|-----------|-------------------|
| Clean (no orphans) | 6.7 s     | yes               |
| Under CPU load     | 200+ s    | yes (if not killed) |

---

## Root Cause Chain

```
Orphaned JVMs at 100% CPU
         │
         ▼
Target JVM starved → more time in interpreted / level-1 mode
         │
         ▼
SQL generation hot path (SQL::Abstract::_expand_expr) becomes hot
  → large generated Perl apply() methods submitted to C1
         │
         ▼
C1 Linear Scan fails: "out of virtual registers"
  compileId 4980  (C1 CompilerThread0, 14:12:54.784)
  compileId 6224  (C1 CompilerThread2, 14:12:56.406)
         │
         ▼
Hot path runs permanently in interpreter (level 0): ~50-100× slower
         │
         ▼
Test completes in ~200 s — exceeds 300 s TAP harness timeout → SIGKILL
```

---

## JFR Evidence

### Clean run (`/tmp/96_clean_jfr.jfr`, 7 s)

| Metric | Value |
|--------|-------|
| Execution samples | 312 |
| GC events | 32 |
| `RuntimeControlFlowList` alloc samples | 0 |
| C1/C2 failures | 0 |
| JIT compilations after t=0 | continuous through end |

### Hung run (`/tmp/96_det.jfr`, 200+ s)

| Metric | Value |
|--------|-------|
| Execution samples | 13,132 |
| GC events | 1,193 |
| `RuntimeControlFlowList` alloc samples | 20,766 |
| C1 failures ("out of virtual registers") | **2** (compileIds 4980, 6224) |
| Last JIT compilation timestamp | 14:12:59 |
| Time with no JIT activity after that | **189 seconds** |

The `RuntimeControlFlowList` samples are produced by `goto &sub` tail-call
trampolining in `RuntimeCode.apply()`.  In the clean run these never materialise
because the code finishes before the allocator fires; in the hung run the
trampoline runs for 189 extra seconds.

### Compilation failure details

```
jdk.CompilationFailure {
  startTime = 14:12:54.784
  failureMessage = "out of virtual registers in linear scan"
  compileId = 4980
  eventThread = "C1 CompilerThread0"
}

jdk.CompilationFailure {
  startTime = 14:12:56.406
  failureMessage = "out of virtual registers in linear scan"
  compileId = 6224
  eventThread = "C1 CompilerThread2"
}
```

Both failures happen during the Perl module loading phase, when generated
anonymous-class `apply()` methods (Perl subroutine bodies compiled by
`EmitterMethodCreator`) first become hot.

### Candidate methods

The largest C1-compiled method seen in any run is
`EmitterMethodCreator.getBytecodeInternal` (5,224 bytes bytecode → 159,840 bytes
native C1 code).  The generated Perl `apply()` methods can be equally large or
larger; for example the SQL::Abstract `_expand_expr` closure observed in the hung
run shows up at `anon11736.apply (eval 743):983` — indicating line 983 of a
single generated method.  Methods of that size stress C1's linear scan allocator
even on a healthy machine; under CPU pressure the probability of failure increases
because the register allocator's working set spills into slow paths.

---

## Hot Frame in Hung Run

From jstack thread dumps taken during the 200 s hang:

```
"main" prio=5 tid=1 nid=... runnable
  at org.perlonjava.runtime.runtimetypes.RuntimeCode.apply(RuntimeCode.java:3075)
  ... (tail-call trampoline loop)
  at anonXXXX.apply((eval 743):983)   ← generated SQL::Abstract closure
  at anonYYYY.apply((eval 743):...)
  at org.perlonjava.runtime.runtimetypes.RuntimeCode.callCachedInner(...)
```

The loop is **not** an infinite loop — it terminates after the full schema
population workload completes.  The apparent hang is purely the result of running
the same finite workload in interpreter mode instead of C2-compiled mode.

---

## Why Not an Application Bug

`t/96_is_deteministic_value.t` exercises DBIx::Class result-set determinism using
`SQL::Abstract::_expand_expr` to build complex SQL.  Every call is legitimate and
produces the correct result.  The test has passed on CI (where each run gets a
dedicated machine/container) without modification.

---

## Fix Options

### Immediate (already in AGENTS.md)

- Wrap every `jperl`/`jcpan` invocation with `timeout N` so orphaned JVMs
  are killed.
- After any test session, verify no orphans:
  ```bash
  ps aux | awk '$3 > 20 {print $2, $3, $11}'
  pkill -9 -f 'perlonjava-.*\.jar.*\.t\b'   # if any
  ```

### Short-term JVM tuning

Discourage C1 from attempting very large methods by capping its budget:

```bash
# In jperl or JPERL_OPTS:
-XX:C1MaxBlockSize=512          # skip C1 for large blocks → goes straight to C2
```

Or, skip the failing C1 tier entirely for the whole process:

```bash
-XX:+TieredCompilation -XX:CompileThreshold=1500   # fewer C1 attempts
```

These trades startup latency for fewer C1 failures under pressure; evaluation
required before shipping.

### Medium-term: reduce generated bytecode size

The generated Perl `apply()` methods grow without bound for complex closures.
Extracting common runtime sequences (argument marshaling, `goto &sub` dispatch,
exception wrapping) into static helper methods in `RuntimeCode` would shrink each
generated method, making C1 compilation more reliable:

- Refactor `EmitterMethodCreator` to emit calls to shared helpers rather than
  inlining those sequences directly.
- Target: keep generated `apply()` bytecode under ~2 KB per method.
- See also: `dev/architecture/large-code-refactoring.md`

### Long-term: CI process isolation

Run `jcpan -t DBIx::Class` and the full DBIx::Class test suite only in isolated
environments (containers, dedicated CI nodes) where no other JVM processes compete
for CPU.

---

## JFR Recordings for Further Study

| File | Size | Contents |
|------|------|----------|
| `/tmp/96_clean_jfr.jfr` | 1.9 MB | Clean 7 s run, profile settings |
| `/tmp/96_det.jfr` | 16.5 MB | Full hung run (200+ s), profile settings |
| `/tmp/96_det_dump.jfr` | 15.2 MB | Hung run, dump-on-exit |
| `/tmp/96_stress.jfr` | 2.1 MB | Stress-test comparison run |

Use `$JAVA_HOME/bin/jfr print --events jdk.CompilationFailure <file>` to inspect
failures, or open in JDK Mission Control for flame graphs.

---

## Related

- `AGENTS.md` — "ALWAYS WRAP `jperl`/`jcpan` IN `timeout`" section
- `dev/architecture/large-code-refactoring.md` — plan for shrinking generated code
- `jperl` lines 36–43 — existing note about `SoftMaxHeapSize` and the test
- commit `b64671407` — removed `SoftMaxHeapSize`, which was the previous fix for
  an unrelated GC-induced hang on the same test
