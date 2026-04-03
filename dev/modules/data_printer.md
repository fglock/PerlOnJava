# Data::Printer Fix Plan

## Overview

- **Module**: Data::Printer 1.002001
- **Test command**: `./jcpan -j 8 -t Data::Printer`
- **Status**: Phase 2c complete — READONLY_SCALAR dispatch wired across codebase, ready for Phase 3

## Test Results Summary

### Baseline: 15/41 test files passing (191/545 subtests fail)

### After Phase 1: 23/41 test files passing (33/545 subtests fail)

### After Phase 2: pending re-run (READONLY_SCALAR type fully wired)

| Test File | Baseline | After Phase 1 | Notes |
|-----------|----------|---------------|-------|
| t/000-load.t | PASS | PASS | |
| t/000.0-nsort.t | PASS | PASS | |
| t/000.1-home.t | **FAIL** 1/4 | **FAIL** 1/4 | glob `~` not expanded (not `$ENV{HOME}`) |
| t/000.2-warn.t | PASS | PASS | |
| t/001-object.t | PASS | PASS | |
| t/002-scalar.t | **FAIL** 68/72 | **FAIL** 12/72 | Remaining: readonly vars, charnames::viacode, `$!` dualvar |
| t/003-ref.t | **FAIL** 5/7 | **FAIL** 2/7 | weak circular ref detection |
| t/004-vstring.t | **FAIL** 1/1 | PASS | Fixed by isweak |
| t/005-lvalue.t | **FAIL** 2/2 | **FAIL** 2/2 | LVALUE ref type detection |
| t/006-glob.t | PASS | PASS | |
| t/007.format.t | **FAIL** 1/1 | **FAIL** 1/1 | FORMAT reference returns undef |
| t/008-regex.t | PASS | PASS | |
| t/009-array.t | **FAIL** 18/18 | **FAIL** 1/18 | 1 remaining: weak circular ref |
| t/010-hashes.t | **FAIL** 16/16 | PASS | Fixed by isweak |
| t/011-class.t | **FAIL** 27/38 | **FAIL** 2/38 | 2 remaining + 11 planned didn't run (B::CV::ROOT) |
| t/011.1-attributes.t | PASS | PASS | |
| t/011.2-roles.t | PASS | PASS | |
| t/011.3-object_pad.t | PASS | PASS | |
| t/012-code.t | **FAIL** 4/4 | **FAIL** 4/4 | `B::CV::ROOT` missing - crash |
| t/013-refcount.t | **FAIL** 16/17 | **FAIL** 16/17 | `B::SV::RV` missing - crash |
| t/014-memsize.t | PASS | PASS | |
| t/015-multiline.t | **FAIL** 1/1 | PASS | Fixed by isweak |
| t/016-merge_options.t | PASS | PASS | |
| t/017-rc_file.t | PASS | PASS | |
| t/018-alias.t | **FAIL** 1/1 | PASS | Fixed by isweak |
| t/019-output.t | **FAIL** 0 ran | **FAIL** 0 ran | `SEEK_SET` bareword error |
| t/020-return_value.t | SKIP | SKIP | Capture::Tiny not found |
| t/021-p_vs_object.t | **FAIL** 24/26 | **FAIL** 24/26 | `B::SV::RV` missing |
| t/022-no_prototypes.t | **FAIL** 4/7 | **FAIL** 1/7 | 1 remaining: readonly on `"test"` literal |
| t/023-filters.t | **FAIL** 11/11 | PASS | Fixed by isweak |
| t/024-tied.t | **FAIL** 18/18 | **FAIL** 14/18 | ClassCastException + untie issue |
| t/025-profiles.t | **FAIL** 4/34 | **FAIL** 4/34 | Dumper profile: lvalue/format + glob name + B::CV::ROOT |
| t/026-caller_message.t | **FAIL** 2/2 | PASS | Fixed by isweak |
| t/027-nativeperlclass.t | SKIP | SKIP | `class` keyword |
| t/100-filter_datetime.t | **FAIL** 6/21 | PASS | Fixed by isweak |
| t/101-filter_db.t | **FAIL** 23/24 | **FAIL** 23/24 | DBI not connecting |
| t/102-filter_digest.t | **FAIL** 3/21 | **FAIL** 3/21 | Digest filter |
| t/103-filter_contenttype.t | **FAIL** 6/32 | **FAIL** 1/32 | 1 remaining: hexdump trailing nulls |
| t/104-filter_web.t | **FAIL** 7/21 | PASS | Fixed by isweak |
| t/998-color.t | **FAIL** 1/1 | **FAIL** 1/1 | crash |
| t/999-themes.t | PASS | PASS | |

## Completed Phases

### Phase 1 (COMPLETED 2026-04-03)

**Fixed**: `isweak()` always returning true + `readonly()` stub

- `ScalarUtil.isweak()` → always return false (weaken is a no-op on JVM)
- `ScalarUtil.readonly()` → detect `RuntimeScalarReadOnly` instances (compile-time constants)
- **Result**: 191 → 33 subtest failures, 8 test files fixed
- **Files**: `ScalarUtil.java`, `Builtin.java`

### Phase 2 (COMPLETED 2026-04-03)

**Fixed**: `Internals::SvREADONLY` via new `READONLY_SCALAR` type + `set()` fast-path optimization

- Added `READONLY_SCALAR = 12` type constant to `RuntimeScalarType.java`
- Rewrote `Internals.svReadonly()` to wrap/unwrap using READONLY_SCALAR type
- Added READONLY_SCALAR guards to all 6 `set()` methods (throw read-only error)
- Added `case READONLY_SCALAR` delegates to all getter methods: `getNumber`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `toString`, `toStringNoOverload`, `getDefinedBoolean`, `toStringRef`
- Added `case READONLY_SCALAR` delegates to all deref methods: `arrayDeref`, `hashDeref`, `scalarDeref`, `scalarDerefNonStrict`, `hashDerefNonStrict`, `arrayDerefNonStrict`, `globDeref`, `globDerefNonStrict`, `codeDerefNonStrict`
- Added `case READONLY_SCALAR` read-only error to all mutation methods: `preAutoIncrement`, `postAutoIncrement`, `preAutoDecrement`, `postAutoDecrement`
- Updated copy constructor to unwrap READONLY_SCALAR
- Updated `ScalarUtil.readonly()` to check both `RuntimeScalarReadOnly` and `READONLY_SCALAR`
- Split `set(RuntimeScalar)` into inlineable fast path (`type < TIED_SCALAR`) + `setLarge()` slow path with switch dispatchers
- Added magic boundary comment in `RuntimeScalarType.java`
- **Files**: `RuntimeScalarType.java`, `RuntimeScalar.java`, `Internals.java`, `ScalarUtil.java`

### Phase 2c: READONLY_SCALAR dispatch across codebase (COMPLETED 2026-04-03)

**Fixed**: All type-dispatch switches and comparisons now handle READONLY_SCALAR transparently.

Uniform pattern applied:
- **Switches**: `case READONLY_SCALAR:` delegate to unwrapped value (zero overhead for non-READONLY paths)
- **Ternaries** (BitwiseOperators): `type < TIED_SCALAR ? original : type == TIED_SCALAR ? tiedFetch() : type == READONLY_SCALAR ? unwrap : original`
- **If-chains**: `if (type == READONLY_SCALAR) unwrap` at method entry

Key fixes:
- `BitwiseOperators.java`: And/Or/Xor/Not + integer variants — fixed `use constant` values being treated as strings (was the root cause of op/svflags.t regression 4→16)
- `ScalarUtils.looksLikeNumber()`: delegate for READONLY_SCALAR
- `RuntimeScalarType.blessedId()/isReference()`: unwrap before REFERENCE_BIT check
- `RuntimeCode.apply()`: unwrap in all 3 overloads (prevents "Not a CODE reference" crash)
- `RuntimeGlob.set()`, `RuntimeStashEntry.set()`: unwrap in switch/if (prevents "typeglob assignment not implemented" crash)
- `ScalarUtil.java`: blessed, refaddr, reftype, isdual, looks_like_number, openhandle, set_prototype
- `Builtin.java`: isBoolean, refaddr, reftype, createdAsNumber
- `Universal.can()`, `Universal.VERSION()`, `NextMethod.getSearchClass()`
- `RuntimeRegex.java`: pattern/replacement/quotedRegex type checks
- `Attributes.getRefType()`, `ReferenceOperators.ref()` inner switch
- Serialization: `Json.java`, `YAMLPP.java`, `Storable.java` (3 methods), `Toml.java` (2 methods)
- `RuntimeScalar.isString()`, `_charnames.pm` readonly crash fix

Lvalue operators (`substr`, `vec`, `chop`, `++/--`) already work correctly — write paths go through `set()` on the parent scalar, which triggers the READONLY_SCALAR modification check.

- **Files** (20 files changed): `BitwiseOperators.java`, `ReferenceOperators.java`, `Attributes.java`, `Builtin.java`, `Json.java`, `ScalarUtil.java`, `Storable.java`, `Toml.java`, `Universal.java`, `YAMLPP.java`, `RuntimeRegex.java`, `NextMethod.java`, `RuntimeCode.java`, `RuntimeGlob.java`, `RuntimeScalar.java`, `RuntimeScalarType.java`, `RuntimeStashEntry.java`, `ScalarUtils.java`, `_charnames.pm`
- **Regression tests**: op/svflags.t 16/23 (restored from 4), op/index.t 415/415, re/subst.t 227/281 (all at baseline)

## Remaining Error Categories

### 1. HIGH: Missing `B::CV::ROOT` and `B::SV::RV` methods

- **Affected tests**: t/012-code.t (4), t/013-refcount.t (16), t/021-p_vs_object.t (24), t/011-class.t (11 didn't run)
- **Root cause**: `B.pm` stub doesn't define `ROOT` on `B::CV` or `RV` on `B::SV`
- **Fix**: Add stub methods to `src/main/perl/lib/B.pm`
  - `B::CV::ROOT` → return `B::OP->new()` (Data::Printer checks if coderef has a body)
  - `B::SV::RV` → return the referenced value wrapped in a B:: object

### 2. MEDIUM: `charnames::viacode` returns empty strings

- **Affected tests**: t/002-scalar.t (3 unicode_charnames tests)
- **Root cause**: `_charnames.pm` does `do "unicore/Name.pl"` but that file doesn't exist. ICU4J has `UCharacter.getName()` available but unused.
- **Fix**: Either generate `unicore/Name.pl` via `dev/import-perl5/sync.pl`, or add a Java-side `charnames::viacode` using ICU4J's `UCharacter.getName(codePoint)`

### 3. MEDIUM: `SEEK_SET` bareword error in t/019-output.t

- **Affected tests**: t/019-output.t (all)
- **Root cause**: `Bareword "SEEK_SET" not allowed while "strict subs"`. Fcntl.pm defines SEEK_SET correctly but the test may use `use Fcntl;` (default export) which doesn't include SEEK_SET. Need to check the actual test code — may be a missing `:seek` tag or Fcntl export issue.

### 4. MEDIUM: Tied variable ClassCastException

- **Affected tests**: t/024-tied.t (crashes after test 5)
- **Root cause**: `RuntimeTiedArrayProxyEntry cannot be cast to TieScalar` — unsafe casts in `TieScalar.java` (lines 42,49) and `TieOperators.java` (lines 126,190)
- **Fix**: Use `instanceof TiedVariableBase` checks before casting. `getSelf()` exists on `TiedVariableBase`. Handle proxy entries differently in `untie()`/`tied()`.

### 5. MEDIUM: glob `~` not expanded (t/000.1-home.t)

- **Affected tests**: t/000.1-home.t (1 failure)
- **Root cause**: Data::Printer uses `glob("~")` to find home dir. `ScalarGlobOperator.java` and `File::Glob::bsd_glob` have no tilde expansion. `$ENV{HOME}` works fine — it's the glob that returns literal `~`.
- **Fix**: Add tilde expansion to `ScalarGlobOperator.java`: when pattern starts with `~`, replace with `System.getProperty("user.home")`

### 6. LOW: LVALUE ref detection, DBI, FORMAT, color, Digest

- LVALUE ref detection (t/005-lvalue.t)
- DBI filter (t/101-filter_db.t)
- FORMAT reference (t/007.format.t)
- Color/ANSI (t/998-color.t)
- Digest filter (t/102-filter_digest.t)
- Dumper profile (t/025-profiles.t)

## Fix Plan (Remaining Phases)

### Phase 3: B module stubs (NEXT)

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 3a | Add `B::CV::ROOT` stub | src/main/perl/lib/B.pm | t/012-code.t, t/011-class.t |
| 3b | Add `B::SV::RV` stub | src/main/perl/lib/B.pm | t/013-refcount.t, t/021-p_vs_object.t |

### Phase 4: charnames::viacode + SEEK_SET + glob tilde

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 4a | Implement `charnames::viacode` via ICU4J | Java-side or sync.pl | t/002-scalar.t unicode tests |
| 4b | Fix Fcntl SEEK_SET export | Investigate test code | t/019-output.t |
| 4c | Add glob `~` tilde expansion | ScalarGlobOperator.java | t/000.1-home.t |

### Phase 5: Tied variable cast fix

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 5a | Fix unsafe TieScalar casts | TieScalar.java, TieOperators.java | t/024-tied.t |

### Phase 6: Low priority

- LVALUE ref detection (t/005-lvalue.t)
- DBI filter (t/101-filter_db.t)
- FORMAT reference (t/007.format.t)
- Color/ANSI (t/998-color.t)
- Digest filter (t/102-filter_digest.t)
- Dumper profile (t/025-profiles.t)

### Future: MAGIC type consolidation

Consolidate `TIED_SCALAR` and `READONLY_SCALAR` into a single `MAGIC` type with a flags field (tied, readonly, tainted, etc.). This keeps all non-magic types on the fast path with a single `type >= MAGIC_THRESHOLD` guard. Requires refactoring 70+ `TIED_SCALAR` references — best done in a separate PR.
