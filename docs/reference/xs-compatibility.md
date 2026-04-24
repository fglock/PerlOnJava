# XS Module Compatibility

This document tracks XS modules and their compatibility with PerlOnJava.

## How XS Modules Work in PerlOnJava

When a module calls `XSLoader::load()`:

1. **Java XS available**: PerlOnJava loads its Java implementation (fast path)
2. **No Java XS**: XSLoader returns an error matching `/loadable object/`
3. **Module has PP fallback**: Module catches error and loads pure Perl version
4. **No fallback**: Module fails to load

## Modules with Java XS Implementations

These modules have optimized Java implementations built into PerlOnJava:

| Module | Java Class | XS_VERSION | Notes |
|--------|------------|------------|-------|
| JSON | _(Perl)_ | 4.11 | Delegates to the bundled pure-Perl `JSON::PP` |
| DateTime | DateTime.java | 1.65 | Uses java.time APIs, JulianFields.RATA_DIE |
| Digest::MD5 | DigestMD5.java | - | Uses Java MessageDigest |
| Digest::SHA | DigestSHA.java | - | Uses Java MessageDigest |
| Time::HiRes | TimeHiRes.java | - | Uses System.nanoTime() |
| DBI | Dbi.java | - | JDBC backend |

## Modules with Built-in PP Fallbacks

These CPAN modules automatically fall back to pure Perl when XS is unavailable:

| Module | Fallback Module | Detection Pattern | Notes |
|--------|-----------------|-------------------|-------|
| DateTime | DateTime::PP | `/loadable object/` | Bundled PP implementation |
| JSON::XS | JSON::PP | `/loadable object/` | Separate CPAN module |
| List::Util | List::Util::PP | varies | Some functions only |
| Params::Util | Params::Util::PP | varies | Separate distribution |
| Class::XSAccessor | fallback in .pm | `/loadable object/` | Pure Perl accessors |

## Modules Requiring Java XS Implementation

These modules have no PP fallback and need Java implementations to work:

| Module | Status | Priority | Notes |
|--------|--------|----------|-------|
| Cpanel::JSON::XS | Not implemented | Low | Use JSON instead |
| Mouse | Not implemented | Medium | Use Moo instead |
| Moose (XS parts) | Partial | Medium | Core works, some optimizations missing |

## Adding Java XS Implementations

To add a Java XS implementation for a module:

1. Create `src/main/java/org/perlonjava/runtime/perlmodule/ModuleName.java`
2. Extend `PerlModuleBase`
3. Add `public static final String XS_VERSION = "x.y"` for version checking
4. Implement the XS functions as static methods
5. Register methods in `initialize()`

Example structure:
```java
public class ModuleName extends PerlModuleBase {
    public static final String XS_VERSION = "1.00";
    
    public ModuleName() {
        super("Module::Name", false);
    }
    
    public static void initialize() {
        ModuleName module = new ModuleName();
        try {
            module.registerMethod("xs_function", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing method: " + e.getMessage());
        }
    }
    
    public static RuntimeList xs_function(RuntimeArray args, int ctx) {
        // Implementation
        return new RuntimeScalar(result).getList();
    }
}
```

## Testing XS Compatibility

```bash
# Check if module loads
./jperl -e 'use Module::Name; print "OK\n"'

# Check if using XS or PP
./jperl -e 'use Module::Name; print Module::Name->can("is_xs") ? "XS" : "PP"'

# Test with version
./jperl -e 'use XSLoader; XSLoader::load("Module::Name", "1.00")'
```

## See Also

- [Using CPAN Modules](../guides/using-cpan-modules.md) - Installing and using CPAN modules
- [Module Porting Guide](../guides/module-porting.md) - How to port Perl modules to PerlOnJava
- [Feature Matrix](feature-matrix.md) - Complete Perl feature compatibility
- [Architecture](architecture.md) - How PerlOnJava works internally
