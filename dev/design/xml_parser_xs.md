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

**Current: 35/47 test files pass (74%), 365/385 subtests pass (95%)**

### Passing Tests (35/47)

bare_glob_filehandle, cdata, combine_chars, current_byte, current_length,
debug_multibyte, deep_nesting, defaulted, element_decl, error_hint,
error_string, expat_version, extern_ent_lexical_glob, external_ent, file,
file_open_scalar, finish, get_base, memory_leak_symtab, namespaces, nolwp,
parse_error_context, parsefile_base_restore, security_api, skip,
stream_attr_escape, stream_localize, styles, subs_inherited,
tree_entity_expand, utf8_handling, utf8_stream, xml_escape, xpcarp, xpcroak

### Failing Tests (12/47)

| Test | Failures | Category | Notes |
|------|----------|----------|-------|
| astress.t | 5/29 | External entities | Ext ent resolution, position_in_context, element_index |
| checklib_findcc.t | 1/3 | Not XML::Parser | Devel::CheckLib stub, no real C compiler check |
| checklib_tmpdir.t | 2/3 | Not XML::Parser | Devel::CheckLib stub, no File::Temp check |
| decl.t | 2/46 | Custom encoding | x-sjis-unicode encoding not supported by JDK SAX; 44/44 subtests pass |
| encoding.t | 0/crash | Custom encoding | Custom encoding map registration not supported |
| foreign_dtd.t | 5/5 | External DTD | Requires external DTD loading / UseForeignDTD |
| g_void.t | 1/35 | External entities | ExternEntFin handler not called |
| parament.t | 5/10 | Parameter entities | PE resolution in document body |
| parament_internal.t | 2/crash | External entities | common.txt external entity file not found |
| partial.t | 1/3 | original_string | SAX expands entities; no access to unexpanded text |
| position_overflow.t | 1/9 | Self-closing tags | Column off by 1 for `<tag/>` (see TODO below) |
| stream.t | 2/3 | Stream delimiter | Resumable stream parsing with delimiter not implemented |

## TODO: Items to Fix

### SAX Limitation: Self-Closing Tag Column Recognition

**Status**: To be fixed
**Test**: position_overflow.t test 9
**Problem**: For self-closing tags like `<child2/>`, `current_column` returns 3 instead of expected 2.

**Root cause**: In `startElement()`, the `recognizedString` is built as `<child2>` (8 chars) but the actual XML token is `<child2/>` (10 chars). The column calculation in `GetCurrentColumnNumber()` subtracts `recognizedString.length()` from the SAX locator's post-token 1-based column to get expat's pre-token 0-based column:

```java
int col = state.locator.getColumnNumber() - 1;      // e.g. 12 - 1 = 11
col -= state.recognizedString.length();               // 11 - 8 = 3 (wrong)
// Should be: 11 - 10 = 1... wait, expected is 2
```

SAX does not distinguish self-closing tags (`<foo/>`) from empty elements (`<foo></foo>`) — both fire `startElement` + `endElement`. The recognizedString omits the `/` character.

**Proposed fix options**:
1. **Check input bytes**: In `startElement()`, look back in `inputBytes` from the locator position to detect if `/>` closed the tag, and if so append `/` to recognizedString
2. **Compare locator positions**: If `endElement` fires at the same line/column as `startElement` ended, infer it was self-closing
3. **Scan the raw input**: Use `inputBytes` and `inputScanPos` to find the actual tag text from the source

### External Entity Resolution Architecture

**Status**: Known limitation
**Tests affected**: astress.t, g_void.t, parament.t, parament_internal.t, foreign_dtd.t

SAX's `resolveEntity()` fundamentally differs from expat's `externalEntityRef`:
- Expat: handler returns a sub-parser that processes the entity content and merges events into the main parse
- SAX: `resolveEntity()` returns an `InputSource` and SAX processes it internally

This means:
- General entity resolution in document body doesn't trigger `resolveEntity` the same way
- `ExternEntFin` handler cannot be called (no sub-parser lifecycle)
- Parameter entity resolution differs between internal/external DTD subsets

### Custom Encoding Registration

**Status**: Known limitation (JDK SAX limitation)
**Tests affected**: encoding.t, decl.t (2 tests)

Expat supports custom encoding maps via `XML_SetUnknownEncodingHandler`. JDK's SAX parser only supports encodings built into the JDK. Custom encodings like `x-sjis-unicode` cannot be registered.

### Stream Delimiter Resumable Parsing

**Status**: Known limitation
**Tests affected**: stream.t (2 tests)

The current `ParseStream` reads the entire IO handle into a byte array and parses it all at once. Expat supports reading line-by-line, stopping at a delimiter, and resuming from the same filehandle position. This requires restructuring ParseStream to read incrementally.

### `original_string` for Expanded Entities

**Status**: Known limitation
**Tests affected**: partial.t (1 test)

SAX always returns expanded entity values. There's no way to get the unexpanded original text (e.g., `&draft.day;` instead of `10`). Would require pre-processing the XML to track entity reference positions.

## Progress Tracking

### Completed

- [x] Initial SAX-backed implementation (2025-04-06)
  - All core handlers: Start, End, Char, Comment, PI, CDATA, Default
  - DTD handlers: Entity, Element, Attlist, Notation, Unparsed, XMLDecl, Doctype
  - Namespace support with dualvar names
  - Position tracking (line, column, byte)
  - MakeMaker integration for Style module installation

- [x] Batch 2 fixes (2025-04-07)
  - UTF-8 double-encoding fix (BYTE_STRING → ISO_8859_1)
  - Specified vs defaulted attributes (Attributes2.isSpecified)
  - Error message format ("not well-formed" + hints)
  - SystemId un-resolution (parseBaseUri tracking)
  - String interpolation `${$ref}{key}` parser fix
  - IO handle class detection (GLOB → IO::Handle)
  - MakeMaker BASEEXT directory scanning

### Next Steps

1. Fix self-closing tag column recognition (position_overflow.t test 9)
2. Investigate stream delimiter resumable parsing
3. Consider external entity architecture improvements
