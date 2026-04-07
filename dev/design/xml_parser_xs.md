# XML::Parser Java XS Implementation Plan

## Overview

XML::Parser is implemented as a Java XS module (`XMLParserExpat.java`) backed by JDK's built-in SAX parser (`javax.xml.parsers.SAXParser`). This replaces the native C/XS expat bindings with a pure-Java equivalent, dispatching SAX events to the same Perl callback interface.

## Architecture

- **Java XS**: `src/main/java/org/perlonjava/runtime/perlmodule/XMLParserExpat.java`
- **Perl shim**: `src/main/perl/lib/XML/Parser/Expat.pm` (modified from upstream)
- **Backend**: JDK SAX (Apache Xerces built into the JDK)

### Key Design Decisions

1. **SAX vs DOM**: SAX chosen for streaming event model that maps naturally to expat's callback API
2. **Namespace dualvars**: Namespace-qualified names use `DualVar(numericIndex, stringName)` matching expat's behavior where `int($name)` gives namespace index
3. **BYTE_STRING encoding**: ParseString uses `ISO_8859_1` for `BYTE_STRING` input to avoid double-encoding raw UTF-8 bytes
4. **SystemId un-resolution**: SAX resolves relative systemIds to absolute `file:///` URIs; `unresolveSysId()` strips the base to recover the original relative paths

## Test Status

**Current: 45/45 test files pass (100%), 434/434 subtests pass (100%)**

All XML::Parser 2.56 tests pass (excluding 2 `Devel::CheckLib` C compiler detection tests that are irrelevant to PerlOnJava).

### Running Tests

Tests are stored in `src/test/resources/module/XML-Parser/` and run via:

```bash
make test-bundled-modules
```

This uses JUnit 5 (`ModuleTestExecutionTest.java`) with `@Tag("module")` to discover and execute all `.t` files under `module/*/t/`. The test runner `chdir`s to the module directory so relative paths resolve correctly.

## Completed Phases

### Phase 5: Final fixes for 47/47 (2026-04-07)

**Tests fixed**: decl.t (44/46→46/46), foreign_dtd.t (0/5→5/5), checklib_findcc.t (2/3→3/3), checklib_tmpdir.t (1/3→3/3)

1. **NOTATION type format fix**: Off-by-one bug in `attributeDecl()` — `substring(8)` → `substring(9)` to strip the space SAX adds after `NOTATION`
2. **XMLDecl for text declarations**: Added `fireTextDeclHandler()` in `resolveEntity()` to fire the XMLDecl callback for text declarations in external parsed entities (with `version=undef`), before `convertEncoding()` rewrites the encoding
3. **UseForeignDTD**: When `UseForeignDTD => 1` and no DOCTYPE exists, calls ExternEnt handler with `(parser, base, undef, undef)`, reads DTD content, injects `<!DOCTYPE _fdt SYSTEM "__perlonjava_foreign_dtd__">` after the XML declaration, and resolves the synthetic system ID in `resolveEntity()`
4. **"undefined entity" error message**: SAX reports `"was referenced, but not declared"` for undefined entities; mapped to expat's `"undefined entity"` format in `formatError()`
5. **Devel::CheckLib**: Replaced 9-line stub with real upstream source from XML-Parser-2.56 tarball

Files: XMLParserExpat.java
