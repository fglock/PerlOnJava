# jcpan DateTime Fix Plan

## Overview

This document tracks all errors and warnings that occur when running `jcpan install DateTime` with a clean cache, and the plan to fix them.

## Error Categories

### 1. CRITICAL: DateTime::Locale Installation Fails

**Root Cause**: `File::stat.pm` is missing

```
Can't locate File/stat.pm in @INC at /Users/fglock/.perlonjava/lib/File/ShareDir/Install.pm line 11
```

**Impact**: DateTime::Locale cannot be configured, which means DateTime tests fail with:
```
Can't locate DateTime/Locale.pm in @INC
```

**Solution**: Implement `File::stat.pm` - a stub or full implementation

**Priority**: HIGH

---

### 2. IPC::Open3 Read-Only Modification Error

**Error**:
```
open3: Modification of a read-only value attempted
         at IPCOpen3.java line 162
```

**Impact**: Many module tests fail in `t/00-compile.t` type tests that use open3 to test module compilation

**Solution**: Fix IPCOpen3.java line 162 to handle read-only values

**Priority**: MEDIUM

---

### 3. Missing Core Modules

| Module | Used By | Priority |
|--------|---------|----------|
| File::stat | File::ShareDir::Install | HIGH |
| IO::Select | Various | MEDIUM |
| PerlIO::encoding | Encode tests | LOW |
| encoding.pm | Encode | LOW |

---

### 4. Encode Module Issues

**Errors**:
```
Can't locate object method "decode" via package "ISO-8859-1"
Can't locate object method "encode" via package "UTF-8"
Can't locate object method "encodings" via package "Encode"
Undefined subroutine &Encode::define_encoding called
```

**Root Cause**: Encode is an XS module; PerlOnJava has a Java implementation but some methods are missing

**Solution**: Add missing methods to Encode.java:
- `encodings()`
- Ensure `decode`/`encode` work with encoding names as package names

**Priority**: MEDIUM

---

### 5. Version Format Errors

**Error**:
```
Invalid version format (version required) at Version.java line 334
```

**Solution**: Handle edge cases in Version.java

**Priority**: LOW

---

### 6. CPAN::Meta::Requirements Warning

**Error**:
```
Use of uninitialized value in numeric gt (>) at jar:PERL5LIB/CPAN/Meta/Requirements.pm line 215
```

**Solution**: Check for undef before numeric comparison

**Priority**: LOW

---

### 7. Test::Builder Overload Issue

**Error**:
```
Undefined subroutine &*version::("" called at jar:PERL5LIB/Test/Builder.pm line 771
```

**Solution**: Fix overload handling for version objects

**Priority**: LOW

---

### 8. Exporter require_version Missing

**Error**:
```
Can't locate object method "require_version" via package "Testing"
```

**Solution**: Implement `require_version` in Exporter or UNIVERSAL

**Priority**: MEDIUM

---

### 9. Too Many Arguments for like()

**Error**:
```
Too many arguments for main::like at t/conflicts.t line 109
```

**Root Cause**: Test::More's `like()` has different prototype handling

**Priority**: LOW (cosmetic test failure)

---

### 10. Carp.pm Bareword Error

**Error**:
```
Bareword "Exporter" not allowed while "strict subs" in use at jar:PERL5LIB/Carp.pm line 224
```

**Root Cause**: Edge case in Carp.pm loading when strict subs is enabled

**Priority**: LOW

---

## Implementation Plan

### Phase 1: Critical (enables DateTime to install)

1. **Implement File::stat.pm stub**
   - Create `src/main/perl/lib/File/stat.pm`
   - Implement basic stat() wrapper returning object with standard fields
   - File: `src/main/perl/lib/File/stat.pm`

### Phase 2: High Priority (reduces test failures)

2. **Fix IPC::Open3 read-only error**
   - File: `src/main/java/org/perlonjava/runtime/perlmodule/IPCOpen3.java`
   - Line 162: clone value before modification

3. **Add Encode::encodings() method**
   - File: `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java`

4. **Implement require_version in UNIVERSAL**
   - File: `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java`

### Phase 3: Medium Priority

5. **Fix Version.java edge cases**
6. **Fix CPAN::Meta::Requirements undef check**
7. **Implement IO::Select stub**

### Phase 4: Low Priority (polish)

8. **Fix Test::Builder overload handling**
9. **Fix Carp.pm bareword issue**
10. **Fix like() prototype handling**

---

## Progress Tracking

### Completed
- [x] Phase 16: utf8::valid() fix for CPAN::Meta parsing (2026-03-20)
- [x] ExtUtils::MakeMaker MYMETA.yml meta-spec v2 format (2026-03-20)

### In Progress
- [ ] Phase 17: File::stat.pm implementation

### Pending
- [ ] IPC::Open3 read-only fix
- [ ] Encode::encodings() method
- [ ] require_version implementation

---

## Related Documents

- `dev/design/cpan_client.md` - Main CPAN client documentation
- `dev/design/xsloader.md` - XSLoader implementation
