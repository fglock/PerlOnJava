# Data::Printer Fix Plan

## Overview

- **Module**: Data::Printer 1.002001
- **Test command**: `./jcpan --jobs 8 -t Data::Printer`
- **Branch**: `feature/data-printer-phase3`
- **PR**: #434
- **Status**: Phase 7 complete — 599/647 subtests passing (92.6%)

## Test Results Summary

| Milestone | Test files passing | Subtests passing | Pass rate |
|-----------|-------------------|-----------------|-----------|
| Baseline | 15/41 | 354/545 | 65.0% |
| After Phase 1 | 23/41 | 512/545 | 93.9% |
| After Phase 2 | 23/41 | 512/545 | 93.9% |
| After Phase 3-6 | **29/41** | **576/624** | **92.3%** |
| After Phase 7 (DBI) | **29/41** | **599/647** | **92.6%** |

Note: subtest totals changed between runs because some tests that previously crashed now run to completion, adding subtests to the total.

### Current Test Status (After Phase 7)

| Test File | Status | Failures | Root Cause |
|-----------|--------|----------|------------|
| t/000-load.t | PASS | | |
| t/000.0-nsort.t | PASS | | |
| t/000.1-home.t | **PASS** | | Fixed Phase 6e: glob tilde uses %ENV{HOME} |
| t/000.2-warn.t | PASS | | |
| t/001-object.t | PASS | | |
| t/002-scalar.t | **FAIL** 4/72 | 2,4,36-37 | Read-only constant detection |
| t/003-ref.t | **FAIL** 2/7 | 5-6 | Weak ref detection (JVM limitation) |
| t/004-vstring.t | PASS | | |
| t/005-lvalue.t | **FAIL** 2/2 | 1-2 | LVALUE ref type not implemented |
| t/006-glob.t | PASS | | |
| t/007.format.t | **FAIL** 1/1 | 1 | FORMAT ref type not implemented |
| t/008-regex.t | PASS | | |
| t/009-array.t | **FAIL** 1/18 | 4 | Weak circular ref (JVM limitation) |
| t/010-hashes.t | PASS | | |
| t/011-class.t | **PASS** | | Fixed Phase 6a: UNIVERSAL methods |
| t/011.1-attributes.t | PASS | | |
| t/011.2-roles.t | PASS | | |
| t/011.3-object_pad.t | PASS | | |
| t/012-code.t | **FAIL** 2/4 | 2,4 | B::Deparse not implemented |
| t/013-refcount.t | **FAIL** 12/17 | 1-3,5-8,11-13,16-17 | Refcount/weak refs (JVM limitation) |
| t/014-memsize.t | PASS | | |
| t/015-multiline.t | PASS | | |
| t/016-merge_options.t | PASS | | |
| t/017-rc_file.t | PASS | | |
| t/018-alias.t | PASS | | |
| t/019-output.t | SKIP | | Capture::Tiny not found |
| t/020-return_value.t | SKIP | | Capture::Tiny not found |
| t/021-p_vs_object.t | **FAIL** 11/26 | 3,5,7,9,11,13,15,17,19,23,25 | Refcount/weak refs (JVM limitation) |
| t/022-no_prototypes.t | PASS | | |
| t/023-filters.t | PASS | | |
| t/024-tied.t | **PASS** | | Fixed Phase 6d: FETCH caching on untie |
| t/025-profiles.t | **FAIL** 10/34 | 20-23,25,29-33 | LVALUE/FORMAT ref types missing (see below) |
| t/026-caller_message.t | PASS | | |
| t/027-nativeperlclass.t | SKIP | | `class` keyword not implemented |
| t/100-filter_datetime.t | PASS | | |
| t/101-filter_db.t | **FAIL** 1/24 | 8 | DESTROY not supported — `undef $sth` can't decrement Kids (16 DBIC skipped) |
| t/102-filter_digest.t | **PASS** | | Fixed Phase 6f: Digest::SHA @ISA |
| t/103-filter_contenttype.t | **FAIL** 1/32 | 29 | Hexdump trailing null bytes |
| t/104-filter_web.t | PASS | | |
| t/998-color.t | **FAIL** 1/1 | 1 | Refcount in colored output (JVM limitation) |
| t/999-themes.t | PASS | | |

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
- **Regression tests**: op/svflags.t 16/23 (restored from 4), op/index.t 415/415, re/subst.t 228/281 (all at baseline)

### Phase 2d: PROXY type for ScalarSpecialVariable (COMPLETED 2026-04-03)

**Fixed**: `set()` fast-path copying stale fields from `$1` and other ScalarSpecialVariable proxies.

- **Root cause**: `ScalarSpecialVariable` ($1, $&, etc.) computes values lazily via `getValueAsScalar()`, but inherits `type = UNDEF` and `value = null` from RuntimeScalar's default constructor. The `set()` fast path (`type < TIED_SCALAR`) copied these stale fields, causing `$x = $1` inside `s///eg` to silently produce undef.
- **Fix**: Added `PROXY = 13` type constant (>= TIED_SCALAR) to `RuntimeScalarType.java`. ScalarSpecialVariable constructors now set `this.type = PROXY`. The fast path's existing `value.type < TIED_SCALAR` check naturally routes PROXY to the slow path, where the existing `instanceof ScalarSpecialVariable` resolution handles it.
- **Design note**: Tried placing PROXY in `RuntimeBaseProxy` (parent class), but other proxy subclasses (RuntimeArrayProxyEntry, RuntimeHashProxyEntry, etc.) have code paths that read `value` directly before materialization. Only ScalarSpecialVariable has persistently stale fields (its values are always lazy). Other proxies update their fields after vivify/set operations.
- **Files**: `RuntimeScalarType.java`, `ScalarSpecialVariable.java`, `RuntimeScalar.java`
- **Regression tests**: re/subst.t 228/281 (restored), op/svflags.t 16/23 (baseline)

### Phase 3: B module stubs (COMPLETED 2026-04-03)

**Fixed**: Missing B module introspection methods that caused crashes in Data::Printer.

- `B::CV::ROOT` → return `B::OP->new()` (all subs have bodies on JVM)
- `B::CV::const_sv` → return `\0` (non-constant subs)
- `B::class()` → strip `B::` prefix from ref (utility for Data::Printer)
- `B::NULL` package → for undefined/stub sub detection
- `B::SV::RV` → unwrap one level of reference
- Exported `class` in `@EXPORT_OK`
- **Files**: `src/main/perl/lib/B.pm`

### Phase 4: charnames, File::Temp, glob tilde (COMPLETED 2026-04-03)

- **4a**: `charnames::viacode` via ICU4J — Created `Charnames.java` with `_java_viacode` using `UCharacter.getName()`. Modified `_charnames.pm` to use Java fallback when `unicore/Name.pl` unavailable. Registered in GlobalContext with `setInc=false` to not block Perl module loading.
- **4b**: `File::Temp :seekable` export — Added SEEK_SET/SEEK_CUR/SEEK_END to `:seekable` tag and `@EXPORT_OK`. Fixed "Bareword SEEK_SET not allowed" in strict subs.
- **4c**: Glob tilde expansion — Added `expandTilde()` to `ScalarGlobOperatorHelper.java`. Reads `$ENV{HOME}` from Perl's `%ENV` first, falls back to `System.getProperty("user.home")`.
- **Files**: `Charnames.java`, `GlobalContext.java`, `_charnames.pm`, `File/Temp.pm`, `ScalarGlobOperatorHelper.java`

### Phase 5: Tied variable ClassCastException fix (COMPLETED 2026-04-03)

**Fixed**: Crash when `tied`/`untie` called on tied array/hash elements.

- Three TiedVariableBase subclasses (TieScalar, RuntimeTiedArrayProxyEntry, RuntimeTiedHashProxyEntry) all stored with `type = TIED_SCALAR`, but code blindly cast to `TieScalar`.
- Added `instanceof TieScalar` guards in `tiedDestroy()` and `tiedUntie()`
- Fixed `TieOperators.untie()` REFERENCE case with `instanceof TieScalar` pattern match
- Fixed `TieOperators.tied()` REFERENCE case with `instanceof TiedVariableBase` + null check
- **Files**: `TieScalar.java`, `TieOperators.java`

### Phase 6: Data::Printer-specific fixes (COMPLETED 2026-04-03)

**6a - UNIVERSAL methods in class introspection**:
- Set `code.packageName` and `code.subName` in `PerlModuleBase.registerMethod()` so all Java-implemented builtins have proper names via `Sub::Util::subname()`.
- Data::Printer skips methods named `__ANON__` — all UNIVERSAL methods were reported as `__ANON__` because RuntimeCode objects created by `registerMethod()` had no name set.
- **Files**: `PerlModuleBase.java`

**6b - $! (errno) as proper dualvar**:
- Changed `ErrnoVariable` to use `DUALVAR` type with `DualVar` value object in constructor, `set(int)`, and `set(String)`. Copies of `$!` now preserve both string ("No such file or directory") and numeric (2) representations.
- Fixed `local $!`: Added `ErrnoVariable` to special cases in `GlobalRuntimeScalar.makeLocal()` so the ErrnoVariable instance is preserved (not replaced with a plain GlobalRuntimeScalar). Overrode `dynamicSaveState`/`dynamicRestoreState` to save/restore the `errno` and `message` fields.
- **Root cause of `local $!` failure**: `GlobalRuntimeScalar.makeLocal()` was replacing the ErrnoVariable with a new plain GlobalRuntimeScalar in the global variable map. Subsequent `$! = 2` called the base class `set(int)` which just set `type = INTEGER`, losing the dualvar behavior.
- **Files**: `ErrnoVariable.java`, `GlobalRuntimeScalar.java`

**6c - Tied scalar FETCH caching**:
- Cache FETCH result in `TieScalar.tiedFetch()` by updating `previousValue.type`/`previousValue.value`. After untie, the cached FETCH result is restored instead of the pre-tie value, matching Perl 5's SV caching behavior.
- **Files**: `TieScalar.java`

**6d - Glob tilde uses %ENV{HOME}**:
- Updated `expandTilde()` to read `$ENV{HOME}` from Perl's `%ENV` hash (`GlobalVariable.getGlobalHash("main::ENV")`) before falling back to Java's `System.getProperty("user.home")`.
- **Files**: `ScalarGlobOperatorHelper.java`

**6e - Digest::SHA @ISA**:
- Changed `@ISA = qw(Exporter)` to `@ISA = qw(Exporter Digest::base)`. The previous assignment was overwriting the `Digest::base` parent set by `use base "Digest::base"` on the line above.
- **Files**: `src/main/perl/lib/Digest/SHA.pm`

### Phase 7: DBI filter support (COMPLETED 2026-04-03)

**Fixed**: DBI connect failures preventing Data::Printer's DB filter tests from running.

- **DSN attribute parsing**: Updated DBI.pm connect wrapper regex from `/^dbi:(\w+):(.*)$/i` to `/^dbi:(\w+)(?:\(([^)]*)\))?:(.*)$/i` to handle attribute syntax like `dbi:Mem(RaiseError=1):`. Parses embedded attributes and merges into `$attr` hash.
- **DBI.java connect()**: Removed `args.size() < 4` requirement — defaults user/pass to empty string when not provided.
- **DBI::db / DBI::st class names**: Changed `bless ... "DBI"` to `bless ... "DBI::db"` for database handles and `bless ... "DBI::st"` for statement handles. Added `@DBI::db::ISA = ('DBI')` and `@DBI::st::ISA = ('DBI')` for method inheritance. Data::Printer's DB filter registers for `DBI::db` and `DBI::st` classes specifically.
- **Handle attribute tracking**: Initialize `Kids`, `ActiveKids`, `Statement` on connect. Increment `Kids` and set `Statement` on prepare. Only increment `ActiveKids` for result-returning statements (`NUM_OF_FIELDS > 0`). Decrement `ActiveKids` on finish. Set `Active = 0` on disconnect.
- **$sth->{Statement}**: Set to SQL string in Java prepare method (was only stored as lowercase `sql`).
- **$dbh->{Name}**: Set to DSN rest (e.g., `dbname=:memory:`) instead of full JDBC URL. The DB filter parses this with `split /[;=]/` to show key-value pairs.
- **DBD::Mem shim**: New file `src/main/perl/lib/DBD/Mem.pm` that maps `dbi:Mem:` to `jdbc:sqlite::memory:` (using bundled sqlite-jdbc driver).
- **Result**: t/101-filter_db.t went from 0/8 DBI tests passing to 7/8 passing. Remaining 1 failure: `undef $sth` can't decrement Kids because DESTROY is not supported.
- **Files**: `DBI.pm`, `DBI.java`, `DBD/Mem.pm`

## Remaining Failures (48/647 subtests)

### Unfixable (JVM limitations) — ~29 subtests

| Category | Tests | Subtests | Why |
|----------|-------|----------|-----|
| Weak references | t/003-ref, t/009-array | 3 | `weaken`/`isweak` not implemented; JVM uses GC, not refcounting |
| Refcount tracking | t/013-refcount, t/021-p_vs_object | 23 | JVM has no SV refcount; `Scalar::Util::refcount` would need JVM-specific instrumentation |
| Colored refcount | t/998-color | 1 | Same colored output includes `(refcount: 2)` which we can't produce |
| DESTROY cleanup | t/101-filter_db | 1 | `undef $sth` can't decrement Kids count without DESTROY support |

### Missing ref types — ~13 subtests

| Category | Tests | Subtests | Why |
|----------|-------|----------|-----|
| LVALUE refs | t/005-lvalue (2), t/025-profiles (partial) | 2 | `\substr(...)` creates a plain scalar ref, not an LVALUE ref. `reftype` returns "SCALAR" not "LVALUE". |
| FORMAT refs | t/007.format (1), t/025-profiles (partial) | 1 | Format references not implemented on JVM. |
| Profile cascading | t/025-profiles | 10 | The Dumper and JSON profiles iterate all ref types and check which ones they can serialize. Missing LVALUE and FORMAT types cause off-by-one in the type list, shifting all warning messages. For example, the JSON profile reports "cannot express vstrings" where it should say "cannot express subroutines" because the type array is shifted. |

### Other — 6 subtests

| Category | Tests | Subtests | Why |
|----------|-------|----------|-----|
| Read-only constants | t/002-scalar | 4 | Literal `123` not detected as read-only. `Scalar::Util::readonly(123)` returns 0 because PerlOnJava's `$` prototype copies the value, losing the read-only flag. Tests 36-37: `Internals::SvREADONLY` on a ref shows stringified ref instead of value. |
| B::Deparse | t/012-code | 2 | Code decompilation not possible — JVM bytecode can't be decompiled back to Perl. Shows `sub { "DUMMY" }` instead of actual code body. |
| ContentType hex dump | t/103-filter_contenttype | 1 | Hexdump shows trailing null bytes `00000000` that Perl 5 doesn't. Minor string truncation difference. |

### Future: MAGIC type consolidation

Consolidate `TIED_SCALAR`, `READONLY_SCALAR`, and `PROXY` into a single `MAGIC` type with a flags field (tied, readonly, tainted, proxy, etc.). This keeps all non-magic types on the fast path with a single `type >= MAGIC_THRESHOLD` guard. Requires refactoring 70+ `TIED_SCALAR` references — best done in a separate PR.
