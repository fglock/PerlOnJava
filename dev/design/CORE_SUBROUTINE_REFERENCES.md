# CORE:: Namespace: Subroutine References for Built-in Functions

## Problem Statement

Perl 5.16+ allows built-in functions to be used as first-class subroutine references
via the `CORE::` namespace. PerlOnJava currently throws
`"Not implemented: take reference of operator \&CORE::..."` when users attempt this.

This blocks real-world Perl modules that use patterns like:

```perl
BEGIN { *shove = \&CORE::push; }
shove @array, 1, 2, 3;          # pushes on to @array

my $r = \&CORE::length;
print $r->("hello"), "\n";      # prints 5

# Tie::StdHandle uses:
&CORE::binmode(shift, @_);
&CORE::read(shift, \shift, @_);
```

## Perl 5 Specification (from `perldoc CORE`)

### Three tiers of builtins (verified on Perl 5.42)

| Tier | `\&CORE::X` | `$ref->()` | `*alias=\&CORE::X; alias()` | `defined()` | `prototype()` | Examples |
|------|-------------|-----------|------------------------------|-------------|---------------|----------|
| **1 — Full subroutine** | Returns CODE ref | Callable at runtime | Compiler inlines as native op | true | Matches `CORE_PROTOTYPES` | `push`, `length`, `abs`, `rand`, ... |
| **2 — Bareword-only** | Returns CODE ref | Dies: `"&CORE::X cannot be called directly"` | Compiler inlines as native op | true | Matches `CORE_PROTOTYPES` (some `undef`) | `chomp`, `chop`, `defined`, `delete`, `eof`, `exec`, `exists`, `lstat`, `split`, `stat`, `system`, `truncate`, `unlink` |
| **3 — No subroutine** | Returns CODE ref (!) | Dies: `"Undefined subroutine"` | Fails | **false** | `undef` | `print`, `eval`, `map`, `sort`, `if`, `my`, ... |

**Key discovery:** In Perl 5.42, `\&CORE::X` succeeds for **all** keywords (even
`\&CORE::if`). The real distinctions are:

- **Tier 1:** `$ref->()` works at runtime — these are real callable subs
- **Tier 2:** `$ref->()` and `&$ref()` die with `"cannot be called directly"`, but
  `*alias = \&CORE::X; alias(args)` works because perl **inlines the native op** at
  compile time (verified via `B::Deparse`: `my_chomp($x)` compiles to `chomp $x`)
- **Tier 3:** `$ref->()` dies with `"Undefined subroutine"`, `defined()` returns false

### Key semantics

- `\&CORE::push` returns a CODE reference that calls the built-in `push`.
- The reference can be stored in a glob: `*shove = \&CORE::push;`
- **Prototypes are preserved:** `prototype(\&CORE::push)` returns `"\@@"` — the
  wrapper sub carries the same prototype as `prototype("CORE::push")`.
- `CORE::X(...)` forces the built-in even if overridden (already works in PerlOnJava).
- `defined(&CORE::push)` returns true (Tier 1+2), false (Tier 3).
- Glob-aliased calls (`*my_push = \&CORE::push; my_push @a, 1, 2, 3`) work for
  **both** Tier 1 and Tier 2 via compile-time op inlining.

## Current State in PerlOnJava

### What already works

| Feature | Status | Location |
|---------|--------|----------|
| `CORE::func(args)` direct call | **Works** | `ParsePrimary.java:110-217` |
| `prototype("CORE::func")` | **Works** | `RuntimeCode.java:2305-2310` |
| `defined(&CORE::func)` | **Works** | `GlobalVariable.java:429-451` |
| `CORE::GLOBAL::` overrides | **Works** | `ParsePrimary.java:153-208` |
| `&CORE::__SUB__` | **Works** | `Variable.java:606-610` |

### What now works (this feature — implemented 2026-03-30)

| Feature | Status |
|---------|--------|
| `\&CORE::push`, `\&CORE::length`, etc. | **Works** — returns CODE ref |
| `my $r = \&CORE::length; $r->(...)` | **Works** — callable through ref |
| `*alias = \&CORE::func` | **Works** — glob aliasing |
| `prototype(\&CORE::func)` | **Works** — returns correct prototype |
| Tier 2: `\&CORE::chomp` | **Works** — returns ref, dies on call |

### Relevant code locations

| File | What |
|------|------|
| `ParserTables.java` `CORE_PROTOTYPES` | Canonical list of ~263 builtin names + prototypes |
| `OperatorHandler.java` `operatorHandlers` | Runtime dispatch: operator name → Java class/method/descriptor |
| `ParsePrimary.java:342-352` | The `\&CORE::` TODO/error |
| `CoreOperatorResolver.java` | Parser dispatch: operator name → parse method |
| `EmitOperatorNode.java` / `EmitBinaryOperatorNode.java` | AST → JVM bytecode dispatch |
| `Variable.java:555-647` | `&sub` / `\&sub` parsing |
| `RuntimeCode.java:2209-2296` | `createCodeReference()` runtime |

## Design

### Approach: Auto-generate Perl wrapper subs

Instead of modifying the bytecode emitter or adding special CODE reference types,
generate Perl wrapper subroutines at compile-time that delegate to the built-in
operators. This is:

1. **Simple** — uses existing sub definition and `CORE::` call infrastructure
2. **Correct** — the generated sub IS a real CODE reference, so `\&`, aliasing, `->()` all work
3. **Low-risk** — no changes to the bytecode emitter, interpreter, or runtime dispatch
4. **Consistent with Perl 5** — Perl 5 internally does the same thing (special CV wrappers)

### Prototype preservation

**The generated wrappers MUST carry the correct Perl prototype.** Verified on Perl 5.42:
`prototype(\&CORE::push)` returns `"\@@"`, matching `prototype("CORE::push")`.

The wrapper uses Perl's prototype syntax on the `sub` declaration:

```perl
# Prototype "\@@" on the wrapper matches the builtin
sub CORE::push (\@@) { CORE::push(@{shift @_}, @_) }

# Prototype "_" for unary functions
sub CORE::length (_) { CORE::length($_[0]) }
```

For Tier 2 (bareword-only) functions that have prototypes, the wrapper also carries
the prototype but dies with the correct error when called directly.

### Generated wrapper shape

Wrappers are generated **on demand** — only when user code first references
`\&CORE::X` for a given builtin. For each referenceable builtin, the generated
wrapper sub lives in the `CORE` package:

```perl
# For a unary function with prototype "_" (uses $_):
sub CORE::length (_) { CORE::length($_[0]) }

# For a function with prototype ";$" (optional scalar arg):
sub CORE::rand (;$) { CORE::rand($_[0]) }
sub CORE::chdir (;$) { CORE::chdir($_[0]) }

# For list operators with prototype "\@@":
sub CORE::push (\@@) { CORE::push(@{shift @_}, @_) }
sub CORE::unshift (\@@) { CORE::unshift(@{shift @_}, @_) }

# For functions with prototype "$$":
sub CORE::rename ($$) { CORE::rename($_[0], $_[1]) }

# For functions with prototype "@" (flat list):
sub CORE::die (@) { CORE::die(@_) }
sub CORE::warn (@) { CORE::warn(@_) }
sub CORE::chmod (@) { CORE::chmod(@_) }

# For zero-arg functions with prototype "":
sub CORE::time () { CORE::time() }
sub CORE::wait () { CORE::wait() }
sub CORE::wantarray () { CORE::wantarray() }

# For Tier 2 (bareword-only) - wrapper dies when called directly:
sub CORE::chomp { die "&CORE::chomp cannot be called directly" }
sub CORE::chop  { die "&CORE::chop cannot be called directly" }
```

### Wrapper generation strategy

Rather than hard-coding each wrapper, use the **prototype** from `CORE_PROTOTYPES`
to auto-generate the correct calling convention:

| Prototype pattern | Wrapper template | Examples |
|-------------------|-----------------|----------|
| `""` (empty) | `sub CORE::X { CORE::X() }` | `time`, `fork`, `getlogin`, `wantarray` |
| `"_"` | `sub CORE::X { CORE::X($_[0]) }` | `abs`, `chr`, `hex`, `int`, `lc`, `length`, `ord`, `ref` |
| `";$"` | `sub CORE::X { CORE::X($_[0]) }` | `rand`, `chdir`, `exit`, `sleep`, `srand`, `alarm`, `caller` |
| `"$$"` | `sub CORE::X { CORE::X($_[0], $_[1]) }` | `rename`, `link`, `symlink`, `crypt`, `atan2` |
| `"$$$"` | `sub CORE::X { CORE::X($_[0], $_[1], $_[2]) }` | `setpriority`, `shmctl` |
| `"$@"` | `sub CORE::X { CORE::X($_[0], @_[1..$#_]) }` | `join`, `sprintf`, `pack`, `formline` |
| `"@"` | `sub CORE::X { CORE::X(@_) }` | `die`, `warn`, `kill`, `chmod`, `chown`, `unlink`, `utime` |
| `"\@@"` | `sub CORE::X { CORE::X(@{shift @_}, @_) }` | `push`, `unshift` |
| `";\@"` | `sub CORE::X { CORE::X(@{$_[0]}) }` | `pop`, `shift` |
| `"\@;$$@"` | `sub CORE::X { CORE::X(@{shift @_}, @_) }` | `splice` |
| `"*;$"` | `sub CORE::X { CORE::X($_[0], $_[1]) }` | `binmode` |
| `"*$$"` | `sub CORE::X { CORE::X($_[0], $_[1], $_[2]) }` | `seek`, `fcntl`, `ioctl` |
| `"$$;$"` | `sub CORE::X { CORE::X(@_) }` | `index`, `rindex`, `substr` (read-only) |
| `"$$;$$"` | `sub CORE::X { CORE::X(@_) }` | `substr` (full) |
| `"*;$@"` | `sub CORE::X { CORE::X(@_) }` | `open` |
| `"*$"` | `sub CORE::X { CORE::X($_[0], $_[1]) }` | `connect`, `bind`, `flock` |
| `"**"` | `sub CORE::X { CORE::X($_[0], $_[1]) }` | `accept`, `pipe` |
| Other complex | Generate case-by-case or `sub CORE::X { CORE::X(@_) }` | Various |

### Implementation: Java-side `PerlSubroutine` lambdas (Option A)

Rather than generating Perl source and compiling it, we create `RuntimeCode` objects
directly in Java. Each wrapper is a `PerlSubroutine` lambda that unpacks `@_` and
calls the existing Java operator methods from the `OperatorHandler` table.

**Where:** `src/main/java/org/perlonjava/runtime/CoreSubroutineGenerator.java`

**When:** Called lazily on first `\&CORE::X` reference for a given operator (not at
startup). Each wrapper is generated at most once; subsequent references find the
already-installed CODE ref in `GlobalVariable.globalCodeRefs`.

**Why Java lambdas instead of Perl source:**
- No parser/compiler recursion risk (no eval inside parser)
- Direct calls to existing operator Java methods — same ones in `OperatorHandler`
- Prototype set directly on `RuntimeCode.prototype` field
- Zero compilation overhead — just object construction

**How:**

```java
public class CoreSubroutineGenerator {

    // Set of keywords that have NO subroutine form (Tier 3)
    private static final Set<String> NO_SUB_KEYWORDS = Set.of(
        "__DATA__", "__END__", "and", "cmp", "default", "do", "dump",
        "else", "elsif", "eq", "eval", "for", "foreach", "format",
        "ge", "given", "goto", "grep", "gt", "if", "last", "le",
        "local", "lt", "m", "map", "my", "ne", "next", "no", "or",
        "our", "package", "print", "printf", "q", "qq", "qr", "qw",
        "qx", "redo", "require", "return", "s", "say", "sort",
        "state", "sub", "tr", "unless", "until", "use", "when",
        "while", "x", "xor", "y"
    );

    // Set of functions that can only be called as barewords (Tier 2)
    private static final Set<String> BAREWORD_ONLY = Set.of(
        "chomp", "chop", "defined", "delete", "eof", "exec",
        "exists", "lstat", "split", "stat", "system",
        "truncate", "unlink"
    );

    /**
     * Generate a wrapper for a single CORE:: function on demand.
     * Creates a RuntimeCode with a PerlSubroutine lambda that calls
     * the operator's Java implementation directly.
     */
    public static boolean generateWrapper(String operatorName) {
        if (NO_SUB_KEYWORDS.contains(operatorName)) {
            return false;
        }

        String fullName = "CORE::" + operatorName;
        if (GlobalVariable.isGlobalCodeRefDefined(fullName)) {
            return true;  // already generated
        }

        String prototype = ParserTables.CORE_PROTOTYPES.get(operatorName);
        if (prototype == null) {
            return false;
        }

        // Tier 2: create stub that dies when called through reference
        if (BAREWORD_ONLY.contains(operatorName)) {
            return generateBarewordOnlyWrapper(operatorName, prototype);
        }

        PerlSubroutine sub = buildSubroutine(operatorName, prototype);
        if (sub == null) return false;

        RuntimeCode code = new RuntimeCode(sub, prototype);
        code.packageName = "CORE";
        code.subName = operatorName;
        GlobalVariable.getGlobalCodeRef(fullName)
            .set(new RuntimeScalar(code));
        return true;
    }

    /**
     * Build a PerlSubroutine lambda based on the operator's prototype.
     * Dispatches on prototype pattern to create the right arg-unpacking.
     */
    private static PerlSubroutine buildSubroutine(String name, String proto) {
        return switch (proto) {
            // Zero-arg: time, fork, wantarray, getlogin, ...
            case "" -> (args, ctx) -> callOperator(name, ctx);

            // Unary with $_ default: abs, chr, hex, int, length, ...
            case "_" -> (args, ctx) -> {
                RuntimeScalar arg = args.size() > 0
                    ? args.get(0) : getDefaultScalar();
                return callOperator(name, arg, ctx);
            };

            // Optional scalar: rand, chdir, exit, sleep, caller, ...
            case ";$" -> (args, ctx) -> {
                RuntimeScalar arg = args.size() > 0
                    ? args.get(0) : new RuntimeScalar();
                return callOperator(name, arg, ctx);
            };

            // Two required scalars: rename, link, crypt, atan2, ...
            case "$$" -> (args, ctx) ->
                callOperator(name, args.get(0), args.get(1), ctx);

            // Flat list: die, warn, chmod, chown, kill, ...
            case "@" -> (args, ctx) ->
                callOperatorList(name, args, ctx);

            // ... more patterns ...

            default -> null;  // unsupported prototype
        };
    }
}
```

The `callOperator()` methods use the `OperatorHandler` table to dispatch
to the correct Java static method (e.g., `StringOperators.length()`,
`MathOperators.abs()`, `Directory.chdir()`).

### Parser changes

In `ParsePrimary.java`, replace the TODO/error at line 342-352:

```java
// Current:
throw new PerlCompilerException(
    "Not implemented: take reference of operator `\\&" + identifierNode.name + "`");

// New:
String opName = identifierNode.name.substring("CORE::".length());
if (!CoreSubroutineGenerator.generateWrapper(opName)) {
    throw new PerlCompilerException(
        "Can't take reference of CORE::" + opName);
}
// Fall through — the normal \&name path will find the installed sub
```

## Phases

### Phase 1: Core infrastructure + simple builtins (MVP)

**Goal:** `\&CORE::length`, `\&CORE::abs`, `\&CORE::hex`, etc. work — unary functions
with `_` or `;$` prototypes.

1. Create `CoreSubroutineGenerator.java` with:
   - `NO_SUB_KEYWORDS` and `BAREWORD_ONLY` sets
   - `generateWrapper(String operatorName)` method
   - Prototype-to-template mapping for simple prototypes (`""`, `"_"`, `";$"`, `"$$"`)
2. Hook into `ParsePrimary.java` to call generator instead of throwing error
3. Add unit tests for basic patterns

**Test cases:**
```perl
# Take reference and call through it
my $r = \&CORE::length;
print $r->("hello"), "\n";  # 5

# Alias via glob
BEGIN { *my_abs = \&CORE::abs; }
print my_abs(-42), "\n";  # 42

# Pass as callback
my @sorted = sort { &CORE::length($a) <=> &CORE::length($b) } @words;

# Undefined/defined checks
print defined(&CORE::length) ? "yes" : "no";  # yes (already works)
```

### Phase 2: List operators and array-ref prototypes

**Goal:** `\&CORE::push`, `\&CORE::pop`, `\&CORE::splice`, etc.

1. Add prototype templates for `\@@`, `;\@`, `\@;$$@`, `@`, `$@`
2. Handle the array-ref dereferencing (`@{shift @_}` pattern)
3. Add tests for list operations through references

**Test cases:**
```perl
BEGIN { *shove = \&CORE::push; }
my @a;
shove @a, 1, 2, 3;
print "@a\n";  # 1 2 3

my $popper = \&CORE::pop;
$popper->(\@a);
print "@a\n";  # 1 2
```

### Phase 3: I/O, complex prototypes, and Tier 2

**Goal:** `\&CORE::open`, `\&CORE::close`, `\&CORE::binmode`, `\&CORE::read`, etc.
Also implement Tier 2 (bareword-only) functions that return a ref with the correct
prototype but die with `"&CORE::X cannot be called directly"` when called through
a reference.

1. Add templates for `*;$`, `*$$`, `*;$@`, `*\$$;$` and other filehandle prototypes
2. Implement Tier 2 wrappers: carry prototype, die on `$ref->()` call
3. Handle edge cases: functions with context-dependent return values

### Phase 4: CORE::GLOBAL:: integration

**Goal:** When a builtin is overridden via `CORE::GLOBAL::`, `\&CORE::func` still
returns the original builtin (not the override).

This should work naturally since the generated wrappers use `CORE::func()` internally,
which bypasses overrides.

## Excluded Functions

These will NOT get callable `\&CORE::` support (matching Perl 5):

### Tier 3: `defined(&CORE::X)` returns false (from `coresubs.t` `%unsupported`)

These keywords have no real subroutine form. `\&CORE::X` returns a CODE ref in
Perl 5.42 but `defined()` returns false and calling dies with "Undefined subroutine".
For initial implementation, `\&CORE::X` can throw an error for these.

```
__DATA__ __END__ ADJUST AUTOLOAD BEGIN UNITCHECK CORE DESTROY END INIT CHECK
all and any catch class cmp default defer do dump else elsif eq eval field
finally for foreach format ge given goto grep gt if isa last le local
lt m map method my ne next no or our package print printf q qq qr qw qx
redo require return s say sort state sub tr try unless until use
when while x xor y
```

### Tier 2: `\&CORE::X` works but `$ref->()` dies with "cannot be called directly"

These get a CODE ref with the correct prototype. Glob aliases like
`*my_chomp = \&CORE::chomp; my_chomp($x)` work via compile-time op inlining
(verified via `B::Deparse`). But `$ref->($x)` and `&$ref($x)` die.

```
chomp chop defined delete eof exec exists lstat split stat system truncate unlink
```

The generated wrapper for Tier 2 should carry the correct prototype but die
with `"&CORE::X cannot be called directly"` when invoked through a reference.

## Testing Strategy

### Existing Perl 5 test files

Two comprehensive test files already exist in the tree:

- **`perl5_t/t/op/coresubs.t`** — Tests prototype preservation and compile-time
  inlining. For each supported keyword:
  1. `ok defined &{"CORE::$word"}` — subroutine exists
  2. `*{"my$word"} = \&{"CORE::$word"}` — alias via glob
  3. `is prototype \&{"my$word"}, $proto` — prototype preserved through alias
  4. Inlining test: `op_list(CORE::word(...))` equals `op_list(myword(...))`
  5. Precedence test: `myword $a > $b` has same parse as `CORE::word $a > $b`
  - **Blocker:** Requires `B` module (not available in PerlOnJava)

- **`perl5_t/t/op/coreamp.t`** — Tests calling `&CORE::func(args)` directly:
  1. Argument count enforcement (too many / too few based on prototype)
  2. Type checking for reference prototypes (`\@`, `\%`, `\[$@%&*]`)
  3. Actual return values for each builtin via `&CORE::func(...)`
  - **Blocker:** Uses `CORE::given` (not implemented)

### Test plan

1. **New unit tests:** `src/test/resources/core_subroutine_refs.t` — basic tests
   that don't require `B` module:
   - `\&CORE::length`, `\&CORE::abs`, `\&CORE::hex` return CODE refs
   - `$ref->()` works for Tier 1
   - Prototype preserved: `prototype(\&CORE::push)` eq `prototype("CORE::push")`
   - Glob alias: `*my_push = \&CORE::push; my_push @a, 1, 2, 3`
   - Tier 2: `\&CORE::chomp` returns ref, `$ref->()` dies correctly
   - Tier 3: `\&CORE::print` errors or returns undef-defined ref

2. **Progressive enablement of coresubs.t / coreamp.t** as blockers are resolved

3. **Module compatibility:** Verify `Tie::StdHandle` patterns work

4. **Regression:** Run full `make` to ensure no breakage

## Related Files

- `dev/design/symbol_table_manipulation.md` — glob/symbol table design
- `dev/design/functional_subroutines.md` — subroutine interface design
- `dev/design/operator_factory.md` — operator dispatch patterns

## Progress Tracking

### Current Status: Phase 1-3 implemented (2026-03-30)

### Completed
- [x] Design document (2025-03-30)
- [x] Phase 1: Core infrastructure + simple builtins (2026-03-30)
  - Created `CoreSubroutineGenerator.java` with lazy wrapper generation
  - Removed parser throw in `ParsePrimary.java`, let `\&CORE::X` flow through
  - Added lazy CORE:: generation hook in `RuntimeCode.createCodeReference()`
  - Supported prototype patterns: `""`, `"_"`, `";$"`, `"$$"`, `"$$$"`, `"@"`, `"$@"`, `"_;$"`
- [x] Phase 2: List operators and array-ref prototypes (2026-03-30)
  - Implemented `\@@` (push, unshift, splice) with array-ref dereferencing
  - Implemented `;\@` (pop, shift)
  - All pass tests
- [x] Phase 3: I/O, complex prototypes, and Tier 2 (2026-03-30)
  - I/O operators dispatched through varargs pattern
  - Tier 2 (bareword-only) wrappers: carry prototype, die with correct error
  - Generic varargs fallback handles remaining complex prototypes

### Files Changed
- **Created:** `src/main/java/org/perlonjava/runtime/CoreSubroutineGenerator.java`
- **Created:** `src/test/resources/unit/core_subroutine_refs.t` (29 tests)
- **Modified:** `src/main/java/org/perlonjava/frontend/parser/ParsePrimary.java` (removed throw)
- **Modified:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` (lazy generation hook)

### Architecture Notes
- **Approach:** Java PerlSubroutine lambdas (Option A) — no Perl source generation
- **Hook point:** `RuntimeCode.createCodeReference()` checks for CORE:: prefix on undefined code refs
- **Key insight:** `getGlobalCodeRef()` creates empty CODE-type scalars; must check `RuntimeCode.defined()` not just scalar type
- **Lazy generation:** Wrappers are created on first `\&CORE::X` reference, cached in global code refs

### Known Limitations
- `chroot` not implemented (no Java method exists)
- `evalbytes` not implemented
- Context propagation for some functions (e.g., `reverse` in scalar vs list context through ref)
- `\&CORE::X` for Tier 3 keywords (print, eval, etc.) returns a valid-looking ref but calling fails

### Next Steps
1. Phase 4: CORE::GLOBAL:: integration (verify overrides don't affect wrappers)
2. Add more operator mappings to callUnary/callVarargs as needed
3. Progressive enablement of `perl5_t/t/op/coresubs.t` and `perl5_t/t/op/coreamp.t`
4. Test module compatibility (Tie::StdHandle patterns)
