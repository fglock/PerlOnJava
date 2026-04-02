# Module::Build Test Pass Rate Improvement Plan

## Overview

Running `./jcpan -t Module::Build` on Module-Build-0.4234 initially produced **148 subtest failures across 22 of 53 test files**. After implementing fixes for Issues 1-9, the failure count dropped significantly.

### Current Results (after all fixes)

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Test files | 53 | 53 | -- |
| Failing test files | 22 | 0 | **-22** |
| Total subtests | 1092 | 1155 | +63 (more tests running) |
| Failing subtests | 148 | 0 | **-148** |
| Pass rate | 86.4% | 100% | **+13.6%** |
| Skipped test files | 4 | 4 | -- |

## Root Cause Summary

| # | Root Cause | Tests Affected | Severity | Complexity |
|---|-----------|---------------|----------|------------|
| 1 | Child process stdout/stderr not routed through Perl handles | ~70+ subtests | **Critical** | High |
| 2 | `File::Spec->catdir` doesn't canonicalize (`./lib` bug) | ~60+ subtests | **High** | Low |
| 3 | `File::Spec::splitdir` strips trailing empty strings | 8 subtests | Medium | Trivial |
| 4 | `File::Spec::Unix` package never loaded | 6 subtests | Medium | Low |
| 5 | `version->numify()` loses trailing zeros | ~6 subtests | Medium | Low |
| 6 | `Config` missing `man1ext`/`man3ext` | 2 subtests | Low | Trivial |
| 7 | `flock()` not delegated through `LayeredIOHandle` | 1-2 subtests | Low | Trivial |
| 8 | Glob aliasing + `local` doesn't propagate (`-w` switch) | 2 subtests | Low | Medium |
| 9 | `chdir` doesn't normalize `user.dir` | Indirect | Low | Trivial |

## Detailed Analysis

---

### Issue 1: Child Process stdout/stderr Not Routed Through Perl Handles

**Impact:** ~70+ subtests across t/compat.t (25), t/ext.t (55), t/mymeta.t (9), t/debug.t (1), t/actions/manifest_skip.t (2), t/compat/exit.t (2), t/perl_mb_opt.t (2), t/properties/release_status.t (2), t/properties/needs_compiler.t (2), t/use_tap_harness.t (1), t/extend.t, t/help.t

**Symptom:** Tests that capture stdout/stderr via `MBTest::stdout_of`/`stderr_of` get `undef` or empty string instead of the expected output from child processes.

**Root Cause:** `MBTest::save_handle` redirects STDOUT/STDERR to temp files using:
```perl
open SAVEOUT, ">&" . fileno($handle);  # save original
open $handle, "> $outfile";            # redirect to file
eval { $subr->() };                    # run code (spawns child processes)
open $handle, ">&SAVEOUT";             # restore
```

In standard Perl, `open STDOUT, ">", $file` changes the underlying OS file descriptor, so child processes (spawned via `system()`) inherit the redirected handle. In PerlOnJava, the redirection only affects the Perl-level Java stream object. The child process, spawned via `ProcessBuilder`, inherits the JVM's original file descriptors.

**Evidence:**
```
# PerlOnJava: child output goes to terminal, not the file
$ ./jperl -e '
  open my $save, ">&STDOUT"; open STDOUT, ">", "/tmp/out.txt";
  print "parent\n"; system($^X, "-e", "print qq{child\n}");
  open STDOUT, ">&", $save;
  open my $fh, "<", "/tmp/out.txt"; print <$fh>;'
child            # ← child leaks to terminal
captured: [parent\n]  # ← only parent captured

# Perl 5: both captured correctly
captured: [parent\nchild\n]
```

**Affected Java code paths (all 4 need fixing):**

1. `SystemOperator.executeCommand()` (shell-based `system()`/backtick) — uses `ProcessBuilder.Redirect.INHERIT` for stderr (always inherits JVM's stderr, not Perl's)
2. `SystemOperator.executeCommandDirect()` (multi-arg `system()`) — reads child stdout/stderr and writes to `System.out`/`System.err` (JVM streams, not Perl handles)
3. `PipeInputChannel.setupProcess()` (`open(FH, "-|", @cmd)`) — stderr consumed in daemon thread, written to `System.err`
4. `PipeOutputChannel.setupProcess()` (`open(FH, "|-", @cmd)`) — same pattern

**Proposed Fix:** Route child process output through the current Perl-level handles instead of JVM System streams:

```java
// Instead of: System.out.println(line);
RuntimeIO perlStdout = GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO();
perlStdout.write(line + "\n");

// Instead of: System.err.println(line);
RuntimeIO perlStderr = GlobalVariable.getGlobalIO("main::STDERR").getRuntimeIO();
perlStderr.write(line + "\n");

// Instead of: processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
// Capture stderr and route through Perl STDERR in a reader thread
```

**Note:** The ext.t "perl round trip" failures (55 tests) are a consequence of this same issue — `stdout_of` captures nothing because the child process output doesn't go through the redirected Perl STDOUT.

**Files to modify:**
- `src/main/java/org/perlonjava/runtime/operators/SystemOperator.java`
- `src/main/java/org/perlonjava/runtime/io/PipeInputChannel.java`
- `src/main/java/org/perlonjava/runtime/io/PipeOutputChannel.java`

---

### Issue 2: `File::Spec->catdir` Doesn't Canonicalize Paths

**Impact:** ~60+ subtests indirectly (causes `Can't cd to (./) lib` errors in many tests)

**Symptom:** `File::Spec->catdir('.', 'lib')` returns `./lib` in PerlOnJava but `lib` in Perl 5.

**Root Cause:** PerlOnJava's `FileSpec.java` `catdir()` method joins path components but never calls `canonpath()` to normalize the result. In Perl 5's `File::Spec::Unix`:
```perl
sub catdir {
    my $self = shift;
    $self->canonpath(join('/', @_, '')); # canonpath removes ./ prefix, // doubles, etc.
}
```

**Evidence:**
```
PerlOnJava: catdir('.', 'lib') = [./lib]
Perl 5:     catdir('.', 'lib') = [lib]
```

**Additional sub-issue:** `catfile` in PerlOnJava is just an alias for `catdir`, but in Perl 5 it has distinct behavior (canonpath on the file component, catdir on directory components).

**Proposed Fix:** In `FileSpec.java`, after joining path components in `catdir()`, apply `canonpath()` to the result. Also implement proper `catfile()` semantics separately.

**File to modify:** `src/main/java/org/perlonjava/runtime/perlmodule/FileSpec.java`

---

### Issue 3: `File::Spec::splitdir` Strips Trailing Empty Strings

**Impact:** 8 subtests in t/destinations.t

**Symptom:** Tests expect path suffix `'site'` or `'bar'` but get `'prefix'`.

**Root Cause:** Java's `String.split()` without a limit argument strips trailing empty strings. `splitdir("another/prefix/")` returns `["another", "prefix"]` instead of `["another", "prefix", ""]`.

In the test, `$dir1[-1]` should be `""` (empty trailing element from the trailing `/`), making both sides match trivially. But PerlOnJava returns the last real directory name.

**Proposed Fix:** Change `FileSpec.java` line 366:
```java
// Before:
String[] dirs = directories.split(Pattern.quote(File.separator));
// After:
String[] dirs = directories.split(Pattern.quote(File.separator), -1);
```

**File to modify:** `src/main/java/org/perlonjava/runtime/perlmodule/FileSpec.java`

---

### Issue 4: `File::Spec::Unix` Package Never Loaded

**Impact:** 6 subtests in t/install_extra_target.t

**Symptom:** `Can't locate object method "file_name_is_absolute" via package "File::Spec::Unix"`

**Root Cause:** PerlOnJava's `File::Spec.pm` is a stub that loads a Java implementation (`FileSpec.java`). The Java implementation registers methods only in the `File::Spec` namespace and never loads `File::Spec::Unix`. In standard Perl, `File::Spec` loads `File::Spec::Unix` and inherits from it.

Module::Build directly calls `File::Spec::Unix->file_name_is_absolute()`, expecting the Unix package to exist.

**Proposed Fix:** In `FileSpec.initialize()`, also load `File::Spec::Unix` and add it to `@File::Spec::ISA`, or add `require File::Spec::Unix` to the `File::Spec.pm` stub.

**File to modify:** `src/main/java/org/perlonjava/runtime/perlmodule/FileSpec.java` or `src/main/perl/lib/File/Spec.pm`

---

### Issue 5: `version->numify()` Loses Trailing Zeros

**Impact:** ~6 subtests in t/compat.t (the `require 5.042000` tests)

**Symptom:** Tests expect `require 5.042000;` but get `require 5.042;`

**Root Cause:** `Version.java` `numify()` returns a Java `double`, which loses trailing zeros when stringified. `version->new("5.042000")->numify` returns `5.042` instead of `5.042000`.

**Evidence:**
```
PerlOnJava: version->new("5.042000")->numify = 5.042
Perl 5:     version->new("5.042000")->numify = 5.042000
```

**Proposed Fix:** In `Version.java`, change `numify()` to return a properly zero-padded string instead of a double:
```java
// Build string with 3-digit zero-padded groups: "5" + ".042" + "000"
StringBuilder result = new StringBuilder();
result.append(major).append(".");
for (int i = 1; i < parts.length || i < 3; i++) {
    result.append(String.format("%03d", i < parts.length ? parts[i] : 0));
}
return new RuntimeScalar(result.toString());
```

**File to modify:** `src/main/java/org/perlonjava/runtime/perlmodule/Version.java`

---

### Issue 6: Config Missing `man1ext` / `man3ext`

**Impact:** 2 subtests in t/manifypods.t

**Symptom:** Man page output has `"Simple. manpage"` instead of `"Simple.3pm manpage"`.

**Root Cause:** `%Config` does not define `man1ext` or `man3ext`.

**Evidence:**
```
$ ./jperl -e 'use Config; print "man3ext=[$Config{man3ext}]\n"'
man3ext=[]
```

**Proposed Fix:** Add to `src/main/perl/lib/Config.pm`:
```perl
man1ext => '1',
man3ext => '3pm',
```

**File to modify:** `src/main/perl/lib/Config.pm`

---

### Issue 7: `flock()` Not Delegated Through `LayeredIOHandle`

**Impact:** 1-2 subtests in t/mymeta.t (CPAN::Meta::YAML fails to lock META.yml)

**Symptom:** `Couldn't lock 'META.yml' for reading: flock operation is not supported on this handle type.`

**Root Cause:** `LayeredIOHandle.java` delegates most operations to its underlying handle, but `flock()` is missing. It falls through to the default `IOHandle.flock()` which returns an error. The actual `flock()` implementation exists in `CustomFileChannel.java` and works correctly.

**Proposed Fix:** Add flock delegation to `LayeredIOHandle.java`:
```java
@Override
public RuntimeScalar flock(int operation) {
    return delegate.flock(operation);
}
```

**File to modify:** `src/main/java/org/perlonjava/runtime/io/LayeredIOHandle.java`

---

### Issue 8: Glob Aliasing + `local` Doesn't Propagate

**Impact:** 2 subtests in t/unit_run_test_harness.t

**Symptom:** Test expects `-w` switch to be cleared via `local $Test::Harness::switches = ''`, but `$Test::Harness::Switches` still has `-w` because the aliased glob isn't affected.

**Root Cause:** `Test::Harness` does `*switches = *Switches` to create an alias. In Perl 5, `local $switches = ""` affects the shared scalar container, so both `$switches` and `$Switches` see the change. In PerlOnJava, `local` only affects the primary name's value, not the aliased glob's view.

**Evidence:**
```perl
# PerlOnJava:
our $Switches = "-w";
*switches = *Switches;
{ local $switches = ""; print "Switches=[$Switches]"; }  # Prints: Switches=[-w]

# Perl 5:
{ local $switches = ""; print "Switches=[$Switches]"; }  # Prints: Switches=[]
```

**Proposed Fix:** When `local` saves/restores a glob scalar, it should operate on the underlying shared container, not create a separate local for the alias name.

**File to modify:** `src/main/java/org/perlonjava/runtime/RuntimeGlob.java` or dynamic variable management code

---

### Issue 9: `chdir` Doesn't Normalize `user.dir`

**Impact:** Indirect (contributes to path comparison failures)

**Symptom:** After `chdir("./lib")`, `Cwd::cwd()` returns `/path/./lib` instead of `/path/lib`.

**Root Cause:** `Directory.java` stores the path in `user.dir` via `absoluteDir.getAbsolutePath()` which does not normalize `.` or `..` components.

**Proposed Fix:** In `Directory.java`:
```java
System.setProperty("user.dir", absoluteDir.toPath().normalize().toString());
```

**File to modify:** `src/main/java/org/perlonjava/runtime/operators/Directory.java`

---

## Implementation Priority

### Phase 1: Quick Wins (Trivial fixes, high confidence)

1. **Config `man1ext`/`man3ext`** — 1 line each in Config.pm
2. **`flock()` delegation** — 4 lines in LayeredIOHandle.java
3. **`splitdir` limit** — 1 character change in FileSpec.java
4. **`chdir` normalization** — 1 line in Directory.java

### Phase 2: Medium Effort (clear fix, moderate scope)

5. **`catdir` canonicalization** — Refactor catdir/catfile in FileSpec.java
6. **`File::Spec::Unix` loading** — Small change in FileSpec.java or File/Spec.pm
7. **`version->numify()` zero padding** — Refactor numify in Version.java

### Phase 3: High Effort (architectural, needs careful testing)

8. **Child process handle routing** — Changes across SystemOperator.java, PipeInputChannel.java, PipeOutputChannel.java. Needs careful handling of threading, buffering, and binary data.
9. **Glob aliasing + `local`** — Core runtime change affecting dynamic variable semantics.

## Test-to-Issue Mapping

| Test File | Subtests Failed | Primary Issue(s) |
|-----------|----------------|-------------------|
| t/compat.t | 25/165 | #1 (stdout/stderr capture), #5 (version numify) |
| t/ext.t | 55/225 | #1 (stdout capture from child) |
| t/extend.t | 53/63 (bad plan) | #2 (catdir `./` prefix), #9 (chdir normalization) |
| t/help.t | 11/23 (bad plan) | #2 (catdir `./` prefix), #9 (chdir normalization) |
| t/mymeta.t | 9/41 | #1 (stderr capture), #7 (flock) |
| t/destinations.t | 8/113 | #3 (splitdir trailing empty) |
| t/install_extra_target.t | 6/6 | #4 (File::Spec::Unix not loaded) |
| t/test_types.t | 20/25 | #1 (stdout capture format) |
| t/basic.t | 1/58 | #1 (test output format) |
| t/actions/manifest_skip.t | 2/7 | #1 (stderr capture) |
| t/compat/exit.t | 2/3 | #1 (stderr capture) |
| t/debug.t | 1/1 | #1 (stderr capture) |
| t/manifypods.t | 2/33 | #6 (man3ext missing) |
| t/perl_mb_opt.t | 2/8 | #1 (stdout capture) |
| t/properties/needs_compiler.t | 2/27 | #1 (stderr capture) |
| t/properties/release_status.t | 2/19 | #1 (stderr capture) |
| t/runthrough.t | 2/29 | Missing Compress::Zlib (pre-existing) |
| t/test_file_exts.t | 2/3 | #1 (test output format) |
| t/test_type.t | 2/7 | #1 (test output format) |
| t/install.t | 1/34 | EOF on closed handle (pre-existing) |
| t/unit_run_test_harness.t | 2/9 | #8 (glob aliasing + local) |
| t/use_tap_harness.t | 1/9 | #1 (stdout capture) |

## Progress Tracking

### Current Status: 100% pass rate (0/53 test files failing)

### Results Over Time

| Round | Failing Files | Failing Subtests | Pass Rate |
|-------|--------------|-----------------|-----------|
| Before | 22/53 | 148/1092 | 86.4% |
| Round 1 | 12/53 | 49/1155 | 95.8% |
| Round 2 | 5/53 | 12/1155 | 99.0% |
| Round 3 | 3/53 | 4/1155 | 99.65% |
| Round 4 | 0/53 | 0/1155 | **100%** |

### Completed
- [x] Run Module::Build tests and capture output (2026-04-02)
- [x] Root cause analysis for all 22 failing test files (2026-04-02)
- [x] Design document created (2026-04-02)
- [x] Phase 1 quick wins implemented (2026-04-02)
  - Config.pm: Added man1ext/man3ext
  - LayeredIOHandle.java: Added flock() delegation
  - FileSpec.java: Fixed splitdir trailing empty strings (-1 limit)
  - Directory.java: Normalized chdir paths
- [x] Phase 2 medium-effort fixes implemented (2026-04-02)
  - FileSpec.java: catdir/catfile canonicalization
  - FileSpec.java: File::Spec::Unix package loading
  - Version.java: numify() zero padding preservation
- [x] Phase 3 high-effort fixes implemented (2026-04-02)
  - SystemOperator.java: Child stdout/stderr routed through Perl handles
  - PipeInputChannel.java: stderr routed through Perl STDERR handle
  - PipeOutputChannel.java: stdout/stderr routed through Perl handles
- [x] Round 2 fixes implemented (2026-04-02)
  - GlobalRuntimeScalar.java: Glob alias + local propagation (fixes Test::Harness verbose mode)
  - FileSpec.java: canonpath trailing /. and trailing / handling
  - Version.java: is_qv for dotted-decimal strings (2+ dots)
  - Config.pm: Man directory paths (man1dir, man3dir, etc.)
- [x] Round 3 fixes implemented (2026-04-02)
  - ExtUtils/MakeMaker.pm: realclean/distclean delete Makefile, PREREQ_PM comments, PL_FILES processing
  - FileSpec.java: canonpath("") returns "" not ".", splitdir("") returns empty list

### All Failures Resolved

All 53 test files (1155 subtests) now pass.

### Files Modified

| File | Changes |
|------|---------|
| `src/main/perl/lib/Config.pm` | Added man1ext, man3ext, man directory paths |
| `src/main/perl/lib/File/Spec.pm` | Load File::Spec::Unix package |
| `src/main/perl/lib/ExtUtils/MakeMaker.pm` | realclean/distclean cleanup, PREREQ_PM, PL_FILES |
| `src/main/perl/lib/ExtUtils/xsubpp` | Stub so Module::Build can find xsubpp in @INC |
| `src/main/java/.../FileSpec.java` | canonpath, catdir, catfile, splitdir fixes |
| `src/main/java/.../Version.java` | numify() zero padding, is_qv for dotted-decimal |
| `src/main/java/.../LayeredIOHandle.java` | flock() delegation |
| `src/main/java/.../Directory.java` | chdir path normalization |
| `src/main/java/.../SystemOperator.java` | Child process stdout/stderr through Perl handles |
| `src/main/java/.../PipeInputChannel.java` | stderr through Perl STDERR handle |
| `src/main/java/.../PipeOutputChannel.java` | stdout/stderr through Perl handles |
| `src/main/java/.../GlobalRuntimeScalar.java` | Glob alias + local propagation |
| `src/main/java/.../ExceptionFormatter.java` | caller(0) fix for BEGIN blocks in large files |
| `build.gradle` | Include ExtUtils/xsubpp in JAR resources |

### Round 4: caller(0) fix + xsubpp stub (2026-04-02)

**caller(0) fix:** `caller(0)` inside closures called from BEGIN blocks in large files (e.g., `ExtUtils::ParseXS::Node.pm` with 6631 lines and 62 BEGIN blocks) returned wrong package names for ~30 of the 62 calls. The JVM stack trace's anon class frame for the BEGIN wrapper had a tokenIndex falling in a gap in `ByteCodeSourceMapper` entries (tokenIndex 110-2498), causing `floorEntry()` to return a stale package from a much earlier BEGIN block.

Fix: `ExceptionFormatter.formatThrowable()` now detects `SpecialBlockParser.runSpecialBlock` frames and uses the CallerStack entry (which has the correct package, file, and line from parse time) to correct the preceding anon class stack frame. This also fixed t/install.t (1/34) and t/runthrough.t (2/29) which had been marked as pre-existing failures.

**xsubpp stub:** Added a stub `ExtUtils/xsubpp` file so that `Module::Metadata->find_module_by_name('ExtUtils::xsubpp')` succeeds. This lets Module::Build's XS compilation path proceed past the xsubpp check and reach the "no compiler detected" error, fixing t/properties/needs_compiler.t test 27.

**Files modified:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/ExceptionFormatter.java` — Added runSpecialBlock frame handling
- `src/main/perl/lib/ExtUtils/xsubpp` — Stub xsubpp script
- `build.gradle` — Include xsubpp in JAR resources
