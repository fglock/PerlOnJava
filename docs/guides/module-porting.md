# Porting Perl Modules to PerlOnJava

## Overview

PerlOnJava supports three types of Perl modules:

1. Pure Perl modules (.pm files)
2. Java-implemented modules (replacing XS/C modules)
3. Hybrid modules (combining Perl and Java implementations)

A hybrid module typically consists of a .pm file containing Perl code and a corresponding Java class that implements performance-critical or system-level functionality. This approach lets you leverage the best of both languages - Perl's expressiveness and Java's performance.

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

## Java-Implemented Modules

Java implementations replace Perl XS modules. They extend `PerlModuleBase` and implement the module's functionality in Java.

Example from DBI module:

```java
public class Dbi extends PerlModuleBase {
    public Dbi() {
        super("DBI", false);
    }

    public static void initialize() {
        Dbi dbi = new Dbi();
        dbi.registerMethod("connect", null);
        dbi.registerMethod("prepare", null);
        // Register other methods...
    }
}
```

## Module Loading API

### Pure Perl Module Loading
```perl
use ModuleName;
require "ModuleName.pm";
```

### Java Module Registration

1. Extend PerlModuleBase:
```java
public class MyModule extends PerlModuleBase {
    public MyModule() {
        super("My::Module");
    }
}
```

2. Register methods:
```java
protected void initialize() {
    registerMethod("method_name", null);
    registerMethod("perl_name", "java_name", null);
}
```

3. Define exports:
```java
defineExport("EXPORT", "function1", "function2");
defineExport("EXPORT_OK", "optional_function");
defineExportTag("group", "function1", "function2");
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

## Module Registration in GlobalContext

After implementing a module, register it in `GlobalContext.java`:

```java
// Initialize built-in Perl classes
DiamondIO.initialize(compilerOptions);
Universal.initialize();
MyNewModule.initialize();  // Add your module here
```

This step ensures your module is initialized during PerlOnJava startup alongside other core modules.

The initialization sequence handles:
- Method registration
- Export definitions
- Global variable setup
- Module state initialization

## Real-World Example: DBI Module

The DBI module demonstrates a complete port:

1. Pure Perl portion (`DBI.pm`):
```perl
package DBI;
use strict;

sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}
```

2. Java implementation (`Dbi.java`):
```java
public class Dbi extends PerlModuleBase {
    public static RuntimeList connect(RuntimeArray args, int ctx) {
        RuntimeHash dbh = new RuntimeHash();
        String jdbcUrl = args.get(1).toString();
        dbh.put("Username", new RuntimeScalar(args.get(2).toString()));
        // Implementation...
        return dbh.createReference().getList();
    }
}
```

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
