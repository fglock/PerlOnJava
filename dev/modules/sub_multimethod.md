# Sub::MultiMethod Support for PerlOnJava

## Overview

`Sub::MultiMethod` 1.003 is a pure-Perl multimethod dispatcher that depends on
`Exporter::Tiny`, `Role::Hooks`, and `Type::Tiny`. This document tracks the
remaining work needed for a clean:

```bash
./jcpan -t Sub::MultiMethod
```

## Current Status

**Branch:** `fix/sub-multimethod-jcpan`  
**Module version:** Sub::MultiMethod 1.003  
**Status:** Blocked on `builtin::export_lexically` support in a separate PR  
**Last checked:** 2026-05-26

## Findings

### Clean `jcpan -t` failure

The first failing dependency is `Exporter::Tiny` 1.006003:

```text
Undefined subroutine &builtin::export_lexically called at .../Exporter/Tiny.pm line 138.
t/14lexical.t ............ Dubious, test returned 255
Result: FAIL
```

Because `Exporter::Tiny` is not installed after that failure, `Type::Tiny` cannot
compile, and `Sub::MultiMethod` later fails with missing dependency errors such as
`Eval/TypeTiny.pm`, `Types/Standard.pm`, and `Type/Library.pm`.

### Exploratory run past the blocker

To check for independent `Sub::MultiMethod` failures, the blocked dependencies
were installed locally without running their tests:

```bash
timeout 600 ./jcpan -Ti Exporter::Tiny Type::Tiny
timeout 900 ./jcpan -t Sub::MultiMethod
```

With `Exporter::Tiny` and `Type::Tiny` present, `Role::Hooks` passes its test
suite and `Sub::MultiMethod` itself passes:

```text
Role::Hooks: All tests successful. Files=13, Tests=12
Sub::MultiMethod: All tests successful. Files=23, Tests=135
```

## Next Steps

1. Merge the separate `builtin::export_lexically` implementation.
2. Re-test from a clean local CPAN state:
   ```bash
   timeout 900 ./jcpan -t Sub::MultiMethod > /tmp/sub_multimethod_after_builtin.log 2>&1
   ```
3. If `Exporter::Tiny` and `Type::Tiny` install cleanly, no additional
   `Sub::MultiMethod` fix is expected based on the exploratory run.

## Related

- `dev/modules/type_tiny.md`
- `dev/modules/pure-perl-exporter.md`
