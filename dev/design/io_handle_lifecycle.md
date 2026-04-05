# IO Handle Lifecycle & Fileno Registry

## Overview

This document explains how PerlOnJava manages the lifecycle of IO handles
(file descriptors), the fileno registry, and the tradeoffs made due to the
absence of Perl 5's reference counting and DESTROY mechanism.

## Background: How Perl 5 Does It

In Perl 5, IO handles are closed automatically when the **last reference**
to the containing glob is dropped.  The interpreter uses reference counting
(`SvREFCNT`) to track this.  When the refcount reaches zero, `sv_clear()`
calls `gp_free()` which closes the IO.  The `DESTROY` method can also
participate in cleanup.

PerlOnJava has **none of these mechanisms**:
- No reference counting
- No DESTROY (object destructors never run)
- JVM garbage collection is non-deterministic

This creates a fundamental tension: we want to close file handles promptly
(to avoid fd leaks and flush data), but we can't safely determine when a
handle is truly unreferenced.

## The Fileno Registry

### Purpose

PerlOnJava's "virtual fd" registry maps small sequential integers to
`RuntimeIO` objects.  This is needed for:

1. **dup-by-fd operations**: `open(*FH, ">&3")` — looks up fd 3 in the registry
2. **4-arg select()**: Uses fd numbers in bit-vectors
3. **fileno() return values**: Perl code expects `fileno($fh)` to return an integer

### Design

```
 stdin  → fd 0 (native, via StandardIO)
 stdout → fd 1 (native, via StandardIO)
 stderr → fd 2 (native, via StandardIO)
 files  → fd 3, 4, 5, ... (from AtomicInteger counter, via registry)
```

- `filenoToIO`: fd → RuntimeIO  (lookup by fd number)
- `ioToFileno`: RuntimeIO → fd  (lookup by handle)
- `nextFileno`: AtomicInteger, starts at 3, only increments

### Fd Numbers Are Never Recycled

A recycling mechanism (using `ConcurrentLinkedQueue<Integer> freedFilenos`)
was attempted and removed.  The problem:

1. `closeIOOnDrop()` closes a RuntimeIO and puts its fd into the free queue
2. The next `assignFileno()` picks up the recycled fd
3. But the closed RuntimeIO was still referenced by another variable
4. Now two RuntimeIO objects claim the same fd → registry corruption

Without reference counting, we cannot know when a fd is truly free to reuse.
The monotonic counter wastes fd numbers but is safe.

### Lazy vs Eager Fileno Assignment

- **Eager** (dup'd handles): `duplicateFileHandle()` calls `assignFileno()`
  immediately.  Dup'd handles need a fd right away because Capture::Tiny
  and similar code do `open(*STDOUT, ">&" . fileno($saved))`.

- **Lazy** (regular files, pipes): `assignFileno()` is deferred until
  `fileno()` is first called.  Many handles (especially temp files in
  Capture::Tiny) never have `fileno()` called on them, so this avoids
  wasting fd numbers.

## closeIOOnDrop: The Eager-Close Heuristic

### What It Does

When a `RuntimeScalar` holding a `GLOBREFERENCE` is being cleared,
`closeIOOnDrop()` checks if the glob's IO should be closed:

1. If the glob is still in a stash (named, like `*main::MYFILE`) → skip
2. If the glob is anonymous (gensym'd, deleted from stash) → close the IO

### Where It's Called

| Call site | Safe? | Notes |
|-----------|-------|-------|
| `undefine()` | Yes | Explicit `undef $fh` — user is intentionally discarding |
| `setLarge(null)` | Yes | Variable set to undef/null |
| `setLarge(value)` | **REMOVED** | Variable reassignment — other refs may still exist |
| `set(int/long/...)` | Never called | Primitive setters don't check IO at all |

### The Capture::Tiny Bug (Why setLarge Was Changed)

Capture::Tiny's `_copy_std()` saves STDOUT/STDERR handles in a loop:

```perl
sub _copy_std {
    my %handles;
    for my $h ( qw/stdout stderr/ ) {
        my $redir = ">&";
        _open $handles{$h} = IO::Handle->new(), $redir . uc($h);
    }
    return \%handles;
}
```

After macro expansion, this is roughly:

```perl
my $h;  # gensym'd by IO::Handle->new()

# Iteration 1: save STDOUT
$h = IO::Handle->new();     # $h → GLOB(GEN1)
open($h, ">&STDOUT");       # GEN1's IO = dup of STDOUT (fd 3)
$handles{stdout} = $h;      # hash also → GLOB(GEN1)

# Iteration 2: save STDERR
$h = IO::Handle->new();     # ← THIS TRIGGERS setLarge() on $h
                             #   old value: GLOBREFERENCE to GEN1
                             #   closeIOOnDrop() closes GEN1's IO!
                             #   $handles{stdout} is now BROKEN
open($h, ">&STDERR");       # GEN2's IO = dup of STDERR
$handles{stderr} = $h;
```

The failure chain:

1. `$h = IO::Handle->new()` calls `$h.set(new_value)`
2. `setLarge()` sees old value is GLOBREFERENCE to GEN1 (gensym'd, not in stash)
3. `closeIOOnDrop()` closes GEN1's RuntimeIO → `ioHandle = ClosedIOHandle`
4. With fd recycling, fd 3 goes to the free queue → next dup gets fd 3 too!
5. `$handles{stdout}` still points to GEN1 → `fileno()` returns undef
6. Later `open(*STDOUT, ">&" . fileno($handles{stdout}))` → `">&"` → "Bad fd"

### The Fix

1. **Removed `closeIOOnDrop()` from `setLarge()` assignment path** — we
   cannot safely determine if other variables reference the same glob.

2. **Removed fd recycling** — without reference counting, recycled fds
   can collide with still-referenced handles.

### Tradeoffs

| Aspect | Before | After |
|--------|--------|-------|
| Capture::Tiny | Broken (Bad fd on restore) | Works correctly |
| Explicit `close($fh)` | Works | Works (unchanged) |
| `undef $fh` | Closes IO | Closes IO (unchanged) |
| Reassigning `$fh = other` | Closes old IO (dangerous) | Old IO leaks until GC |
| Fd numbers | Recycled (unsafe) | Monotonic (safe, wastes numbers) |

### Future Improvements

If IO handle leaks become a practical problem, consider:

1. **Reference counting on RuntimeGlob**: Track how many RuntimeScalars
   point to each glob.  Only close IO when count reaches zero.
   - Pro: Correct, matches Perl 5 semantics
   - Con: Adds overhead to every set() call involving GLOBREFERENCEs

2. **Java Cleaner API**: Register a cleanup action with `java.lang.ref.Cleaner`
   when a RuntimeGlob is created.  When the glob becomes phantom-reachable,
   the cleaner closes the IO.
   - Pro: No overhead on set(); leverages JVM GC
   - Con: Non-deterministic timing; data may not be flushed promptly

3. **WeakReference-based fd tracking**: Use WeakReferences to track
   RuntimeIO handles.  Periodically scan for collected references and
   reclaim their fds.
   - Pro: Safe fd recycling
   - Con: Adds a background task; still non-deterministic

## Files Involved

- `RuntimeScalar.java` — `setLarge()`, `undefine()`, `closeIOOnDrop()`
- `RuntimeIO.java` — fileno registry, `assignFileno()`, `unregisterFileno()`, `fileno()`, `close()`
- `IOOperator.java` — `duplicateFileHandle()`, `openFileHandleDup()`
- `RuntimeGlob.java` — `setIO()`, glob name and stash management
