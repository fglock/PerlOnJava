# Architecture options for Perl regex in PerlOnJava

## 1) Hybrid delegator (quickest path)

* **Idea:** Compile the pattern into a sequence of “atoms.” Route the Java-compatible subset to `java.util.regex`, and handle Perl-only constructs in your own engine.
* **How:** Build a tiny parser that segments the pattern around features Java lacks: `(?{...})`, `(??{...})`, `(?|…)` (branch reset), `\K`, `\G`, subroutine calls `(?&name)`, recursion `(?R)`, cut verbs `(*THEN|*SKIP|*PRUNE|*COMMIT)`, embedded code/modifiers, and capture semantics that differ.
* **Backtracking control:** Your wrapper owns the global backtracking stack. Delegated Java chunks run as “primitive predicates” that either succeed (and push a single backtrack point) or fail atomically. For cut/atomic groups, you discard backtrack frames before invoking the delegate.
* **Pros:** Fast to ship; reuses a tuned engine for large chunks of work.
* **Cons:** Edge-case fidelity is tricky (leftmost-longest vs leftmost-first nuances, capture numbering across branch-reset, interactions with `\G`, `pos()`, and `s///` flags).

## 2) Full Perl regex VM with JIT (most faithful, best long-term)

* **Idea:** Treat each regex as bytecode you compile (with ASM) into a specialized matcher class that runs on top of a small interpreter/VM. Think: a backtracking VM with explicit frames, plus deopt to an interpreter for rare ops.
* **Core instructions:** `CHAR`, `CLASS`, `ANCHOR`, `SAVE(n)`, `GROUP_ENTER/EXIT`, `CALL_SUBRULE`, `RECURSE`, `ASSERT_{POS,NEG}`, `ATOMIC_ENTER/EXIT`, `CUT_{PRUNE,THEN,COMMIT,SKIP}`, `SET_POS`, `RESET_CAPS`, `YIELD_CODE` (for `(?{ })`), `EVAL_PATTERN` (for `(??{ })`), `BRANCH_RESET_ENTER/EXIT`.
* **Backtracking model:** An explicit stack with frames recording: input idx, capture state snapshot (or journal of diffs), verb targets, and engine PC. Atomic groups elide frame pushes; cut verbs pop to the right watermark.
* **Embedded code & side effects:** Use PerlOnJava’s existing save-stack and scope machinery. Every side effect during match (variable writes, `local`, `tie` magic, pos changes) must be **journaling** so you can roll it back on backtrack. Re-execution on retry falls out naturally.
* **Unicode/char semantics:** Inline fast-path ASCII ops; deopt to helper calls for general category properties and grapheme boundaries. (You can keep ICU-style helpers behind an interface if you want to swap implementations later.)
* **Pros:** Exact Perl semantics, predictable performance characteristics, clean hooks for `(??{ })`, `qr//`, `s///e`, verb semantics, named/subrule recursion, etc.
* **Cons:** More work up front; you own all perf.

# Integration points with PerlOnJava internals

* **`qr//` and pattern cache:** Compile once into a matcher class; cache by `(pattern, flags, locale)`. Honor `/o` and embedded modifiers. Expose deopt guards for locale/utf8 mode.
* **`pos()` / `\G` / `/g` / `/c`:** Store per-SV `pos` in the scalar’s magic; the engine queries/updates it. `/c` prevents `pos` reset on failure; `\G` anchors to it.
* **Captures & numbering:** Maintain Perl’s numbering and named capture tables, including **branch-reset `(?|…)`** rules. This is where the hybrid approach usually stumbles—your VM owns it cleanly.
* **Subrule calls & recursion:** Implement `(?&name)` / `(?R)` as true calls that push a frame with their own capture scope, respecting leftmost-first.
* **Cut verbs:** Map `(*COMMIT)` to “drop all frames in this alternation,” `(*PRUNE)` to “drop just-created frames,” `(*THEN)` to “fail this branch, try next,” `(*SKIP:name?)` to “pop to a named cut or last savepoint.” These are first-class VM ops.
* **`(?{ code })` and `(??{ expr })`:**

  * Execute on **enter** with transactional side-effects.
  * Roll back on backtrack via your journal.
  * For `(??{ expr })`, compile the produced pattern on the fly and tail-call into it; cache if it’s stable under `/o`.
* **Substitution pipeline:** For `s///` variants (`/e`, `/r`, `/g`, `/c`, `/p`), drive the VM for find-phase; feed captures into replacement compiler (which can also be JITed if it includes `\U...\E` and friends or `e` code).
* **Locales, `/a` `/d` `/u` `/l`:** Thread flags through your char-class ops and anchors. Guard the JIT with the active mode and deopt if it changes.

# Performance strategy (keeps it fast)

* **Hot-path JIT:** Emit straight-line checks for literal runs, small classes, anchors, and word boundaries; fall back to helper calls for complex classes. Inline captures as simple array writes.
* **Frame elision:** Avoid pushing frames when the next op cannot fail (e.g., after `^` at start, fixed-width literals).
* **Memoization:** Optional: add a (pos, pc) → fail memo to short-circuit catastrophic cases (kept small to avoid memory blow-ups).
* **String access:** Work over UTF-16 with indexed access and a “cursor” abstraction; provide fast ASCII branch and slow path for surrogate pairs only when needed.
* **Deopt hooks:** If `(??{ })` or complicated verbs appear, deopt to the interpreter version of your VM for just that region.

# Security & isolation

* **`(?{ })` / `/e`:** Run with the same safety model you apply to `eval`: lexically scoped pads, `Safe`-like compartments if enabled, and hard time/memory guards. The journaling rollback ensures determinism under backtracking.
* **Denial-of-service:** Offer a global step counter and optional timeouts per match; expose knobs to cap backtrack frames and memo table sizes.

# Recommendation

* **Start hybrid** to get immediate wins and production exposure (especially for users not depending on `(??{ })`/verbs).
* **Converge to full VM JIT** for exact semantics and predictable behavior. You already generate bytecode with ASM and have Perl’s save-stack/ops—this plays to your strengths.

# Minimal milestone plan

1. Parser + IR for full Perl regex features → 2) Interpreter VM with full backtracking + side-effect journaling → 3) Bytecode JIT for hot ops (literals, classes, anchors, groups) → 4) Subrule recursion & branch-reset → 5) Verbs and atomic groups → 6) `(??{ })` + cached dynamic patterns → 7) s/// integration and `/g` semantics → 8) Perf passes (frame elision, memo, guards).


