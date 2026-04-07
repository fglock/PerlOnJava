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

**Current: 41/47 test files pass (87%), 377/397 subtests pass (95%)**

### Passing Tests (41/47)

astress, bare_glob_filehandle, cdata, combine_chars, current_byte,
current_length, debug_multibyte, deep_nesting, defaulted, element_decl,
error_hint, error_string, expat_version, extern_ent_lexical_glob,
external_ent, file, file_open_scalar, finish, g_void, get_base,
memory_leak_symtab, namespaces, nolwp, parament_internal,
parse_error_context, parsefile_base_restore, partial, position_overflow,
security_api, skip, stream, stream_attr_escape, stream_localize, styles,
subs_inherited, tree_entity_expand, utf8_handling, utf8_stream, xml_escape,
xpcarp, xpcroak

### Failing Tests (6/47)

| Test | Failures | Category | Notes |
|------|----------|----------|-------|
| checklib_findcc.t | 1/3 | Not XML::Parser | Devel::CheckLib stub, no real C compiler check |
| checklib_tmpdir.t | 2/3 | Not XML::Parser | Devel::CheckLib stub, no File::Temp check |
| decl.t | 0/44 pass, 2 incomplete | Custom encoding | x-sjis-unicode text declaration; all 44 subtests pass |
| encoding.t | 0/crash | Custom encoding | Custom encoding map registration not supported |
| foreign_dtd.t | 0/5 (4 ran) | External DTD | Requires UseForeignDTD feature (not implemented) |
| parament.t | 1/4 fail, 9 incomplete | Custom encoding | x-sjis-unicode in foo.dtd crashes SAX parser |

## TODO: Remaining Issues

### Custom Encoding Registration

**Status**: Known limitation (JDK SAX limitation)
**Tests affected**: encoding.t, decl.t (2 incomplete), parament.t (9 incomplete)

Expat supports custom encoding maps via `XML_SetUnknownEncodingHandler`. JDK's SAX parser only supports encodings built into the JDK. Custom encodings like `x-sjis-unicode` cannot be registered. The `foo.dtd` test file uses this encoding, causing SAX parse errors when `ParseParamEnt` is enabled and the DTD is loaded.

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

### Remaining Limitations

1. Custom encoding support (x-sjis-unicode) — JDK limitation
2. UseForeignDTD — no SAX equivalent
3. Devel::CheckLib tests — not XML-related
