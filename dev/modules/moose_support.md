# Moose Support for PerlOnJava

## Overview

This document outlines the path to supporting Moose (and Class::MOP) on PerlOnJava. Moose is Perl's most popular object system, providing a rich meta-object protocol (MOP) for defining classes, attributes, roles, and more.

## Current Status: Not Feasible (Requires Phase 1)

### Blockers

| Blocker | Severity | Description |
|---------|----------|-------------|
| Subroutine name introspection | **Critical** | `B::CV->GV->NAME` returns `__ANON__` for named subs |
| Makefile.PL compiler check | Medium | `ExtUtils::HasCompiler` dies without compiler |
| Class::MOP XS functions | Medium | No pure Perl fallbacks provided |
| MAGIC-based export tracking | Low | XS uses sv_magic for export flags |

### What Already Works

| Component | Status | Notes |
|-----------|--------|-------|
| Moo | **96% tests pass** | Recommended alternative |
| Params::Util | Works | Requires `PERL_PARAMS_UTIL_PP=1` |
| Package::Stash | Works | Uses PP fallback automatically |
| Class::Load | Works | Uses PP fallback automatically |
| Data::OptList | Works | With Params::Util PP mode |
| Sub::Install | Mostly works | Some test failures |

---

## Root Cause Analysis

### The Subroutine Name Problem

When Perl compiles `sub foo { ... }`, it stores metadata about the subroutine:
- Package name (stash)
- Subroutine name
- File and line number

This metadata is accessible via the B (Perl internals) module:

```perl
sub foo { 1 }
use B;
my $cv = B::svref_2object(\&foo);
print $cv->GV->NAME;    # Should print "foo"
print $cv->GV->STASH->NAME;  # Should print "main"
```

**Current PerlOnJava behavior:**
```
Name: __ANON__
Stash: main
```

**Expected behavior:**
```
Name: foo
Stash: main
```

### Why This Matters for Moose

Moose uses `Class::MOP::get_code_info($coderef)` extensively:

1. **Method tracking**: Determining if a method belongs to a class or was imported
2. **Role application**: Checking method origins during role composition
3. **Export management**: Tracking which subs were exported vs. defined locally
4. **Metaclass operations**: Building method maps, checking overrides

Without accurate subroutine name introspection, Moose cannot function correctly.

---

## Implementation Plan

### Phase 1: Fix B Module Subroutine Name Introspection (Critical)

**Goal**: Make `B::CV->GV->NAME` return the actual subroutine name.

**Analysis Required**:

1. **Where subroutine names are stored**:
   - Check `RuntimeCode.java` - does it store the subroutine name?
   - Check `EmitterMethodCreator.java` - is the name passed during creation?
   - Check symbol table operations in `GlobalVariable.java`

2. **Where B module queries names**:
   - `src/main/java/org/perlonjava/perlmodule/B.java`
   - Methods: `BCV`, `BGV`, `BSTASH`

**Likely Fix**:

The `RuntimeCode` class needs to store the subroutine name when created:

```java
// RuntimeCode.java
public class RuntimeCode {
    private String subName;      // Add this field
    private String packageName;  // Add this field
    
    public String getSubName() {
        return subName != null ? subName : "__ANON__";
    }
    
    public String getPackageName() {
        return packageName != null ? packageName : "main";
    }
}
```

Then update the B module to query these:

```java
// B.java - in the GV NAME accessor
public static RuntimeScalar getName(RuntimeScalar self) {
    RuntimeCode code = (RuntimeCode) self.value;
    return new RuntimeScalar(code.getSubName());
}
```

**Files to investigate**:
- `src/main/java/org/perlonjava/runtime/RuntimeCode.java`
- `src/main/java/org/perlonjava/perlmodule/B.java`
- `src/main/java/org/perlonjava/codegen/EmitterMethodCreator.java`
- `src/main/java/org/perlonjava/runtime/GlobalVariable.java`

**Test**:
```perl
sub named_sub { 42 }
use B;
my $cv = B::svref_2object(\&named_sub);
print $cv->GV->NAME eq 'named_sub' ? "ok" : "not ok";
print $cv->GV->STASH->NAME eq 'main' ? "ok" : "not ok";
```

---

### Phase 2: Bypass Makefile.PL Compiler Check

**Goal**: Allow Moose to install despite lacking a C compiler.

**Options**:

#### Option A: Patch ExtUtils::HasCompiler (Recommended)

Create a PerlOnJava-specific version that returns false gracefully:

```perl
# src/main/perl/lib/ExtUtils/HasCompiler.pm
package ExtUtils::HasCompiler;
use strict;
use warnings;

sub can_compile_loadable_object {
    # PerlOnJava cannot compile XS, but modules may have PP fallbacks
    return 0;
}

# ... rest of API for compatibility
```

#### Option B: Environment Variable

Set `PERLONJAVA_SKIP_XS_CHECK=1` and patch Moose's Makefile.PL detection.

#### Option C: jcpan Patching

Have jcpan automatically patch known modules during installation.

**Files to create/modify**:
- `src/main/perl/lib/ExtUtils/HasCompiler.pm`

---

### Phase 3: Implement Class::MOP Java XS Functions

**Goal**: Provide Java implementations for critical MOP functions.

**Functions to implement**:

| Function | Purpose | Complexity |
|----------|---------|------------|
| `get_code_info` | Get package/name from coderef | Easy (after Phase 1) |
| `INSTALL_SIMPLE_READER` | Create hash accessor methods | Medium |
| `is_stub` | Check if method is a stub | Easy |
| `_flag_as_reexport` | Mark glob as re-export | Hard (needs MAGIC) |
| `_export_is_flagged` | Check re-export flag | Hard (needs MAGIC) |

**Implementation approach**:

Create `src/main/java/org/perlonjava/perlmodule/ClassMOP.java`:

```java
package org.perlonjava.perlmodule;

public class ClassMOP extends PerlModuleBase {
    
    public ClassMOP() {
        super("Class::MOP", false);
    }
    
    public static void initialize() {
        ClassMOP module = new ClassMOP();
        try {
            module.registerMethod("get_code_info", null);
        } catch (NoSuchMethodException e) {
            // handle
        }
    }
    
    /**
     * get_code_info($coderef)
     * Returns (package_name, sub_name) for a code reference.
     */
    public static RuntimeList get_code_info(RuntimeArray args, int ctx) {
        RuntimeScalar coderef = args.get(0);
        if (coderef.type != RuntimeScalarType.CODE) {
            return new RuntimeList();
        }
        
        RuntimeCode code = (RuntimeCode) coderef.value;
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(code.getPackageName()));
        result.add(new RuntimeScalar(code.getSubName()));
        return result;
    }
}
```

**Files to create**:
- `src/main/java/org/perlonjava/perlmodule/ClassMOP.java`

---

### Phase 4: Handle Export Flag Magic (Optional)

**Goal**: Implement MAGIC-based export tracking for Moose::Exporter.

This is lower priority because:
1. It only affects re-export detection
2. Moose may work without it (with some export warnings)

**If needed**, implement a simplified version using a WeakHashMap in Java to track flagged globs.

---

### Phase 5: Testing and Validation

**Test progression**:

1. **Unit tests for B module fixes**:
   ```bash
   ./jperl -e 'sub foo{} use B; print B::svref_2object(\&foo)->GV->NAME'
   ```

2. **Sub::Identify tests**:
   ```bash
   PERL_PARAMS_UTIL_PP=1 ./jcpan -t Sub::Identify
   ```

3. **Class::Load with Moose dependencies**:
   ```bash
   PERL_PARAMS_UTIL_PP=1 ./jperl -e 'use Class::Load qw(load_class); load_class("Moose"); print "ok\n"'
   ```

4. **Basic Moose functionality**:
   ```perl
   use Moose;
   
   has 'name' => (is => 'ro', isa => 'Str');
   
   my $obj = __PACKAGE__->new(name => 'test');
   print $obj->name;
   ```

5. **Full Moose test suite**:
   ```bash
   ./jcpan -t Moose
   ```

---

## Alternative: Use Moo

If Moose support proves too complex, **Moo is already working** and provides most functionality:

```perl
package MyClass;
use Moo;

has name => (is => 'ro', isa => sub { die unless defined $_[0] });
has age  => (is => 'rw', default => sub { 0 });

sub greet {
    my $self = shift;
    return "Hello, " . $self->name;
}

1;
```

**Moo features that work**:
- Attributes with `is`, `isa`, `default`, `trigger`, `coerce`
- Inheritance with `extends`
- Roles with `Role::Tiny` / `Moo::Role`
- Method modifiers (`before`, `after`, `around`)
- BUILD and BUILDARGS

**Moo limitations on PerlOnJava** (expected):
- Weak references don't behave like native Perl
- DEMOLISH timing differs (JVM GC)
- Some namespace cleanup edge cases

---

## Dependencies Graph

```
Moose
├── Class::MOP (XS - needs Java impl)
│   └── MRO::Compat (works)
├── Class::Load (works with PP)
│   ├── Data::OptList (works)
│   │   ├── Params::Util (needs PERL_PARAMS_UTIL_PP=1)
│   │   └── Sub::Install (mostly works)
│   └── Package::Stash (works with PP)
├── Devel::GlobalDestruction (works)
├── Devel::OverloadInfo (needs investigation)
├── Devel::StackTrace (works)
├── Dist::CheckConflicts (works)
├── Eval::Closure (needs investigation)
├── List::Util (built-in)
├── Module::Runtime (works)
├── Package::DeprecationManager (needs investigation)
├── Params::Util (needs PP flag)
├── Scalar::Util (built-in)
├── Sub::Exporter (needs investigation)
└── Try::Tiny (works)
```

---

## Environment Variables for PP Mode

Until Java XS is implemented, use these environment variables:

```bash
export PERL_PARAMS_UTIL_PP=1
# Future: export PERL_CLASS_MOP_PP=1
```

Or create a wrapper script:

```bash
#!/bin/bash
# jperl-moose - Run jperl with Moose-compatible settings
export PERL_PARAMS_UTIL_PP=1
exec jperl "$@"
```

---

## Progress Tracking

### Current Status: Phase 0 - Investigation Complete

### Completed
- [x] Investigation of Moose requirements (2025-03-27)
- [x] Identified root cause: B module subroutine names
- [x] Verified Moo works as alternative (96% tests pass)
- [x] Documented dependency tree and status

### Next Steps
1. [ ] Phase 1: Fix B module subroutine name introspection
2. [ ] Phase 2: Create ExtUtils::HasCompiler stub
3. [ ] Phase 3: Implement Class::MOP Java XS
4. [ ] Phase 4: Handle export flag magic (if needed)
5. [ ] Phase 5: Full Moose test suite

### Open Questions
- Should we prioritize Moose or focus on Moo compatibility?
- Is there demand for full Moose metaclass introspection?
- Can we implement a "Moose-lite" that covers common use cases?

---

## Related Documents

- [xs_fallback.md](xs_fallback.md) - XS fallback mechanism
- [makemaker_perlonjava.md](makemaker_perlonjava.md) - MakeMaker implementation
- [cpan_client.md](cpan_client.md) - CPAN client support
- `.agents/skills/port-cpan-module/` - Module porting skill

---

## References

- [Moose Manual](https://metacpan.org/pod/Moose::Manual)
- [Class::MOP](https://metacpan.org/pod/Class::MOP)
- [Moo](https://metacpan.org/pod/Moo) - Minimalist Object Orientation
- [B module](https://perldoc.perl.org/B) - Perl compiler backend
