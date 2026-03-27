# Source Filters Implementation Design

## Overview

This document describes the design for supporting Perl source filters in PerlOnJava, specifically enabling modules like `Filter::Simple` and `Filter::Util::Call` to work when filters are installed via `use Module qw(:tag)` statements.

## Problem Statement

### Current Limitation

PerlOnJava tokenizes the **entire source file upfront** before any code executes. This creates a fundamental incompatibility with Perl source filters:

```perl
use Log::Log4perl qw(:resurrect);  # Installs filter in import()

###l4p DEBUG "hidden logging";  # Should be transformed to: DEBUG "hidden logging";
print "hello\n";
```

**What happens in PerlOnJava:**
1. Lexer tokenizes all source (including `###l4p` comments - unchanged)
2. Parser builds AST
3. `use Log::Log4perl` executes, calls `import(':resurrect')`
4. `import()` installs the filter via `Filter::Util::Call::filter_add()`
5. **Problem**: Source is already tokenized - filter has nothing to filter!

**What happens in standard Perl:**
1. Perl reads source incrementally (line by line or block by block)
2. `use Log::Log4perl` is parsed and executed immediately
3. `import()` installs the filter
4. Perl continues reading source **through the filter**
5. The filter transforms `###l4p DEBUG "first";` → `DEBUG "first";`
6. Transformed source is then tokenized and compiled

### Real-World Impact

Modules that rely on source filters:
- **Log::Log4perl** (`:resurrect` tag) - Test: t/049Unhide.t
- **Filter::Simple** - Simplified filter interface
- **Switch** (deprecated but common)
- **Lingua::Romana::Perligata** - Perl in Latin
- **Acme::\*** modules

## Current Implementation

### FilterUtilCall.java

PerlOnJava has a partial implementation in `FilterUtilCall.java`:

```java
public class FilterUtilCall extends PerlModuleBase {
    // Thread-local filter stack
    private static final ThreadLocal<FilterContext> filterContext;
    
    // XS function implementations
    public static RuntimeList real_import(...)  // Called by filter_add()
    public static RuntimeList filter_read(...)  // Read next chunk
    public static RuntimeList filter_del(...)   // Remove filter
    
    // Apply installed filters to source
    public static String applyFilters(String sourceCode) {...}
    
    // Workaround: preprocess BEGIN blocks containing filter_add
    public static String preprocessWithBeginFilters(String sourceCode) {...}
}
```

### preprocessWithBeginFilters() Workaround

The current workaround handles explicit `BEGIN { filter_add(...) }` blocks:

1. Scans source for `BEGIN { ... filter ... }`
2. Extracts and executes the BEGIN block
3. Applies any installed filters to remaining source
4. Returns filtered source for parsing

**Limitation**: This only works for explicit BEGIN blocks, NOT for filters installed via `use Module qw(:tag)` because:
- The `use` statement is not recognized as a filter installer
- The `import()` method that installs the filter is called after tokenization

## Perl Source Filter Semantics

### Filter::Util::Call API

```perl
# Install a filter (usually in import())
filter_add($coderef_or_object);

# Inside the filter: read next chunk into $_
$status = filter_read();      # Line mode
$status = filter_read($size); # Block mode

# Remove current filter
filter_del();

# Return values:
# > 0 : More data available
# = 0 : EOF
# < 0 : Error
```

### Filter Types

1. **Closure Filter**: Anonymous sub passed to `filter_add()`
   ```perl
   filter_add(sub {
       my $status = filter_read();
       s/old/new/g if $status > 0;
       return $status;
   });
   ```

2. **Method Filter**: Blessed object with `filter()` method
   ```perl
   filter_add(bless {}, $class);
   # Calls $obj->filter() repeatedly
   ```

### Filter::Simple API

Higher-level interface that collects all source then transforms:

```perl
use Filter::Simple;

FILTER { s/BANG/die/g };  # Transform all source

FILTER_ONLY
    code => sub { ... },      # Transform code only
    string => sub { ... };    # Transform strings only
```

## Proposed Solution: Token Rejoin and Re-tokenize

### Key Insight

The Lexer produces an array of `LexerToken` objects, each with a `text` field containing the original characters. These tokens can be **rejoined** back into source text by concatenating their `text` values, then **re-tokenized** after filtering.

This is much simpler than incremental parsing because:
1. The parser already has access to `parser.tokens` (the token array)
2. Tokens can be rejoined from any position: `tokens[i].text + tokens[i+1].text + ...`
3. After filtering, we just re-tokenize and replace the remaining tokens

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                 Parser processes use statement                   │
│                 (tokenIndex points to position after use)        │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                 import() installs a filter                       │
│                 FilterState.markFilterInstalled()                │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│              Check: FilterState.wasFilterInstalled()?            │
└─────────────────────────────────────────────────────────────────┘
                                │ Yes
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  1. Rejoin remaining tokens: tokens[i..end].map(t => t.text)    │
│  2. Apply filters: FilterUtilCall.applyFilters(rejoined)        │
│  3. Re-tokenize: Lexer.tokenize(filtered)                       │
│  4. Replace: parser.tokens = tokens[0..i-1] + newTokens         │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                 Continue parsing with new tokens                 │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

#### 1. FilterState Tracking

```java
// In FilterUtilCall.java or new FilterState.java
public class FilterState {
    // Track if filters were installed during current use statement
    private static final ThreadLocal<Boolean> filterInstalledDuringUse = 
        ThreadLocal.withInitial(() -> false);
    
    public static void markFilterInstalled() {
        filterInstalledDuringUse.set(true);
    }
    
    public static boolean wasFilterInstalled() {
        boolean result = filterInstalledDuringUse.get();
        filterInstalledDuringUse.set(false);  // Reset
        return result;
    }
}
```

#### 2. Modified filter_add() 

In `FilterUtilCall.real_import()`:

```java
public static RuntimeList real_import(RuntimeArray args, int ctx) {
    // ... existing code to add filter to stack ...
    
    // Mark that a filter was installed
    FilterState.markFilterInstalled();
    
    return scalarTrue.getList();
}
```

#### 3. Token Rejoin Utility

```java
// In Parser.java or new utility class
public static String rejoinTokens(List<LexerToken> tokens, int fromIndex) {
    StringBuilder sb = new StringBuilder();
    for (int i = fromIndex; i < tokens.size(); i++) {
        sb.append(tokens.get(i).text);
    }
    return sb.toString();
}
```

#### 4. Post-import Filter Check

In `StatementParser.parseUseDeclaration()`, after calling `import()`:

```java
// After import() completes (around line 708)
if (FilterState.wasFilterInstalled()) {
    // A filter was installed - rejoin remaining tokens, filter, and re-tokenize
    int currentPos = parser.tokenIndex;
    
    // Step 1: Rejoin remaining tokens back to source text
    String remainingSource = rejoinTokens(parser.tokens, currentPos);
    
    // Step 2: Apply the installed filters
    String filteredSource = FilterUtilCall.applyFilters(remainingSource);
    
    // Step 3: Re-tokenize the filtered source
    Lexer lexer = new Lexer(filteredSource);
    List<LexerToken> newTokens = lexer.tokenize();
    
    // Step 4: Replace remaining tokens with filtered tokens
    // Keep tokens[0..currentPos-1], append newTokens
    List<LexerToken> updatedTokens = new ArrayList<>(
        parser.tokens.subList(0, currentPos));
    updatedTokens.addAll(newTokens);
    parser.tokens = updatedTokens;
    
    // Clear the filter after applying (it's been consumed)
    FilterUtilCall.clearFilters();
}
```

### Algorithm

```
function parseUseDeclaration(parser):
    1. Parse and execute `use Module qw(args)`
       - This calls require() then import()
       - import() may call filter_add()
    
    2. After import() returns, check FilterState.wasFilterInstalled()
    
    3. If a filter was installed:
       a. currentPos = parser.tokenIndex  // Position after use statement
       b. remainingSource = rejoinTokens(parser.tokens, currentPos)
       c. filteredSource = FilterUtilCall.applyFilters(remainingSource)
       d. newTokens = Lexer.tokenize(filteredSource)
       e. parser.tokens = parser.tokens[0..currentPos-1] + newTokens
       f. FilterUtilCall.clearFilters()  // Filter consumed
    
    4. Continue parsing (with potentially filtered tokens)
```

### Example Walkthrough

Given this source:
```perl
use Log::Log4perl qw(:resurrect);
###l4p DEBUG "hello";
print "world\n";
```

**Before filtering:**
```
tokens = [
  "use", " ", "Log::Log4perl", " ", "qw(:resurrect)", ";", "\n",
  "###l4p DEBUG \"hello\";", "\n",
  "print", " ", "\"world\\n\"", ";", "\n"
]
```

**After `use` statement executes:**
1. `import(':resurrect')` installs a filter that removes `###l4p` prefixes
2. `FilterState.wasFilterInstalled()` returns true
3. `parser.tokenIndex` is at position 7 (after the semicolon and newline)

**Rejoin remaining tokens:**
```
remainingSource = "###l4p DEBUG \"hello\";\nprint \"world\\n\";\n"
```

**Apply filter:**
```
filteredSource = "DEBUG \"hello\";\nprint \"world\\n\";\n"
```

**Re-tokenize and replace:**
```
newTokens = ["DEBUG", " ", "\"hello\"", ";", "\n", "print", " ", "\"world\\n\"", ";", "\n"]
parser.tokens = tokens[0..6] + newTokens
```

**Continue parsing** with the filtered tokens.

## Implementation Phases

### Phase 1: Filter State Tracking (Simple)

Add state tracking to know when a filter was installed:

1. Add `filterInstalledDuringUse` flag to `FilterUtilCall.java`
2. Set flag in `real_import()` when filter is added
3. Add `wasFilterInstalled()` / `clearFilterInstalledFlag()` methods

**Files to modify:**
- `FilterUtilCall.java` - Add state tracking

**Estimated effort:** ~30 minutes

### Phase 2: Token Rejoin and Re-tokenize (Core Implementation)

Implement the token rejoin, filter, and re-tokenize logic:

1. Add `rejoinTokens(List<LexerToken> tokens, int fromIndex)` utility
2. After `import()` in `parseUseDeclaration()`, check if filter was installed
3. If yes: rejoin → filter → re-tokenize → replace tokens
4. Clear filter after applying

**Files to modify:**
- `StatementParser.java` - Add post-import filter check (~20 lines)
- `FilterUtilCall.java` - May need to expose `applyFilters()` better

**Estimated effort:** ~2-3 hours

### Phase 3: Method Filters Support

Currently `applyFilters()` only supports closure filters. Add method filter support:

```java
if (!isCodeRef.getBoolean()) {
    // Method filter: call $obj->filter()
    RuntimeScalar filterMethod = Universal.can(filterObj, "filter");
    // Call repeatedly until returns <= 0
}
```

**Files to modify:**
- `FilterUtilCall.java` - Add method filter support in `applyFilters()`

**Estimated effort:** ~1-2 hours

### Phase 4: Error Handling and Edge Cases

Handle edge cases:

1. Multiple `use` statements that install filters
2. Nested module loading where inner module installs filter
3. `no Module` removing filters
4. Error reporting with correct line numbers after re-tokenization

**Files to modify:**
- Various - depends on edge cases discovered

**Estimated effort:** ~2-4 hours

## Alternative Approaches (For Reference)

### Approach A: Pre-scan for Filter-Installing Modules

Before parsing, scan source for known filter-installing modules:

1. Build a list of modules known to install filters (Log::Log4perl with :resurrect, etc.)
2. If source contains `use KnownFilterModule`, use two-pass compilation
3. First pass: just execute use statements
4. Second pass: parse filtered source

**Pros**: No runtime overhead for code without filters
**Cons**: Requires maintaining a list of known filter modules

### Approach B: Streaming Lexer (Most Correct, Most Complex)

Modify Lexer to read source incrementally through filter chain:

1. Lexer calls `filter_read()` for each chunk
2. Filters transform source during reading
3. Exactly matches Perl's behavior

**Pros**: Correct semantics, handles all edge cases
**Cons**: Major Lexer rewrite, affects all code paths

## Risks and Considerations

### Performance

- Filters add overhead to compilation
- Re-tokenization for filtered source adds latency
- Consider caching filtered source for repeated use

### Edge Cases

1. **Nested use statements**: Module A uses Module B which installs a filter
2. **BEGIN blocks in filtered code**: Must be executed correctly
3. **Filter affecting use statement itself**: Pathological but possible
4. **Multiple filters**: Must apply in correct order (LIFO stack)

### Compatibility

- Must maintain backward compatibility for code without filters
- Filter installation via `@INC` hooks (less common)
- `eval` and `do` with filters

## Testing Strategy

### Unit Tests

```perl
# Test filter installation via use
use FilterModule qw(:filter_tag);
# ... code that should be transformed ...

# Test Filter::Simple
use SimpleFilter;
FILTER { s/X/Y/g };
print "X";  # Should print Y
```

### Integration Tests

- Log::Log4perl `:resurrect` tag (t/049Unhide.t)
- Custom filter modules

## Related Documentation

- [perldoc perlfilter](https://perldoc.perl.org/perlfilter) - Source filters overview
- [perldoc Filter::Util::Call](https://perldoc.perl.org/Filter::Util::Call) - Low-level API
- [perldoc Filter::Simple](https://perldoc.perl.org/Filter::Simple) - High-level API

## Progress Tracking

### Current Status: Implementation Complete (Closure Filters)

### Completed
- [x] Research Perl source filter semantics
- [x] Analyze current PerlOnJava implementation  
- [x] Identify root cause of t/049Unhide.t failure
- [x] Design token rejoin/re-tokenize solution
- [x] **Phase 1**: Add filter state tracking to FilterUtilCall.java (2026-03-27)
  - Added `filterInstalledDuringUse` ThreadLocal flag
  - Added `markFilterInstalled()`, `wasFilterInstalled()`, `hasActiveFilters()` methods
  - Modified `real_import()` to mark when filter is installed
- [x] **Phase 2**: Implement token rejoin and re-tokenize in StatementParser.java (2026-03-27)
  - Added `applySourceFilterToRemainingTokens()` method
  - Integrated with `parseUseDeclaration()` to check for filters after import()
  - Added `updateTokens()` method to ErrorMessageUtil
  - Fixed EOF token handling (skip EOF tokens when rejoining to avoid garbage characters)
- [x] **Phase 3**: Test with Log::Log4perl `:resurrect` tag - PASSED

### Remaining Work
- [ ] **Phase 4**: Add method filter support (currently returns original source for method filters)
- [ ] Add debug environment variable documentation (JPERL_FILTER_DEBUG=1)
- [ ] **Phase 5**: Fix FILTER_ONLY @transforms issue in Java instead of patching Filter::Simple (see below)

### Known Issues

#### FILTER_ONLY @transforms Scope Issue (2026-03-27)

**Problem**: When multiple filter modules using `FILTER_ONLY` are loaded in sequence, the second filter's `$multitransform` closure incorrectly includes transforms from the first module.

**Root Cause**: In Filter::Simple, `@transforms` is a package variable. In native Perl, this works because filters process source incrementally - each filter completes before the next filter module is loaded. In PerlOnJava, we tokenize upfront then apply filters, so multiple filter modules may be loaded before any filter runs, causing `@transforms` to accumulate transforms from different modules.

**Current Fix**: Patched `Filter::Simple.pm` to make `@transforms` lexical in `FILTER_ONLY`:
```perl
sub FILTER_ONLY {
    my $caller = caller;
    my @transforms;  # Made lexical instead of package-scoped
    ...
}
```

**TODO - Proper Java-side Fix**: The ideal solution would be to fix this in PerlOnJava's module loading code:
1. Before loading a module that may use `FILTER_ONLY`, save `@Filter::Simple::transforms`
2. Clear `@Filter::Simple::transforms` 
3. After module loading completes, restore the saved value

This would allow using unmodified upstream Filter::Simple. The challenge is detecting which modules will use `FILTER_ONLY` before loading them. Possible approaches:
- Clear `@Filter::Simple::transforms` before every `require` (may have side effects)
- Track filter module loading depth and isolate transforms per level
- Hook into Filter::Simple's FILTER_ONLY to auto-reset before each call

**Files affected by current fix**:
- `src/main/perl/lib/Filter/Simple.pm` (marked as `protected: true` in config.yaml)

### Files Modified
- `src/main/java/org/perlonjava/runtime/perlmodule/FilterUtilCall.java`
- `src/main/java/org/perlonjava/frontend/parser/StatementParser.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ErrorMessageUtil.java`

### Open Questions (Resolved)
- Line number tracking after re-tokenization: We update ErrorMessageUtil with new tokens
- How to handle EOF tokens: Skip them when rejoining (they contain invalid characters)
