# Performance over Perl handoff

## Objective and proof

The objective is the [main performance contract](performance-over-perl.md):
the default JVM backend must reach a portfolio geometric mean of at least
1.05x standard Perl, with a 95% confidence interval wholly above 1.00x; both
the closure and Life anchors must also reach 1.05x; every scored workload must
be at least 0.90x; and semantics must remain correct on both backends.

Do not treat a short benchmark, a JFR capture, an allocation reduction, or an
analyzer success alone as proof. The final report must contain all seven
workloads, seven alternating fresh-process pairs per workload, stable warmup,
the paired confidence intervals, source/JAR hashes, and the pinned Perl/JDK
and host identity. The analyzer currently does not enforce the complete
contract: extend it before relying on a passing result so that it rejects a
missing workload and checks the portfolio and both anchors' lower confidence
bounds above 1.00x.

## Current evidence and budget

The authoritative baseline is decisively below target. Its JSON ratio was
0.0102x, which needs an 88.2x speedup merely to reach the 0.90x floor. The
other recorded gaps remain material: closure needs 6.59x to its 1.05x anchor,
Life 2.75x, method 5.41x, regex 4.81x, string 3.09x, and numeric 2.69x to
their stated thresholds. No individual reduction should be described as
progress toward acceptance unless its non-overlapping affected fraction and
measured speedup can materially move one of those budgets.

The recent one-pair JSON diagnostic is useful only for attribution. Its
shared-argument instance category took about 30.98 microseconds and 28,977
bytes per call inclusive. The reported 3.74 microseconds / 3,384 bytes
"exclusive" value is **not** generic call-frame cost: it includes all body
work except nested instrumented calls. It cannot justify deprioritizing call
boundary work without a direct setup/dispatch/return measurement.

## What the opcode capture says

`BytecodeOpcodeDiagnostics` is an opt-in counter. A bounded JSON run recorded
high counts for branches, byte-string loads, mortal flushes, list creation,
call-site hint/warning setup, aliases, regex matching and state snapshots,
lexical cleanup, hash/array access, and direct calls. These counts cover
startup, warmup, measurement windows, and every interpreter CV in the process.
They establish that interpreter work is substantial, but not which operation
owns elapsed time or allocation. Never optimize by count alone.

Use it with:

```text
-Dperlonjava.bytecodeOpcodeDiagnostics=true
-Dperlonjava.bytecodeOpcodeDiagnosticsOutput=/tmp/json-opcodes.json
```

The implementation is disabled in ordinary runs. It passed the full `make`
gate in 5m27s, and its instrumentation cost makes it unsuitable for timing.

## Required next sequence

1. **Repair the acceptance reporter.** Require exactly the scored seven
   workloads and verify portfolio, closure, and Life lower confidence bounds
   above 1.00x in addition to the median thresholds. Add tests that a missing
   workload and a failing anchor interval cannot pass.
2. **Measure the hot code during measurement windows.** Extend attribution to
   identify executed interpreter CVs and isolate warmup from measured windows.
   Combine per-CV opcode counts with JFR CPU/allocation stacks. Record direct
   setup, dispatch, body, and return costs so body time is not conflated with
   uninstrumented boundary work.
3. **Test the hot-eval hypothesis before promoting it.** `JPERL_EVAL_NO_INTERPRETER=1`
   previously moved the JSON diagnostic by only about 5%. Verify which hot CVs
   changed backend and whether they account for the remaining time. Do not
   build a promotion mechanism until this activation evidence supports it.
4. **Screen each structural candidate with an Amdahl budget.** Record the
   non-overlapping fraction it affects, its guard hit rate, fallback cost,
   expected residual cost, allocations, and required speedup. Reject a change
   that cannot close a meaningful portion of a scored workload's budget even
   if it reduces a frequent opcode.
5. **Implement only measured hot paths.** Candidate classes include repeated
   interpreter call sequences, dynamic regex scope setup, lexical cleanup, and
   JSON::PP-specific executed patterns. Preserve the generic slow path and add
   standard-Perl regression coverage before backend and full-suite validation.
6. **Measure parent and candidate from the same controlled source state.**
   Start with a paired diagnostic only to answer the candidate's cost question.
   Run the complete seven-pair portfolio only after it demonstrates a material
   reduction. Retain compact evidence in the main design and update this
   handoff with exact commit hashes and remaining budgets.

## References

- [Main performance design](performance-over-perl.md)
- [Bytecode interpreter architecture](interpreter.md)
- [Profiling skill](../../.agents/skills/profile-perlonjava/SKILL.md)
