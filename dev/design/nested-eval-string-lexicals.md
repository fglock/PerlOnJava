# Fix: nested `eval STRING` cannot see outer `my` lexicals (interpreter backend)

## Problem

In standard Perl, a string eval's compile-time lexical scope includes every
`my`/`our`/`state` visible at the call site — including variables declared
inside an enclosing `eval STRING`.

In PerlOnJava (default interpreter backend, i.e. not
`JPERL_EVAL_NO_INTERPRETER=1`), this works for most forms but fails for a
specific combination: **a named subroutine defined inside a nested `eval STRING`
that references a `my` declared in the outer `eval STRING`**.

Minimal reproducer (discovered while fixing `Geo::IP`):

```perl
eval q{
  use strict;
  my $y = 99;
  eval q{ sub bar { return $y } 1 };
  print $@;   # PerlOnJava: Global symbol "$y" requires explicit package name...
              # Real Perl:  (prints nothing)
};
```

Concrete impact: Geo::IP's pure-Perl path wraps v6 subs inside an inner
`eval <<'__IPV6__'` that references outer-eval `my @countries`, `@code3s`,
`@names`. All v6 methods silently fail to compile, causing `country_v6.t`
and half of `org.t` to die with "Can't locate object method …".

## Root cause (verified)

The bug is **interpreter-backend specific**. Setting
`JPERL_EVAL_NO_INTERPRETER=1` makes the reproducer work, because the JVM
backend (`backend/jvm/EmitEval.java` → `runtime/runtimetypes/RuntimeCode.java#evalStringHelper`)
correctly threads the outer eval's `capturedSymbolTable` through to the inner
eval's snapshot. When `handleMyOperator` sees `my $y` at the outer eval's
codegen, it calls `capturedSymbolTable.addVariable("$y", …)`. The inner eval's
`handleEvalOperator` then snapshots that table, so `$y` is present at the
inner parse.

The **interpreter backend** behaves differently:

1. At compile time, `backend/bytecode/CompileOperator.java:1097` captures the
   caller's lexical pad as a `Map<String,Integer>` via
   `bytecodeCompiler.symbolTable.getVisibleVariableRegistry()` and stores it in
   `BytecodeCompiler.evalSiteRegistries`. That registry is emitted as part of
   the `EVAL` opcode operand and delivered at runtime.

2. At runtime, `backend/bytecode/EvalStringHandler.java:120`
   (`evalStringList`) creates a **fresh, empty** `ScopedSymbolTable` and
   seeds it with only three entries:

   ```java
   symbolTable.enterScope();
   symbolTable.addVariable("this", "", null);
   symbolTable.addVariable("@_", "our", null);
   symbolTable.addVariable("wantarray", "", null);
   ```

3. The `siteRegistry` received from the caller (step 1) is used purely to
   **build runtime captured-value arrays** (lines 182–246) — the variable
   *names* never make it into `symbolTable`. The fresh symbol table is what
   the parser sees.

4. Consequence: `frontend/parser/Variable.java#checkStrictVarsAtParseTime`
   (line 285) — which only fires inside named sub bodies — does a
   `symbolTable.getSymbolEntry("$y")`, finds nothing, and raises
   "Global symbol requires explicit package name".

Verified via instrumentation (`DEBUG_STRICT=1`):

```
[EVAL-INT] tag=eval14 capturedVars=[this, @_, wantarray]
[STRICT] missing $countries in sub=main::foo visible=[this, @_, wantarray]
```

The direct-expression case (`eval q{ $y + 1 }` inside outer eval) *works*
only because it bypasses the strict-at-parse-time check (no named sub body)
and because `BytecodeCompiler` resolves references via the
`adjustedRegistry`, not `symbolTable`.

The anonymous-sub case (`eval q{ sub { $y } }`) works for the same reason
(`checkStrictVarsAtParseTime` is gated on named subs only).

## Goal

An inner `eval STRING` must see the caller's visible `my`/`our`/`state`
lexicals at parse time, for all parse paths (direct references, anonymous
subs, named subs, nested evals of any depth) — under both the interpreter and
JVM backends — while preserving existing closure/runtime-capture semantics.

## Proposed fix

Treat `siteRegistry` as authoritative lexical information: when
`EvalStringHandler` prepares the fresh `ScopedSymbolTable`, seed it with
placeholder `my`-declared entries for every name in the registry. These
entries only need to be *present* (so parse-time name lookups and
`checkStrictVarsAtParseTime` succeed); the actual storage location is
handled separately by `BytecodeCompiler` via `adjustedRegistry` (runtime
captured-var registers).

This mirrors what the JVM backend's `RuntimeCode.evalStringHelper` already
does implicitly (by reusing `capturedSymbolTable`).

### Phase 1 — fix the interpreter `EvalStringHandler`

In `backend/bytecode/EvalStringHandler.java`, both in `evalStringList`
(around line 126) and `evalString` (around line 340):

```java
symbolTable.enterScope();
symbolTable.addVariable("this", "", null);
symbolTable.addVariable("@_", "our", null);
symbolTable.addVariable("wantarray", "", null);

// NEW: seed the symbol table with outer lexicals so the parser can see
// them (e.g. for strict-vars checks inside named sub bodies). The
// runtime values for these variables are captured separately via
// adjustedRegistry; here we only need the names to be resolvable.
if (siteRegistry != null) {
    List<Map.Entry<String, Integer>> sorted =
        new ArrayList<>(siteRegistry.entrySet());
    sorted.sort(Map.Entry.comparingByValue());
    for (Map.Entry<String, Integer> e : sorted) {
        String name = e.getKey();
        // Skip reserved slots and names already added.
        if (e.getValue() < 3) continue;
        if (symbolTable.getSymbolEntry(name) != null) continue;
        // "my" is the right decl: these variables will not leak back
        // into the caller's scope (eval scope is discarded on return),
        // and "my" is what strict-vars looks for.
        symbolTable.addVariable(name, "my", null);
    }
}
```

Considerations:

- **Declaration kind**: use `"my"` for everything (not `"our"`) so the
  existing `checkStrictVarsAtParseTime` bypass at line 285 applies. If any
  of the names were originally `our`, that's fine — we lose the "our"
  distinction inside eval, but that only affects extremely edge-case
  diagnostics (e.g. re-declaration warnings) and can be refined later by
  carrying the decl kind alongside the index in the registry.
- **Index preservation**: don't carry over the registry's slot indices into
  `symbolTable`. `ScopedSymbolTable.addVariable` will pick fresh indices
  for the eval's own pad, and `BytecodeCompiler` already uses
  `adjustedRegistry` (independent of `symbolTable` indices) to map
  captured variables into runtime registers 3…N.
- **`@_` collision**: `@_` is pre-added at reserved slot 1 and will not be
  re-added (guarded by the `getSymbolEntry != null` check).
- **`strict`/`feature` flags**: already inherited at lines 139–148. No
  change needed.

### Phase 2 — instrument + prove via Perl-level tests

Add `src/test/resources/unit/eval_nested_lexicals.t` with one subtest per
failing shape:

1. `my $x` in outer eval, direct `$x` in inner eval. (already passes)
2. `my $x` in outer eval, anonymous sub in inner eval. (already passes)
3. `my $x` in outer eval, **named sub** in inner eval reading `$x`.
4. `my @arr` in outer eval, **named sub** in inner eval reading `$arr[0]`.
5. Three-deep nesting: `eval q{ my $a = …; eval q{ my $b = …; eval q{
   sub f { $a + $b } }; f() }; }`.
6. `our $x`, `state $x`, and `local $x` variants.
7. Write-through: inner eval assigns to outer `my` variable, outer checks
   the value.
8. Compile-time pragma propagation across eval boundary (warnings, strict,
   features).

Run under both backends:

```bash
./jperl src/test/resources/unit/eval_nested_lexicals.t
JPERL_EVAL_NO_INTERPRETER=1 ./jperl src/test/resources/unit/eval_nested_lexicals.t
./jperl --interpreter src/test/resources/unit/eval_nested_lexicals.t
```

### Phase 3 — Perl 5 core eval.t baseline

Run `perl dev/tools/perl_test_runner.pl perl5_t/t/op/eval.t` on master
and on the fix branch. Key subtests (per subagent report):

- Lines 105–132: "check navigation of multiple eval boundaries to find lexicals"
- Lines 121–132: "calls from within `eval''` should clone outer lexicals"
- Lines 254–312: explicit `eval q{ my $r; sub fred3 { ...inner eval '$yyy'... } }`
- Lines 186–189: "lexical search terminates correctly at subroutine boundary"

Target: at least all the above subtests passing. Avoid regressions
elsewhere.

### Phase 4 — real-world validation

1. **Geo::IP**: re-run `./jcpan -t Geo::IP`. Expected: all 8 test files
   pass, up from the current 6/8 (with the `fix/geo-ip-dynaloader-socket-v6`
   branch already merged).
2. **Full unit suite**: `make` must be clean.
3. **Bundled-modules suite**: `make test-bundled-modules` must not regress.
4. **Performance smoke test**: `./jperl -e 'for (1..1000) { eval q{my $x=1; eval q{my $y=2} } }'`
   — the fix adds N variable additions to the eval's fresh symbol table;
   for large outer pads this is O(N) per inner eval. Measure under
   ExifTool's startup to ensure no measurable regression.

### Phase 5 — JVM-backend audit

Although `RuntimeCode.evalStringHelper` already handles the inner-eval
case correctly, write an explicit regression test that runs under both
backends so we can't silently diverge in the future. In particular, the
`filteredSnapshot` logic in `frontend/parser/SubroutineParser.java`
(lines 1188–1254) that runs when a named sub inside an eval is being
compiled should be checked against the new test cases — if there's a
path that rebuilds the snapshot off a pre-mutated symbol table, it
might drop outer eval vars.

## Alternatives considered

- **Extend `siteRegistry` to carry decl kind ("my"/"our"/"state") and
  source `ScopedSymbolTable`**: more invasive, helps edge cases but not
  needed for the bug. Revisit if Phase 2 finds sub-suite failures that
  care about decl kind.
- **Reuse `RuntimeCode.evalStringHelper` logic inside
  `EvalStringHandler`**: biggest refactor, would unify both backends. Not
  advisable under schedule pressure; both paths have grown their own
  nuance (BEGIN-block aliasing, hint-hash restore, etc.) and need to
  converge via a shared helper later.
- **Disable interpreter backend**: regression in startup time; not
  acceptable.

## Risks / open questions

1. **Name collisions**: if two nested scopes of the caller declared vars
   with the same name, only the innermost is in `siteRegistry`. OK —
   standard Perl does the same.
2. **`@_` / reserved-slot interactions**: guarded by the
   `getSymbolEntry != null` check. Cross-check that the caller's registry
   cannot contain entries mapped to slots 0/1/2 that we'd miss.
3. **Named subs that *do* capture outer lexicals**: in real Perl these
   warn "Variable `$x` will not stay shared at …". We don't emit that
   warning today. Add to a follow-up ticket, not blocking.
4. **BEGIN blocks**: `RuntimeCode.evalStringHelper` has a complex
   PersistentVariable aliasing path for BEGIN. The interpreter path may
   need a parallel mechanism. Out-of-scope for this fix, but add to open
   questions.
5. **`eval STRING` inside BEGIN**: confirm `evalSiteRegistries` is
   correctly populated when the outer code runs at BEGIN time.

## Files likely to change

| File | Change |
|---|---|
| `src/main/java/org/perlonjava/backend/bytecode/EvalStringHandler.java` | Seed symbol table from `siteRegistry` (both overloads). |
| `src/test/resources/unit/eval_nested_lexicals.t` | New test file. |
| `dev/design/nested-eval-string-lexicals.md` | This document (progress tracking). |
| `AGENTS.md` or similar | Only if new debug env var or workflow is added. |

No changes planned to:
- `backend/jvm/EmitEval.java` (already correct)
- `runtime/runtimetypes/RuntimeCode.java` (JVM path)
- `frontend/parser/Variable.java` (the strict check is correct; we're
  fixing the symbol table it consults)
- `frontend/parser/SubroutineParser.java` (may need inspection, not
  change)

## Progress tracking

### Current status
Plan drafted; implementation not started.

### Completed
- [x] Reproduce the bug and isolate to interpreter backend (2026-04-20)
- [x] Instrument `EmitEval`, `RuntimeCode.evalStringHelper`,
      `EmitVariable.handleMyOperator`, and
      `Variable.checkStrictVarsAtParseTime`; confirm fresh symbol table
      in `EvalStringHandler` is the root cause (2026-04-20)
- [x] Confirm JVM backend (`JPERL_EVAL_NO_INTERPRETER=1`) works correctly
      on the reproducer (2026-04-20)

### Next steps
1. Phase 1: implement the symbol-table seeding in `EvalStringHandler` (both
   overloads).
2. Phase 2: write and run `eval_nested_lexicals.t`.
3. Phase 3: baseline + compare `perl5_t/t/op/eval.t`.
4. Phase 4: re-run `./jcpan -t Geo::IP`; `make`; `make test-bundled-modules`.
5. Phase 5: add cross-backend parity tests.

### Open questions
- Should named subs inside eval warn "Variable will not stay shared"?
  (separate ticket)
- Do BEGIN blocks in the interpreter path need a parallel to the JVM's
  `PersistentVariable` aliasing? (file a follow-up after Phase 4 tests.)

## References

- Subagent investigation transcript:
  `/var/folders/r9/9y2qm0t12bxc10jbthrttn8h0000gn/T/devin-overflows-501/f1a337c9/content.txt`
  (full technical walkthrough of both backends, ~200 lines).
- Related doc: `dev/custom_bytecode/EVAL_STRING_SPEC.md`.
- Related doc: `dev/custom_bytecode/CLOSURE_IMPLEMENTATION_STATUS.md`.
- PR that surfaced the bug:
  https://github.com/fglock/PerlOnJava/pull/511
  (Geo::IP fix — 2 of the 8 test files still fail due to this issue.)
