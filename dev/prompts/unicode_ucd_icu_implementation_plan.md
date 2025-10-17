# Unicode::UCD ICU4J Implementation Plan

## Objective

Replace the large `unicore/UCD.pl` file (282KB) with Java implementation using ICU4J libraries to:
1. Avoid "Method too large" JVM errors
2. Improve performance
3. Reduce JAR size
4. Leverage existing ICU4J dependency (version 77.1)

## Current Status

**Problem:** `unicore/UCD.pl` contains huge data structures that exceed JVM's 64KB method bytecode limit

**Available:** ICU4J 77.1 is already a dependency and used in:
- `UnicodeResolver.java` - Property lookups
- `StringOperators.java` - Case mapping
- `Lexer.java` - Character classification
- `IdentifierParser.java` - Identifier validation

## Key Functions to Implement

From `Unicode::UCD` module, the critical function is:

### `prop_invmap($property)`

Returns 4-element list:
1. `$invlist_ref` - Array of code point range starts
2. `$invmap_ref` - Array of property values for each range
3. `$format` - Format string ("s", "a", "ad", "ar", "ale", "sl")
4. `$default` - Default value for unmapped code points

**Example:**
```perl
my ($list_ref, $map_ref, $format, $default) = prop_invmap("General_Category");
```

## ICU4J APIs Available

### UCharacter Class
- `getIntPropertyValue(int ch, int property)` - Get property value for code point
- `getPropertyEnum(String alias)` - Get property ID from name
- `getPropertyValueEnum(int property, String alias)` - Get value ID

### UnicodeSet Class
- Represents sets of Unicode characters
- Can iterate over ranges
- Supports all Unicode properties

### UProperty Interface
- Constants for all Unicode properties
- Property name/alias resolution

## Implementation Strategy

### Phase 1: Create UnicodeUCD.java Module

```java
package org.perlonjava.perlmodule;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;

public class UnicodeUCD extends PerlModuleBase {
    
    public static RuntimeList prop_invmap(RuntimeArray args, int ctx) {
        String property = args.getFirst().toString();
        
        // Use ICU4J to build inversion map
        // Return 4-element list
    }
    
    // Other functions as needed
}
```

### Phase 2: Register with XSLoader

Update `UnicodeUCD` to be loadable via XSLoader, similar to `UnicodeNormalize`.

### Phase 3: Remove unicore/UCD.pl

Once Java implementation works, remove the large Perl file.

## Benefits

1. **Performance:** Native Java code vs interpreted Perl
2. **Size:** No 282KB data file in JAR
3. **Maintainability:** Use ICU4J's maintained Unicode data
4. **Compatibility:** ICU4J tracks Unicode standards
5. **No JVM Limits:** No method size issues

## Implementation Steps

1. Create `UnicodeUCD.java` with `prop_invmap()` implementation
2. Use ICU4J `UnicodeSet` to build inversion maps
3. Register methods with XSLoader
4. Test with `t/uni/lower.t`
5. Remove `unicore/UCD.pl` from JAR
6. Verify all Unicode tests

## ICU4J Code Example

```java
// Get all code points with a specific property value
UnicodeSet set = new UnicodeSet("[:General_Category=Lowercase_Letter:]");

// Iterate over ranges
for (UnicodeSet.EntryRange range : set.ranges()) {
    int start = range.codepoint;
    int end = range.codepointEnd;
    // Build inversion map
}
```

## Estimated Effort

- **Time:** 2-3 hours
- **Complexity:** Medium (requires understanding inversion map format)
- **Risk:** Low (ICU4J is well-documented and already used)
- **Impact:** High (unblocks 6+ Unicode test files, improves performance)

## Next Steps

1. Study `prop_invmap()` return format in detail
2. Create minimal test case
3. Implement `UnicodeUCD.java`
4. Test and iterate
5. Remove large Perl file
