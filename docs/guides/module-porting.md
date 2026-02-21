# Porting Perl Modules to PerlOnJava

## Overview

PerlOnJava supports three types of Perl modules:

1. **Pure Perl modules** (.pm files) - No Java code needed
2. **Java-implemented modules** (via XSLoader) - Perl modules that load Java implementations, replacing XS/C modules
3. **Built-in modules** (in GlobalContext) - Internal PerlOnJava modules available at startup (e.g., UNIVERSAL)

**Most CPAN module ports should use type #2 (XSLoader).** Type #3 is only for internal PerlOnJava functionality.

## Directory Structure

- Pure Perl modules: `src/main/perl/lib/`
- Java implementations: `src/main/java/org/perlonjava/perlmodule/`

## Pure Perl Modules

Pure Perl modules can be used directly or with minimal changes. Example from `if.pm`:

```perl
package if;
use strict;

sub import   { shift; unshift @_, 1; goto &work }
sub unimport { shift; unshift @_, 0; goto &work }
```

## Java-Implemented Modules (via XSLoader)

Java implementations replace Perl XS modules. They extend `PerlModuleBase` and are loaded via `XSLoader::load()`.

### Naming Convention

XSLoader maps Perl module names to Java class names:

- **Perl module**: `DBI` → **Java class**: `org.perlonjava.runtime.perlmodule.Dbi`
- **Perl module**: `Text::CSV` → **Java class**: `org.perlonjava.runtime.perlmodule.Text_CSV`
- **Perl module**: `My::Module` → **Java class**: `org.perlonjava.runtime.perlmodule.My_Module`

Rules:
- Package: Always `org.perlonjava.runtime.perlmodule`
- Class name: Perl module name with `::` replaced by `_`
- First letter capitalized (Java convention)

### Basic Structure

```java
package org.perlonjava.runtime.perlmodule;

public class Dbi extends PerlModuleBase {
    public Dbi() {
        super("DBI", false);
    }

    // Called by XSLoader::load('DBI')
    public static void initialize() {
        Dbi dbi = new Dbi();
        dbi.registerMethod("connect", null);
        dbi.registerMethod("prepare", null);
        // Register other methods...
    }

    // Implement methods
    public static RuntimeList connect(RuntimeArray args, int ctx) {
        // Implementation...
    }
}
```

## Using Java-Implemented Modules

### From Perl Code (XSLoader)

In your Perl module, load the Java implementation:

```perl
package My::Module;
use strict;
use warnings;

our $VERSION = '1.00';

# Load Java implementation
require XSLoader;
XSLoader::load('My::Module', $VERSION);

# Pure Perl methods can call Java methods
sub helper_method {
    my ($self, @args) = @_;
    return $self->java_implemented_method(@args);
}

1;
```

### From User Code

Users just use the module normally:

```perl
use My::Module;

my $obj = My::Module->new();
$obj->method();
```

The XSLoader mechanism is completely transparent to end users.

## Implementing Java Module Methods

### Method Registration

In your Java class's `initialize()` method, register all methods:

```java
public static void initialize() {
    MyModule module = new MyModule();
    module.registerMethod("method_name", null);
    module.registerMethod("perl_name", "java_method_name", null);
}
```

### Defining Exports

```java
module.defineExport("EXPORT", "function1", "function2");
module.defineExport("EXPORT_OK", "optional_function");
module.defineExportTag("group", "function1", "function2");
```

## Calling Conventions

### Method Parameters
- First parameter: RuntimeArray containing arguments
- Second parameter: Context type (void/scalar/list)

Example:
```java
public static RuntimeList method_name(RuntimeArray args, int ctx) {
    RuntimeHash self = args.get(0).hashDeref();
    String param1 = args.get(1).toString();
    return new RuntimeList(new RuntimeScalar(result));
}
```

### Return Values
- Return `RuntimeList` containing results
- For scalar context: return single-element list
- For list context: return multi-element list
- For void context: return empty list

## Module Registration

There are two ways to register Java-implemented modules:

### 1. Built-in/Internal Modules (GlobalContext)

**Only for internal PerlOnJava modules** that need to be available immediately at startup (e.g., UNIVERSAL, CORE functions).

Register in `GlobalContext.java`:

```java
// Initialize built-in Perl classes
DiamondIO.initialize(compilerOptions);
Universal.initialize();
```

**Do not use this approach for regular CPAN-style modules.**

### 2. Regular Modules (XSLoader)

**This is the standard approach for porting modules.** Use XSLoader in your Perl module:

```perl
package DBI;
use strict;
use warnings;

our $VERSION = '1.643';

# Load Java implementation
require XSLoader;
XSLoader::load('DBI', $VERSION);

# Pure Perl methods
sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}

1;
```

When `XSLoader::load('DBI')` is called:
1. XSLoader looks for the Java class `org.perlonjava.runtime.perlmodule.Dbi`
2. Calls the static `initialize()` method
3. Registers all methods defined in the Java class

This is transparent to users - they just `use DBI` and it works.

## Real-World Example: DBI Module

The DBI module demonstrates a complete port using XSLoader:

1. **Perl module** (`DBI.pm`):
```perl
package DBI;
use strict;
use warnings;

our $VERSION = '1.643';

# Load Java implementation
require XSLoader;
XSLoader::load('DBI', $VERSION);

# Pure Perl helper method
sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}

1;
```

2. **Java implementation** (`org/perlonjava/perlmodule/Dbi.java`):
```java
public class Dbi extends PerlModuleBase {
    public Dbi() {
        super("DBI", false);
    }

    // Called by XSLoader
    public static void initialize() {
        Dbi dbi = new Dbi();
        dbi.registerMethod("connect", null);
        dbi.registerMethod("prepare", null);
        dbi.registerMethod("execute", null);
        // ... register other methods
    }

    // Implementation of connect method
    public static RuntimeList connect(RuntimeArray args, int ctx) {
        RuntimeHash dbh = new RuntimeHash();
        String jdbcUrl = args.get(1).toString();
        dbh.put("Username", new RuntimeScalar(args.get(2).toString()));
        // ... JDBC connection logic
        return dbh.createReference().getList();
    }
}
```

**Key points:**
- DBI.pm calls `XSLoader::load('DBI')` to load the Java implementation
- Java class is in `org.perlonjava.runtime.perlmodule.Dbi` (naming convention)
- `initialize()` method registers all Java-implemented methods
- Pure Perl methods (like `do()`) can call Java methods (like `prepare()`, `execute()`)

## Best Practices

1. Keep pure Perl code for simple functionality
2. Use Java implementation for:
    - Performance-critical code
    - System interactions
    - Database connectivity
    - Complex data structures
3. Maintain Perl calling conventions
4. Handle both scalar and list contexts
5. Properly manage resources and error states
6. Follow PerlOnJava naming conventions

## Testing

1. Create test files in `src/test/resources/`
2. Write Java tests in `src/test/java/`
3. Test both pure Perl and Java implementations
4. Verify compatibility with original Perl module

## Version Compatibility

- Perl version requirements
- Java version requirements
- PerlOnJava version compatibility matrix

## Error Handling

### Exception Mapping
- Perl exceptions map to Java RuntimeExceptions
- Standard error patterns follow Perl conventions
- Error propagation maintains stack traces

### Guidelines
- Use die() for Perl-style exceptions
- Propagate Java exceptions with proper context
- Maintain error state in $@ variable

## Performance Considerations

### Implementation Choice
- Use Java for performance-critical code paths
- Pure Perl for maintainability and compatibility
- Hybrid approach for balanced solutions

### Optimization Techniques
- Minimize context switches
- Cache frequently used values
- Use native Java collections where appropriate

### Memory Management
- Release resources promptly
- Monitor object lifecycles
- Follow Java garbage collection best practices

## Troubleshooting

### Common Issues
- Module loading failures
- Method registration problems
- Context handling errors

### Debugging Techniques
- Enable verbose logging
- Use Java debugger for implementation code
- Perl debugging for pure Perl portions

### Module Loading
- Verify path configuration
- Check initialization sequence
- Validate export definitions

## Migration Checklist

### Pre-migration Assessment
- [ ] Analyze module dependencies
- [ ] Identify XS/C components
- [ ] Document API requirements

### Testing Requirements
- [ ] Unit test coverage
- [ ] Integration tests
- [ ] Performance benchmarks

### Documentation Requirements
- [ ] API documentation
- [ ] Migration notes
- [ ] Version compatibility

### Post-migration Verification
- [ ] Functionality verification
- [ ] Performance validation
- [ ] Compatibility testing
