# Sub::HandlesVia Support for PerlOnJava

## Overview

[Sub::HandlesVia](https://metacpan.org/pod/Sub::HandlesVia) generates delegation methods (“handles”) for Moo/Moose/Object::Pad/toolkit classes. Runtime work uses **generated Perl** compiled via **`Eval::TypeTiny::eval_closure`**; build-time codegen uses **Mite** (`*.mite.pm`) and **`Sub::HandlesVia::CodeGenerator`**.

PerlOnJava must treat **SvUTF8 / BYTE_STRING parity** consistently in string concatenation *and* return **Perl-correct lists** from core helpers such as **`UNIVERSAL::can`**, otherwise Mite constructors and delegated subs break in non-obvious ways.

---

## Completed (upstream-style fixes landed in core)

These changes address blockers traced while running `./jcpan -t Sub::HandlesVia`:

| Area | Problem | Fix |
|------|---------|-----|
| **`UNIVERSAL::can`** | Missing methods returned an **empty** `RuntimeList`, which behaves like Perl’s **empty list** inside hash literals. That **consumes** the next `=>` pairing and corrupted Mite **`__META__`** (`HAS_BUILDARGS` falsely truthy → bogus **`BUILDARGS`** branch). Pure singleton-`undef` on **all** failure paths confused **scalar-context** compiler probes (`VERSION`/`import`/attributes) that discriminate with **`size() == 1`**. | Failure paths route through **`Universal.canNotFound(ctx)`**: **LIST** ⇒ one `undef` element; **scalar/void/lvalue** ⇒ empty list (still **`scalar()` → undef**). `Universal.java`. |
| **String concat SvUTF8** *(deferred)* | A typed-concat experiment caused **`perl5_t`** regressions (`op/sub.t`, `porting/filenames.t`, `re/pat_advanced.t`); it was **reverted** from the PR trajectory serving Sub::HandlesVia. Redo against smaller, **`perl5_t`-backed** steps ([`dev/design/string_encoding_context_plan.md`](../design/string_encoding_context_plan.md)). |

Design cross-links:

- [`dev/design/utf8_flag_parity.md`](../design/utf8_flag_parity.md) — §2b (`can`).
- [`dev/design/string_encoding_context_plan.md`](../design/string_encoding_context_plan.md) — investigation note (2026-05-15).

---

## Current Status (manual smoke)

After the **`can`** fix:

- **`Sub::HandlesVia::CodeGenerator->__META__`** has four keys; **`HAS_BUILDARGS`** exists with **`undef`** (Perl-correct falsy gate).
- **`t/02moo.t`** progresses further but **still fails** when **`Eval::TypeTiny::eval_closure`** compiles generated source (`Unrecognized character \x{c2}` with a `#line` pointing at **`Eval/TypeTiny.pm`** — the synthesized filename/line prefix, not the host file’s UTF-8 problem).

Automated `./jcpan -t Sub::HandlesVia` was previously **timed out at 600s** in CI-style runs; rerun with **`timeout 3600`** after core fixes stabilize.

---

## Next Steps (prioritized)

### 1. [P0] Fix UTF-8 / lead-byte breakage in delegated eval (`\x{c2}`)

**Symptom:**

```text
Failed to compile source because: Unrecognized character \x{c2}; at .../Eval/TypeTiny.pm line 8 ...
  at .../Sub/HandlesVia/CodeGenerator.pm line 345 (Eval::TypeTiny::eval_closure)
```

**Goals:**

1. Capture the **exact `%ec_args`** string passed into **`eval_closure`** for a failing case (minimal Moo delegation in `t/02moo.t`), e.g. temporary logging in **`CodeGenerator.pm`** (`generate_coderef_for_handler`) guarded by **`$ENV{SUB_HANDLESVIA_DEBUG_EC}`**.
2. Binary-diff that string (`unpack "H*", $src`) vs system Perl — locate the first stray **`0xc2`** (UTF-8 lead byte) treated as Latin-1.
3. Classify origin:
   - **Runtime string typing** remaining in codegen (`"."`/`join`/quoting/formatters elsewhere), or
   - **PerlOnJava lexer/compiler** rejecting valid UTF-8 in **`eval`** strings (narrow vs wide rules), or
   - **Copy from file** paths reading `.pm` with wrong Perl layer assumptions.
4. Fix at the appropriate layer (prefer **prevent** mis-typing; **`RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted`** is only a fallback per design notes).

**Success:** `timeout 900 ./jperl .../blib/lib .../Sub-HandlesVia-*/t/02moo.t` completes with TAP **ok**.

### 2. [P1] Full CPAN harness run

```bash
timeout 3600 ./jcpan -t Sub::HandlesVia > /tmp/jcpan_Sub_HandlesVia.txt 2>&1
```

Catalog skips (optional deps **MooX::TypeTiny**, **Mouse**, etc.) vs real failures.

### 3. [P2] Concat / SvUTF8 parity redo (staging)

Retry [`dev/design/string_encoding_context_plan.md`](../design/string_encoding_context_plan.md) **Phase 2** (`StringOperators.stringConcat*`) **only after** guarding with:

```bash
cd perl5_t/t
timeout 300 ../../jperl op/sub.t
timeout 180 ../../jperl porting/filenames.t
timeout 600 ../../jperl re/pat_advanced.t   # noisy; grep ^not ok
```

Establish **baseline counts** vs **`origin/master`** on the **same harness** (`perl_test_runner.pl` shards if that is CI). A naive `RuntimeScalar(text, BYTE_STRING)` swap for the ISO-8859-1 `byte[]` path surfaced **opaque** regressions in **regex/porting/stack** slices — redo incrementally under its own tiny PR once bisected.

### 4. [P3] Regression tests in-repo (coordination needed)

PerlOnJava policy: **never delete or weaken existing tests**; adding **new** unit tests requires maintainer alignment. Candidate areas:

- **`UNIVERSAL::can`** in **hash constructor** contexts: `%h = (... unknown package ...->can(...) ...)` pairing integrity.
- **Concat parity**: **`no utf8` / `use utf8`** literals **`Encode::is_utf8`** expectations (see **`dev/design/string_encoding_context_plan.md`** verification section).

### 5. [P4] Optional XS

Upstream ships **`Sub::HandlesVia::XS`** (skipped when absent). No action unless performance work demands it — pure Perl path is canonical for portability.

---

## Dependencies (mental model)

| Module | Role |
|--------|------|
| **Type::Tiny** / **Exporter::Tiny** | Types and coercion surfaces for handlers |
| **Eval::TypeTiny** | **`eval_closure`** — compiles delegated method bodies |
| **Mite / Sub::HandlesVia::Mite** | Constructor / attribute sugar; **`__META__`** uses **`can('BUILDARGS')`** |
| **Moo** | Primary toolkit exercised in **`t/02moo*.t`** |
| **Moose / Mouse / Corinna** | Separate test dirs; skip if stacks incomplete |

Issues in **Eval::TypeTiny** often surface as **compile errors inside generated strings** rather than `.pm` syntax errors — treat reports as **`$src` forensic** first.

---

## Related docs

| Document | Topic |
|----------|--------|
| [type_tiny.md](type_tiny.md) | Type::Tiny quirks on PerlOnJava |
| [moo_support.md](moo_support.md) | Moo stack status |
| [moose_support.md](moose_support.md) | Moose prerequisites |

---

## Progress log

| Date | Milestone |
|------|-----------|
| 2026-05-15 | **`UNIVERSAL::can`** empty-list/hash corruption fixed; **`__META__`** validated; `\x{c2}` eval blocker documented as next P0 |
| 2026-05-15 | **`UNIVERSAL::can`** split: **LIST** failures → `(undef)`, **scalar**/compile-time failures → empty list (restores `perl5_t` regressions while fixing Mite splice) |
