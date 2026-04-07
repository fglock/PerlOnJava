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

**Current: 43/47 test files pass (91%), 430/440 subtests pass (97.7%)**

### Passing Tests (43/47)

astress, bare_glob_filehandle, cdata, combine_chars, current_byte,
current_length, debug_multibyte, deep_nesting, defaulted, element_decl,
encoding, error_hint, error_string, expat_version, extern_ent_lexical_glob,
external_ent, file, file_open_scalar, finish, g_void, get_base,
memory_leak_symtab, namespaces, nolwp, parament, parament_internal,
parse_error_context, parsefile_base_restore, partial, position_overflow,
security_api, skip, stream, stream_attr_escape, stream_localize, styles,
subs_inherited, tree_entity_expand, utf8_handling, utf8_stream, xml_escape,
xpcarp, xpcroak

### Failing Tests (4/47)

| Test | Failures | Category | Notes |
|------|----------|----------|-------|
| checklib_findcc.t | 1/3 | Not XML::Parser | Devel::CheckLib stub, no real C compiler check |
| checklib_tmpdir.t | 2/3 | Not XML::Parser | Devel::CheckLib stub, no File::Temp check |
| decl.t | 0/44 pass, 2 incomplete | External DTD | 44 tests pass; 2 remaining tests unknown |
| foreign_dtd.t | 0/5 (4 ran) | External DTD | Requires UseForeignDTD feature (not implemented) |

## TODO: Remaining Issues

### Phase 4: Encoding Conversion

**Status**: Completed (2026-04-07)
**Tests fixed**: encoding.t (0→43/43), parament.t (1/4→13/13)

#### Implementation

Added encoding conversion utilities to `XMLParserExpat.java`:

1. **`ENCODING_MAP`**: Maps expat-specific encoding names to JDK charsets (`x-sjis-unicode` → `Shift_JIS`, `x-euc-jp-unicode` → `EUC-JP`)
2. **`extractDeclaredEncoding()`**: Scans first 200 bytes of input for `<?xml ... encoding="..."?>` declaration
3. **`convertEncoding()`**: Decodes bytes with correct charset, re-encodes as UTF-8, replaces encoding declaration
4. **`mapToJdkCharset()`**: Maps encoding names via ENCODING_MAP, falls back to JDK charset lookup

Applied `convertEncoding()` in all input paths:
- `ParseString`, `ParseStream`, `ParseDone` — document parsing
- `resolveEntity()` — external DTD/entity content (both filehandle and string paths)
- `doParse()` — ProtocolEncoding via `mapToJdkCharset()`

#### Additional Fix: Tail Call Trampoline

Fixed `RuntimeCode.apply(RuntimeScalar, RuntimeArray, int)` to handle `goto &func` tail calls. XML::Parser's `initial_ext_ent_handler` uses `goto &func`, which returned a `RuntimeControlFlowList` with TAILCALL marker that wasn't being resolved. Added a trampoline loop to follow tail calls to completion.

### UseForeignDTD

**Status**: Not implemented
**Tests affected**: foreign_dtd.t (5 tests)

Expat's `XML_UseForeignDTD()` triggers the `ExternalEntityRef` handler even for documents without a DOCTYPE. This allows injecting a DTD dynamically. JDK SAX has no equivalent API.

### Devel::CheckLib Stubs

**Status**: Not XML::Parser related
**Tests affected**: checklib_findcc.t (1 test), checklib_tmpdir.t (2 tests)

These tests check C compiler detection and temp directory handling from Devel::CheckLib, which is not relevant to the Java XS implementation.

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

- [x] Batch 3 fixes (2025-04-07)
  - Stream delimiter parsing (readline-based, respecting $/)
  - Self-closing tag detection (inputBytes scanning for `/>`)
  - Entity expansion tracking (startEntity/endEntity → original_string)
  - ExternEntFin handler for string returns
  - Element index stack (push/pop for start/end consistency)
  - ProtocolEncoding (stored and applied to InputSource)
  - PositionContext implementation (surrounding lines + linepos)
  - ParseParamEnt conditional SAX feature flags
  - Entity resolver systemId preservation for relative URI resolution
  - Context pop order (after end handler, matching libexpat)
  - Self-closing tag column in endElement (empty recognizedString)

- [x] Phase 4: Encoding Conversion (2026-04-07)
  - Encoding name mapping (x-sjis-unicode → Shift_JIS, x-euc-jp-unicode → EUC-JP)
  - Pre-parse encoding detection and byte re-encoding to UTF-8
  - Applied in ParseString, ParseStream, ParseDone, resolveEntity, doParse
  - Tail call trampoline fix in RuntimeCode.apply() for goto &func
  - Files: XMLParserExpat.java, RuntimeCode.java

### Remaining Limitations

1. UseForeignDTD — no SAX equivalent
2. Devel::CheckLib tests — not XML-related
3. decl.t 2 incomplete tests — unknown cause
