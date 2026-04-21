# Module Porting Documentation

This directory contains design documents and guides related to porting CPAN modules to PerlOnJava.

## Quick Reference

| Document | Description |
|----------|-------------|
| [moose_support.md](moose_support.md) | Path to Moose/Class::MOP support (blocked by B module) |
| [moo_support.md](moo_support.md) | Moo support status (96% working) |
| [xs_fallback.md](xs_fallback.md) | XS fallback mechanism for pure Perl modules |
| [xsloader.md](xsloader.md) | XSLoader architecture |
| [makemaker_perlonjava.md](makemaker_perlonjava.md) | ExtUtils::MakeMaker implementation |
| [cpan_client.md](cpan_client.md) | jcpan - CPAN client for PerlOnJava |
| [dbix_class.md](dbix_class.md) | DBIx::Class support (in progress) |
| [math_bigint_bignum.md](math_bigint_bignum.md) | Math::BigInt / BigFloat / BigRat / bignum support (in progress) |

## Module Status Overview

### Working Modules

| Module | Status | Notes |
|--------|--------|-------|
| **Moo** | 96% tests pass | Recommended OO system |
| **DateTime** | Works | Java XS implementation |
| **JSON::PP** | Works | Built-in |
| **YAML** | Works | Pure Perl |
| **Try::Tiny** | Works | Pure Perl |
| **Test::More** | Works | Built-in |
| **DBI** | Works | Java implementation |

### Modules Requiring Environment Variables

| Module | Environment Variable | Notes |
|--------|---------------------|-------|
| Params::Util | `PERL_PARAMS_UTIL_PP=1` | Has PP fallback |
| Class::Load | Works after Params::Util | Has PP fallback |
| Package::Stash | Auto-fallback | Has PP fallback |

### Not Yet Working

| Module | Blocker | See |
|--------|---------|-----|
| Moose | B module subroutine names | [moose_support.md](moose_support.md) |
| Any XS-only module | No compiler | [xs_fallback.md](xs_fallback.md) |

## Key Concepts

### XS Fallback Pattern

Many CPAN modules with XS code also provide pure Perl fallbacks:

```perl
# Common pattern in modules
eval {
    require XSLoader;
    XSLoader::load(__PACKAGE__, $VERSION);
};
if ($@) {
    require Module::PP;  # Pure Perl fallback
}
```

PerlOnJava's XSLoader returns an error matching `/loadable object/` which these modules recognize.

### Java XS Implementations

For performance-critical modules, PerlOnJava can provide Java implementations:

- `DateTime` - Uses `java.time` APIs
- `JSON::XS` - Falls back to JSON::PP (or could use FASTJSON)
- `DBI` - Custom Java implementation with JDBC

See [xs_fallback.md](xs_fallback.md) for implementation details.

### Installing Modules

Use `jcpan` for module installation:

```bash
# Install a module
./jcpan install DateTime

# Test a module
./jcpan -t Moo

# With PP environment variables
PERL_PARAMS_UTIL_PP=1 ./jcpan -t Class::Load
```

## Adding Support for New Modules

1. **Check if module has XS**: Look for `.xs` files in the distribution
2. **Check for PP fallback**: Look for `*::PP` modules or fallback patterns
3. **Test installation**: `./jcpan -t ModuleName`
4. **Document blockers**: Create/update design doc if issues found
5. **Consider Java XS**: For critical modules, implement in Java

## Related Resources

- [AGENTS.md](../../AGENTS.md) - Project guidelines
- [docs/guides/module-porting.md](../../docs/guides/module-porting.md) - User-facing porting guide
- `.agents/skills/port-cpan-module/` - AI skill for porting modules

## Document Index

### Core Infrastructure
- [xsloader.md](xsloader.md) - XSLoader implementation
- [dynaloader.md](dynaloader.md) - DynaLoader architecture
- [dynamic_loading.md](dynamic_loading.md) - Dynamic module loading
- [makemaker_perlonjava.md](makemaker_perlonjava.md) - MakeMaker for PerlOnJava
- [cpan_client.md](cpan_client.md) - jcpan CPAN client

### XS and Fallbacks
- [xs_fallback.md](xs_fallback.md) - XS fallback mechanism
- [pure-perl-exporter.md](pure-perl-exporter.md) - Pure Perl Exporter

### CPAN Compatibility
- [cpan_patch_plan.md](cpan_patch_plan.md) - Strategy for patching CPAN modules for JVM compatibility

### Specific Modules
- [moose_support.md](moose_support.md) - Moose support (in progress)
- [moo_support.md](moo_support.md) - Moo support (working)
- [JCPAN_DATETIME_FIXES.md](JCPAN_DATETIME_FIXES.md) - DateTime via jcpan
- [dbix_class.md](dbix_class.md) - DBIx::Class support
- [log4perl-compatibility.md](log4perl-compatibility.md) - Log::Log4perl
- [term_readkey.md](term_readkey.md) - Term::ReadKey
- [xml_parser.md](xml_parser.md) - XML::Parser (Java XS via JDK SAX)
