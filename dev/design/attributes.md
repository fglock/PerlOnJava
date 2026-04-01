# Attribute System Implementation Design

## Overview

Implement Perl's `attributes` pragma for PerlOnJava, enabling `attributes::get()`, `attributes->import()`, and the full `MODIFY_*_ATTRIBUTES` / `FETCH_*_ATTRIBUTES` callback chain for CODE, SCALAR, ARRAY, and HASH types.

**Baseline:** 62/216 tests passing (28.7%) across 4 test files.
**Target:** ~140+/216 tests (65%+).

## Perl Semantics (from `perldoc attributes`)

### Core Behavior

When Perl encounters attribute declarations, it translates them into calls to the `attributes` module:

```perl
# Sub attributes (compile-time)
sub foo : method;
# equivalent to:
use attributes __PACKAGE__, \&foo, 'method';

# Variable attributes (my = run-time, our = compile-time)
my ($x, @y, %z) : Bent = 1;
# equivalent to:
use attributes ();
my ($x, @y, %z);
attributes::->import(__PACKAGE__, \$x, 'Bent');
attributes::->import(__PACKAGE__, \@y, 'Bent');
attributes::->import(__PACKAGE__, \%z, 'Bent');
($x, @y, %z) = 1;

# Typed variable (package comes from type, not current package)
package Dog;
my Canine $spot : Watchful;
# equivalent to:
attributes::->import(Canine => \$spot, "Watchful");
```

### Built-in Attributes

| Type | Attribute | Purpose |
|------|-----------|---------|
| CODE | `lvalue` | Marks sub as valid lvalue |
| CODE | `method` | Marks sub as method (suppresses ambiguity warnings) |
| CODE | `prototype(...)` | Sets prototype |
| CODE | `const` | Experimental: calls anon sub immediately, captures return as constant |
| SCALAR/ARRAY/HASH | `shared` | Thread-sharing (no-op in PerlOnJava) |

### `import()` Flow

1. Get `reftype` of the reference (CODE, SCALAR, ARRAY, HASH)
2. Check if `$home_stash` has `MODIFY_<type>_ATTRIBUTES` (via `UNIVERSAL::can`)
3. If handler exists:
   - First apply built-in attributes via `_modify_attrs()` (returns non-built-in attrs)
   - Pass remaining attrs to `MODIFY_<type>_ATTRIBUTES($pkg, $ref, @remaining)`
   - If handler returns empty list AND remaining attrs are all-lowercase: emit "may clash with future reserved word" warning
   - If handler returns non-empty list: croak with "Invalid <TYPE> attribute(s)"
4. If no handler: apply built-in attrs; anything unrecognized is an error

### `get()` Flow

1. Get `reftype` of the reference
2. Determine stash via `_guess_stash()` (for CODE: original package; fallback to `caller`)
3. Get built-in attributes via `_fetch_attrs()`
4. If stash has `FETCH_<type>_ATTRIBUTES`: call it and merge results
5. Return combined list

### Error Messages (must match exactly)

```
Invalid CODE attribute: "plugh"
Invalid CODE attributes: "plugh" : "xyzzy"
Invalid SCALAR attribute: "plugh"
Invalid SCALAR attributes: "switch(10,foo(7,3))" : "expensive"
Unterminated attribute parameter in attribute list
Invalid separator character '+' in attribute list
Invalid separator character ':' in attribute list
SCALAR package attribute may clash with future reserved word: "plugh"
SCALAR package attributes may clash with future reserved words: "plugh" : "plover"
lvalue attribute applied to already-defined subroutine
lvalue attribute removed from already-defined subroutine
Useless use of attribute "const"
```

## Current State

### What Already Works

| Component | Status | Location |
|-----------|--------|----------|
| Sub attribute parsing (`:attr`, `:attr(args)`) | Done | `SubroutineParser.consumeAttributes()` (line 633) |
| Variable attribute parsing | Parsed but **ignored** | `OperatorParser.parseOperatorMyOurState()` (line 413) |
| `:prototype(...)` extraction | Done | `SubroutineParser.consumeAttributes()` (line 650) |
| `RuntimeCode.attributes` storage | Done | `RuntimeCode.java` (line 267) |
| `SubroutineNode.attributes` in AST | Done | `SubroutineNode.java` (line 20) |
| `MODIFY_CODE_ATTRIBUTES` dispatch | Done | `SubroutineParser.callModifyCodeAttributes()` (line 1046) |
| `:=` error detection | Done | `SubroutineParser.consumeAttributes()` (line 638) |
| Empty attr list (`: =`) handling | Done | `SubroutineParser.consumeAttributes()` (line 640) |

### What Is Missing

| Component | Impact |
|-----------|--------|
| `attributes.pm` module | ~50 tests — needed for `use attributes`, `attributes::get`, `attributes->import` |
| `MODIFY_SCALAR/ARRAY/HASH_ATTRIBUTES` dispatch | ~26 tests — variable attrs parsed but never dispatched |
| `FETCH_*_ATTRIBUTES` callbacks | ~3 tests — needed for `attributes::get` user-defined attrs |
| Variable attribute errors ("Invalid SCALAR attribute") | ~18 tests — currently silently ignored |
| `_modify_attrs` / `_fetch_attrs` / `_guess_stash` (XS equivalents) | Needed by `attributes.pm` |
| Attribute removal (`-lvalue`, `-method`) | ~5 tests |
| `:const` attribute support | ~7 tests |
| `Attribute::Handlers` integration | 4 tests (low priority) |

## Implementation Strategy

The key insight is that Perl's `attributes.pm` relies on three XS functions (`_modify_attrs`, `_fetch_attrs`, `_guess_stash`) that operate on Perl internals. In PerlOnJava, we implement these as a Java module (`Attributes.java`) that directly accesses `RuntimeCode.attributes` and other runtime structures.

The architecture:

```
attributes.pm (Perl)           Attributes.java (Java)
├── import()                   ├── _modify_attrs(svref, @attrs)
│   └── calls _modify_attrs    │     └── validates built-in attrs
│   └── calls MODIFY_*_ATTRS   │     └── applies lvalue/method/prototype
├── get()                      ├── _fetch_attrs(svref)
│   └── calls _fetch_attrs     │     └── reads RuntimeCode.attributes
│   └── calls FETCH_*_ATTRS    ├── _guess_stash(svref)
├── reftype()                  │     └── returns packageName for CODE refs
│   └── calls Java ref()       └── reftype(svref)
└── require_version()                └── returns underlying ref type
```

Variable attribute dispatch happens in the **emitter/compiler** — when a `my`/`our`/`state` declaration has attributes in its AST annotations, the emitter generates code to call `attributes::->import(PKG, \$var, @attrs)` at the appropriate time (compile-time for `our`, run-time for `my`/`state`).

## Components

### 1. Java Module: `Attributes.java`

**New file:** `src/main/java/org/perlonjava/perlmodule/Attributes.java`

Implements the XS-equivalent functions that `attributes.pm` calls:

```java
public class Attributes extends PerlModuleBase {
    public Attributes() {
        super("attributes", false);
    }

    public void initialize() {
        // Register XS-equivalent functions
        registerMethod("_modify_attrs", null, null);  // built-in attr application
        registerMethod("_fetch_attrs", null, null);    // built-in attr retrieval
        registerMethod("_guess_stash", null, null);    // package name lookup
        registerMethod("reftype", null, null);         // underlying ref type
    }
}
```

#### `_modify_attrs($svref, @attrs)`

- For CODE refs: validates `lvalue`, `method`, `prototype(...)`, `const`; applies them to `RuntimeCode`; returns unrecognized attrs
- For SCALAR/ARRAY/HASH refs: validates `shared`; returns unrecognized attrs
- Handles `-attr` prefix for removal (removes from `RuntimeCode.attributes`)
- Emits `misc` warnings for `lvalue`/`-lvalue` on already-defined subs

#### `_fetch_attrs($svref)`

- For CODE refs: returns the built-in attributes from `RuntimeCode.attributes` (filtered to `lvalue`, `method`, `const`)
- For SCALAR/ARRAY/HASH refs: returns empty list (no built-in variable attrs in PerlOnJava)

#### `_guess_stash($svref)`

- For CODE refs: returns `RuntimeCode.packageName` (the original compilation package)
- For other refs: returns `undef` (caller will use `caller()` as fallback)

#### `reftype($svref)`

- Returns the underlying reference type ignoring bless: `"CODE"`, `"SCALAR"`, `"ARRAY"`, `"HASH"`, `"REF"`, `"GLOB"`, `"REGEXP"`

### 2. Perl Module: `attributes.pm`

**New file:** `src/main/perl/lib/attributes.pm`

Port of the system Perl `attributes.pm` (116 lines of code before POD). The Perl-side logic handles:

- `import()`: Exporter integration when called without a ref; otherwise validates via `_modify_attrs` + `MODIFY_*_ATTRIBUTES` dispatch + error/warning generation
- `get()`: Combines `_fetch_attrs` + `FETCH_*_ATTRIBUTES` dispatch
- `reftype()`: Delegates to Java `reftype`
- Warning messages: "may clash with future reserved word", "lvalue attribute applied/removed"
- Error messages: "Invalid TYPE attribute(s)"

```perl
package attributes;
our $VERSION = 0.36;
@EXPORT_OK = qw(get reftype);
@EXPORT = ();
%EXPORT_TAGS = (ALL => [@EXPORT, @EXPORT_OK]);

use strict;

sub croak { require Carp; goto &Carp::croak; }
sub carp  { require Carp; goto &Carp::carp;  }

my %msg = (
    lvalue  => 'lvalue attribute applied to already-defined subroutine',
    -lvalue => 'lvalue attribute removed from already-defined subroutine',
    const   => 'Useless use of attribute "const"',
);

sub _modify_attrs_and_deprecate {
    my $svtype = shift;
    grep {
        $svtype eq 'CODE' && exists $msg{$_} ? do {
            require warnings;
            warnings::warnif('misc', $msg{$_});
            0;
        } : 1
    } _modify_attrs(@_);
}

sub import {
    @_ > 2 && ref $_[2] or do {
        require Exporter;
        goto &Exporter::import;
    };
    my (undef, $home_stash, $svref, @attrs) = @_;
    my $svtype = uc reftype($svref);
    my $pkgmeth;
    $pkgmeth = UNIVERSAL::can($home_stash, "MODIFY_${svtype}_ATTRIBUTES")
        if defined $home_stash && $home_stash ne '';
    my @badattrs;
    if ($pkgmeth) {
        my @pkgattrs = _modify_attrs_and_deprecate($svtype, $svref, @attrs);
        @badattrs = $pkgmeth->($home_stash, $svref, @pkgattrs);
        if (!@badattrs && @pkgattrs) {
            require warnings;
            return unless warnings::enabled('reserved');
            @pkgattrs = grep { m/\A[[:lower:]]+(?:\z|\()/ } @pkgattrs;
            if (@pkgattrs) {
                for my $attr (@pkgattrs) { $attr =~ s/\(.+\z//s; }
                my $s = ((@pkgattrs == 1) ? '' : 's');
                carp "$svtype package attribute$s " .
                    "may clash with future reserved word$s: " .
                    join(' : ', @pkgattrs);
            }
        }
    } else {
        @badattrs = _modify_attrs_and_deprecate($svtype, $svref, @attrs);
    }
    if (@badattrs) {
        croak "Invalid $svtype attribute" .
            ((@badattrs == 1) ? '' : 's') . ": " .
            join(' : ', @badattrs);
    }
}

sub get ($) {
    @_ == 1 && ref $_[0] or
        croak 'Usage: ' . __PACKAGE__ . '::get $ref';
    my $svref = shift;
    my $svtype = uc reftype($svref);
    my $stash = _guess_stash($svref);
    $stash = caller unless defined $stash;
    my $pkgmeth;
    $pkgmeth = UNIVERSAL::can($stash, "FETCH_${svtype}_ATTRIBUTES")
        if defined $stash && $stash ne '';
    return $pkgmeth ?
        (_fetch_attrs($svref), $pkgmeth->($stash, $svref)) :
        (_fetch_attrs($svref));
}

sub require_version { goto &UNIVERSAL::VERSION }

1;
```

### 3. Variable Attribute Dispatch (Emitter Changes)

**Modified file:** `src/main/java/org/perlonjava/astvisitor/EmitVariable.java` (or equivalent)

When processing a `my`/`our`/`state` declaration node that has `"attributes"` in its annotations, the emitter must generate code equivalent to:

```perl
# For: my $x : Foo
attributes::->import(__PACKAGE__, \$x, "Foo");

# For typed: my ClassName $x : Foo  
attributes::->import("ClassName", \$x, "Foo");

# For list: my ($x, @y) : Foo
attributes::->import(__PACKAGE__, \$x, "Foo");
attributes::->import(__PACKAGE__, \@y, "Foo");
```

**Timing:**
- `our` declarations: emit at compile-time (immediately during parse)
- `my`/`state` declarations: emit as runtime code (part of the generated bytecode)

**Implementation approach:** After the variable declaration node is emitted, check for the `"attributes"` annotation. If present, emit a method call to `attributes::->import(PKG, \$var, @attrs)` where PKG is the typed class name (if present) or the current package.

### 4. Error Message Improvements in Parser

**Modified file:** `SubroutineParser.java`

The `consumeAttributes()` method needs two additional error detections:

1. **Unterminated attribute parameter**: Already handled by `StringParser.parseRawString()` when `(` is not balanced — verify the error message matches `"Unterminated attribute parameter in attribute list"`

2. **Invalid separator character**: After consuming an attribute, if the next token is not `:`, whitespace, `;`, `{`, or `=`, emit `"Invalid separator character 'X' in attribute list"`

### 5. `callModifyCodeAttributes` Error Message Fix

The existing `callModifyCodeAttributes` in `SubroutineParser.java` formats unrecognized attributes with commas: `"plugh", "xyzzy"`. Perl uses ` : ` as separator: `"plugh" : "xyzzy"`. Fix the separator in the StringBuilder loop.

## Test Coverage Analysis

### Currently Passing (62/216)

| File | Pass/Total | What Works |
|------|-----------|------------|
| attrs.t | 49/130 | `:method`/`:lvalue` on subs, MODIFY_CODE_ATTRIBUTES, `:=` errors, empty attr lists, bug #66970 CV identity |
| attrproto.t | 3/52 | Basic `:prototype(...)` override (tests 1-3) |
| attrhand.t | 0/0 | Crashes immediately (needs `attributes.pm`) |
| uni/attrs.t | 10/34 | Unicode CODE attrs, MODIFY_CODE_ATTRIBUTES die, deref errors, CV identity |

### Expected Gains by Phase

| Phase | Component | Estimated New Passes |
|-------|-----------|---------------------|
| 1 | `Attributes.java` + `attributes.pm` | +5 (loading, reftype, basic get) |
| 2 | `attributes::get()` with `_fetch_attrs` | +8 (Group H in attrs.t) |
| 3 | `attributes->import()` validation matrix | +32 (Group L in attrs.t) |
| 4 | Variable attribute dispatch (MODIFY_SCALAR/ARRAY/HASH) | +20 (Groups E, F, J in attrs.t + uni) |
| 5 | lvalue/const warnings + `-attr` removal | +12 (Groups T, W in attrs.t) |
| 6 | `use attributes` prototype handling in attrproto.t | +15 |
| 7 | Error message fixes (separator in callModifyCodeAttributes) | +3 |
| **Total** | | **~95 new passes → ~157/216 (73%)** |

## Files to Modify

### New Files

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/perlmodule/Attributes.java` | XS-equivalent functions |
| `src/main/perl/lib/attributes.pm` | Perl `attributes` pragma |

### Modified Files

| File | Change |
|------|--------|
| `SubroutineParser.java` | Fix error message separator (`, ` → ` : `); add "Invalid separator character" detection |
| `EmitVariable.java` or `EmitOperator.java` | Emit `attributes::->import()` for variable declarations with attrs |
| `BytecodeCompiler.java` | Same for interpreter backend |

## Implementation Order

### Phase 1: Java Backend + attributes.pm (foundation)

1. Create `Attributes.java` with `_modify_attrs`, `_fetch_attrs`, `_guess_stash`, `reftype`
2. Create `attributes.pm` with `import()`, `get()`, `reftype()`
3. Fix `callModifyCodeAttributes` separator (`, ` → ` : `)
4. Run tests, verify `use attributes` loads and `attributes::get` returns built-in attrs

### Phase 2: Variable Attribute Dispatch

5. In the JVM emitter: when a variable declaration has `"attributes"` annotation, emit `attributes::->import(PKG, \$var, @attrs)` calls
6. In the bytecode interpreter: same
7. Run tests, verify `MODIFY_SCALAR_ATTRIBUTES` is called for `my $x : Foo`

### Phase 3: Polish and Edge Cases

8. Add "Invalid separator character" error to parser
9. Add "Unterminated attribute parameter" error message alignment
10. Handle `-attr` removal in `_modify_attrs`
11. Handle `:const` warning
12. `use attributes __PACKAGE__, \&sub, "prototype(X)"` handling
13. Run full test suite, measure improvement

## Open Questions

1. **Variable attribute storage**: Should variables store their attributes? Currently `RuntimeCode` has an `attributes` field, but `RuntimeScalar`/`RuntimeArray`/`RuntimeHash` do not. Most test cases only need the `MODIFY_*_ATTRIBUTES` callback (side effects like `tie`), not persistent storage. The `FETCH_*_ATTRIBUTES` tests are only for CODE refs. **Decision: Don't add storage to variables yet — not needed for any current test.**

2. **`_modify_attrs` implementation level**: The system Perl implements this as XS that directly manipulates SV flags. In PerlOnJava, we access `RuntimeCode.attributes` from Java. For CODE refs this is straightforward. For variable refs, we only need to validate built-in attrs (`shared`) and return unrecognized ones — no actual flag-setting needed since `shared` is a no-op.

3. **Attribute::Handlers**: The module exists at `src/main/perl/lib/Attribute/Handlers.pm` but depends on `attributes.pm` + CHECK blocks. Getting attrhand.t to pass likely requires CHECK block support (see `op/blocks.t` in the test failures doc). **Decision: Defer — only 4 tests.**

4. **`our` variable attribute timing**: The perldoc says `our` attributes are applied at compile-time. This means the emitter needs to call `attributes::->import()` immediately during parsing (like `callModifyCodeAttributes` does for subs), not defer to runtime. **Decision: Handle in Phase 2.**

## Related Documents

- `dev/prompts/test-failures-not-quick-fix.md` — Section 8 (Attribute System)
- `dev/design/defer_blocks.md` — Similar implementation pattern

---

## Progress Tracking

### Current Status: Phase 1 not started

### Completed Phases
- (none yet)

### Baseline Test Results (2026-04-01)
- attrs.t: 49/130
- attrproto.t: 3/52 (incomplete — crashes at test 19)
- attrhand.t: 0/0 (crashes immediately)
- uni/attrs.t: 10/34
- **Total: 62/216 (28.7%)**

### Next Steps
1. Create `Attributes.java` with `_modify_attrs`, `_fetch_attrs`, `_guess_stash`, `reftype`
2. Create `attributes.pm` (port from system Perl)
3. Fix error message separator in `callModifyCodeAttributes`
4. Test Phase 1

### PR
- https://github.com/fglock/PerlOnJava/pull/420
