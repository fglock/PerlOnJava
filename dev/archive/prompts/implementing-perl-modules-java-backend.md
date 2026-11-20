# Implementing Perl Modules with Java Backend in PerlOnJava

This document captures critical learnings from implementing Hash::Util and debugging module loading issues.

## Problem Context

When implementing Hash::Util to fix failing tests in op/hash.t, we encountered multiple issues with creating a Perl module backed by Java code. The module needed to provide accurate hash bucket statistics via the `bucket_ratio()` function.

## Key Learnings and Solutions

### 1. Method Return Types Must Be RuntimeList

**Problem:** Methods returning RuntimeScalar were not being registered properly by registerMethod().

**Solution:** All module methods must return `RuntimeList`, not `RuntimeScalar`.

```java
// WRONG - Methods won't be registered
public static RuntimeScalar bucket_ratio(RuntimeArray args, int ctx) {
    return new RuntimeScalar(result);
}

// CORRECT - Methods will be registered properly
public static RuntimeList bucket_ratio(RuntimeArray args, int ctx) {
    return new RuntimeScalar(result).getList();
}
```

### 2. Constructor Parameter for XSLoader Modules

**Problem:** Modules loaded via XSLoader were not working with `super("Module::Name", true)`.

**Solution:** Modules loaded via XSLoader must use `false` for the auto-export parameter.

```java
// For modules loaded directly (like List::Util)
public ListUtil() {
    super("List::Util", true);  // true for auto-export
}

// For modules loaded via XSLoader (like Hash::Util)
public HashUtil() {
    super("Hash::Util", false);  // false because loaded via XSLoader
}
```

### 3. Exporter Pattern in Perl Module

**Problem:** Using `use Exporter 'import'` pattern didn't work correctly.

**Solution:** Use the traditional `require Exporter` with `@ISA` pattern.

```perl
# WRONG - Modern import pattern doesn't work
use Exporter 'import';

# CORRECT - Traditional pattern works
require Exporter;
our @ISA = qw(Exporter);
```

### 4. Explicit @EXPORT_OK Declaration

**Problem:** Trying to dynamically populate @EXPORT_OK from Java didn't work.

**Solution:** Explicitly declare all exportable functions in the Perl module.

```perl
# Must explicitly list all functions
our @EXPORT_OK = qw(
    bucket_ratio
    lock_keys unlock_keys
    lock_hash unlock_hash
    hash_seed
);
```

### 5. XSLoader Integration

**Problem:** Functions weren't available in the correct namespace.

**Solution:** Call XSLoader before setting up exports, and ensure Java module registers in correct namespace.

```perl
# Load Java backend FIRST
require XSLoader;
XSLoader::load('HashUtil');  # Loads org.perlonjava.runtime.perlmodule.HashUtil

# THEN set up exports
require Exporter;
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(...);
```

### 6. Method Registration in Java

**Problem:** registerMethod() was throwing NoSuchMethodException silently.

**Solution:** Ensure method signatures match exactly what registerMethod() expects.

```java
public static void initialize() {
    HashUtil hashUtil = new HashUtil();
    try {
        // Method name in Java must match registration
        hashUtil.registerMethod("bucket_ratio", "bucket_ratio", "\\%");
    } catch (NoSuchMethodException e) {
        System.err.println("Warning: Missing method: " + e.getMessage());
    }
}
```

## Complete Working Example

### Java Backend (HashUtil.java)

```java
package org.perlonjava.runtime.perlmodule;

public class HashUtil extends PerlModuleBase {

   public HashUtil() {
      super("Hash::Util", false);  // false for XSLoader modules
   }

   public static void initialize() {
      HashUtil hashUtil = new HashUtil();
      try {
         hashUtil.registerMethod("bucket_ratio", "bucket_ratio", "\\%");
         // Register other methods...
      } catch (NoSuchMethodException e) {
         System.err.println("Warning: " + e.getMessage());
      }
   }

   // Methods MUST return RuntimeList
   public static RuntimeList bucket_ratio(RuntimeArray args, int ctx) {
      // Implementation...
      return new RuntimeScalar(result).getList();
   }
}
```

### Perl Module (Hash/Util.pm)

```perl
package Hash::Util;

use strict;
use warnings;
require Exporter;

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    bucket_ratio
    lock_keys unlock_keys
    lock_hash unlock_hash
    hash_seed
);
our $VERSION = '0.28';

# Load the Java backend
require XSLoader;
XSLoader::load('HashUtil');

our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

1;
```

## Testing the Implementation

```perl
# Test direct call
use Hash::Util;
my %h = (a => 1, b => 2);
print Hash::Util::bucket_ratio(%h), "\n";  # Should work

# Test exported function
use Hash::Util qw(bucket_ratio);
my %h = (a => 1, b => 2);
print bucket_ratio(%h), "\n";  # Should print "2/8" or similar
```

## Common Pitfalls to Avoid

1. **Don't use initializeExporter()** - This is not needed for XSLoader modules
2. **Don't use defineExport()** - Export lists should be in Perl, not Java
3. **Don't return RuntimeScalar** - Always return RuntimeList from module methods
4. **Don't use modern Exporter syntax** - Stick to traditional @ISA pattern
5. **Don't try to dynamically populate @EXPORT_OK** - List functions explicitly

## Debugging Tips

1. Add debug prints in initialize() to verify it's being called:
   ```java
   System.err.println("HashUtil.initialize() called!");
   ```

2. Check if methods are registered by catching exceptions:
   ```java
   try {
       hashUtil.registerMethod("bucket_ratio", "bucket_ratio", "\\%");
       System.err.println("Registered bucket_ratio");
   } catch (NoSuchMethodException e) {
       e.printStackTrace();
   }
   ```

3. Verify functions exist in namespace:
   ```perl
   print "Functions: ";
   for my $k (keys %Hash::Util::) {
       print "$k " if defined &{"Hash::Util::$k"}
   }
   ```

## Results

Following these patterns, Hash::Util was successfully implemented with:
- bucket_ratio() returning accurate hash statistics
- Proper export functionality via Exporter
- +624 tests fixed in op/hash.t (97.1% pass rate)

## See Also

- ListUtil.java - Example of a module loaded directly (not via XSLoader)
- ScalarUtil.java - Another module implementation for reference
- DBI.java - Example of module with complex initialization
