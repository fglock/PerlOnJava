# Guide: Importing a Perl Module to PerlOnJava

This is a reusable prompt/guide for implementing a Perl module in PerlOnJava with a Java backend.

## Prerequisites

Before starting, gather:
1. **Module API documentation**: Run `perldoc ModuleName` to get the expected interface
2. **Java library**: Find a suitable Java library that provides the functionality (search Maven Central)
3. **Reference implementations**: Look at existing modules in `src/main/java/org/perlonjava/perlmodule/`

## Step-by-Step Implementation

### Step 1: Add Java Dependency

Add the Java library to both build systems:

**build.gradle** (in dependencies section):
```gradle
implementation 'group.id:artifact-id:version'           // Description
```

**pom.xml** (in dependencies section):
```xml
<dependency>
    <groupId>group.id</groupId>
    <artifactId>artifact-id</artifactId>
    <version>version</version>
</dependency>
```

### Step 2: Create Java Implementation

Create `src/main/java/org/perlonjava/perlmodule/ModuleName.java`:

```java
package org.perlonjava.perlmodule;

// Import Java library classes


/**
 * The {@code ModuleName} class provides methods for [description]
 * within a Perl-like runtime environment.
 * <p>
 * Note: Some methods are defined in src/main/perl/lib/MODULE/NAME.pm
 */
public class ModuleName extends PerlModuleBase {

    /**
     * Constructor - module name should match Perl package name but without ::
     * XSLoader converts Module::Name to ModuleName when loading
     */
    public ModuleName() {
        super("MODULE::NAME", false);  // false for XSLoader modules
    }

    /**
     * Initializes the module by registering methods.
     * Called by XSLoader.load()
     */
    public static void initialize() {
        ModuleName module = new ModuleName();
        try {
            // Register methods - name in Perl, Java method name, prototype
            module.registerMethod("function_name", null);  // null = same name, auto prototype
            module.registerMethod("perl_name", "javaMethodName", "$");  // explicit mapping
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing ModuleName method: " + e.getMessage());
        }
    }

    /**
     * Example method - ALL methods MUST return RuntimeList
     */
    public static RuntimeList function_name(RuntimeArray args, int ctx) {
        // Get arguments
        RuntimeScalar arg1 = args.get(0);

        // Process using Java library
        // ...

        // Return result - ALWAYS use .getList()
        return new RuntimeScalar(result).getList();
    }

    /**
     * For methods returning multiple values or supporting list context
     */
    public static RuntimeList multi_return(RuntimeArray args, int ctx) {
        // Check context
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            list.add(new RuntimeScalar(value1));
            list.add(new RuntimeScalar(value2));
            return list;
        }
        // Scalar context
        return new RuntimeScalar(scalarValue).getList();
    }
}
```

### Step 3: Create Perl Wrapper Module

Create `src/main/perl/lib/MODULE/NAME.pm` (or `src/main/perl/lib/MODULENAME.pm` for single-level):

```perl
package MODULE::NAME;

use Exporter "import";
use warnings;
use strict;

XSLoader::load( 'ModuleName' );  # Class name without ::

# NOTE: The core implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/ModuleName.java

our @EXPORT_OK = qw(function1 function2);
our @EXPORT = @EXPORT_OK;  # Or subset for default exports

# Optional: export tags
our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

1;

__END__

=head1 NAME

MODULE::NAME - Brief description

=head1 SYNOPSIS

    use MODULE::NAME qw(function1 function2);
    
    my $result = function1($arg);

=head1 DESCRIPTION

Description of the module.

=head1 FUNCTIONS

=head2 function1

Description of function1.

=head2 function2

Description of function2.

=cut
```

### Step 4: Create Example File (if asked)

Create `examples/modulename.pl`:

```perl
use strict;
use warnings;
use MODULE::NAME qw(function1 function2);

# Demonstrate basic usage
my $result = function1("input");
print "Result: $result\n";

# Show more complex examples
# ...

print "=== Module demo complete! ===\n";
```

### Step 5: Create Test File

Create `src/test/resources/modulename.t`:

```perl
use strict;
use warnings;
use Test::More tests => N;  # Replace N with actual count

use_ok('MODULE::NAME', qw(function1 function2));

# Test basic functionality
{
    my $result = function1("input");
    is($result, "expected", 'function1 basic test');
}

# Test edge cases
{
    my $result = function1("");
    is($result, "", 'function1 empty input');
}

# Test error handling (if applicable)
{
    my ($data, $err) = function_with_error("bad input");
    ok($err, 'error returned for bad input');
}
```

### Step 6: Build and Test

```bash
# Compile
make

# Run example
./jperl examples/modulename.pl

# Run tests
./jperl src/test/resources/modulename.t
```

### Step 7: Update Documentation

Update these files:
- `docs/FEATURE_MATRIX.md` - Add module entry in `Non-core modules` section
- `MILESTONES.md` - Note the addition in the `Upcoming Milestones` section

### Step 8: Commit

```bash
git add -A
git commit -m "Add MODULE::NAME module implementation

- Add ModuleName.java using [library] for [functionality]
- Add MODULE/NAME.pm Perl wrapper with [exports]
- Add [library] [version] dependency to build.gradle and pom.xml
- Add examples/modulename.pl demonstrating usage
- Add src/test/resources/modulename.t with N passing tests
- Update documentation

Features:
- function1(): Description
- function2(): Description"
```

## Common Patterns

### Converting Java Objects to RuntimeScalar

```java
// Strings
return new RuntimeScalar(javaString).getList();

// Integers
return new RuntimeScalar(longValue).getList();

// Doubles
return new RuntimeScalar(doubleValue).getList();

// Booleans
return new RuntimeScalar(boolValue).getList();

// Undef
return scalarUndef.getList();

// Hash reference
RuntimeHash hash = new RuntimeHash();
hash.put("key", new RuntimeScalar("value"));
return hash.createReference().getList();

// Array reference
RuntimeArray array = new RuntimeArray();
array.elements.add(new RuntimeScalar("item"));
return array.createReference().getList();
```

### Converting RuntimeScalar to Java Objects

```java
// Get from args
RuntimeScalar arg = args.get(0);

// Convert to Java types
String str = arg.toString();
long num = arg.getLong();
int i = arg.getInt();
double d = arg.getDouble();
boolean b = arg.getBoolean();

// Dereference hash
RuntimeHash hash = (RuntimeHash) arg.value;  // if HASHREFERENCE
// or
RuntimeHash hash = arg.hashDeref();

// Dereference array
RuntimeArray array = (RuntimeArray) arg.value;  // if ARRAYREFERENCE
```

### Error Handling Patterns

```java
// Return error in list context (like from_toml)
public static RuntimeList parse(RuntimeArray args, int ctx) {
    try {
        // ... parsing logic ...
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            list.add(data);
            list.add(scalarUndef);  // No error
            return list;
        }
        return data.getList();
    } catch (Exception e) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            list.add(scalarUndef);
            list.add(new RuntimeScalar(e.getMessage()));
            return list;
        }
        return scalarUndef.getList();
    }
}

// Throw Perl exception (preferred)
// Import: import org.perlonjava.runtime.runtimetypestypes.PerlCompilerException;
throw new PerlCompilerException("Error message");

// Alternative (less preferred)
throw new IllegalStateException("Error message");
```

## Reference Implementations

Study these existing modules for patterns:

| Module | Java Class | Features |
|--------|-----------|----------|
| JSON | Json.java | OO interface, encode/decode, options |
| YAML::PP | YAMLPP.java | File I/O, multiple documents, options |
| TOML | Toml.java | Simple functional interface, error handling |
| List::Util | ListUtil.java | Many small functions, prototypes |
| Hash::Util | HashUtil.java | Hash manipulation, XSLoader pattern |
| Storable | Storable.java | Serialization, blessed objects |

## Checklist

- [ ] Java dependency added to build.gradle
- [ ] Java dependency added to pom.xml
- [ ] Java class created extending PerlModuleBase
- [ ] All methods return RuntimeList
- [ ] Constructor uses `super("Module::Name", false)` for XSLoader
- [ ] initialize() registers all methods
- [ ] Perl wrapper created with XSLoader::load()
- [ ] @EXPORT_OK lists all exportable functions
- [ ] Example file created and tested
- [ ] Test file created with passing tests
- [ ] Documentation updated
- [ ] Code compiles with `make`
- [ ] Example runs successfully
- [ ] Tests pass
