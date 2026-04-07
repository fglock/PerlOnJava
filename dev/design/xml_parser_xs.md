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

### Phase 4: Encoding Conversion

**Status**: Planned
**Tests affected**: encoding.t (all tests), decl.t (2 incomplete), parament.t (9 incomplete)

#### Problem

JDK SAX rejects unknown encoding names like `x-sjis-unicode` (an expat-specific alias for Shift_JIS). This affects three areas:

1. **Document parsing** (`ParseString`/`ParseStream`): When `<?xml encoding="x-sjis-unicode"?>` appears in the document, SAX throws an unsupported encoding error.
2. **External entity resolution** (`resolveEntity`): When an external DTD like `foo.dtd` starts with `<?xml encoding="x-sjis-unicode"?>`, SAX fails while parsing the entity content.
3. **ProtocolEncoding**: When `ProtocolEncoding => 'X-SJIS-UNICODE'` is passed without an XML declaration.

#### Analysis of encoding.t

The test covers 11 encoding groups. Most are standard encodings that JDK already supports:

| Encoding | JDK Charset | Status |
|----------|------------|--------|
| `x-sjis-unicode` | `Shift_JIS` | **Needs mapping** |
| `WINDOWS-1252` | `windows-1252` | JDK supports |
| `windows-1251` | `windows-1251` | JDK supports |
| `koi8-r` | `KOI8-R` | JDK supports |
| `windows-1255` | `windows-1255` | JDK supports |
| `ibm866` | `IBM866` | JDK supports |
| `iso-8859-2` | `ISO-8859-2` | JDK supports |
| `iso-8859-5` | `ISO-8859-5` | JDK supports |
| `iso-8859-9` | `ISO-8859-9` | JDK supports |
| `iso-8859-15` | `ISO-8859-15` | JDK supports |
| `windows-1250` | `windows-1250` | JDK supports |

The test crashes on the first case (`x-sjis-unicode`) and never reaches the standard cases.

#### Analysis of parament.t / decl.t

`t/foo.dtd` starts with `<?xml encoding="x-sjis-unicode"?>` and contains SJIS-encoded entity values (e.g., `<!ENTITY joy "\x99\x44">` where bytes `0x99 0x44` map to U+50D6 in Shift_JIS). When `ParseParamEnt => 1` loads this DTD, SAX fails on the unsupported encoding.

#### Implementation Plan

**Step 1: Pre-parse encoding detection and byte re-encoding**

Before feeding bytes to SAX, scan for `<?xml ... encoding="..."?>` in the raw input. If the declared encoding is not directly supported by JDK, map it to a known Java charset and re-encode the bytes as UTF-8:

```java
// In doParse() and resolveEntity(), before creating InputSource:
private static byte[] convertEncoding(byte[] input) {
    String declared = extractDeclaredEncoding(input);  // parse <?xml encoding="...">
    if (declared == null) return input;

    // Map expat-specific encoding names to Java charsets
    String javaCharset = mapEncodingName(declared);
    if (javaCharset == null) return input;  // let SAX handle it

    // Decode with the correct charset, re-encode as UTF-8,
    // and replace the encoding declaration
    String content = new String(input, Charset.forName(javaCharset));
    content = content.replaceFirst(
        "encoding=['\"]" + Pattern.quote(declared) + "['\"]",
        "encoding='UTF-8'");
    return content.getBytes(StandardCharsets.UTF_8);
}
```

**Step 2: Encoding name mapping table**

Build a static mapping of expat-specific encoding names to Java charset names:

```java
private static final Map<String, String> ENCODING_MAP = Map.of(
    "x-sjis-unicode", "Shift_JIS",
    "x-euc-jp-unicode", "EUC-JP"
    // Add other expat-specific names as needed
);

private static String mapEncodingName(String encoding) {
    // First check our custom map
    String mapped = ENCODING_MAP.get(encoding.toLowerCase());
    if (mapped != null) return mapped;
    // Then check if JDK supports it directly
    try {
        Charset.forName(encoding);
        return null;  // JDK handles it natively
    } catch (Exception e) {
        return null;  // truly unknown, let SAX report the error
    }
}
```

**Step 3: Apply in all input paths**

Apply `convertEncoding()` in three places:
1. `ParseString` — for document strings with non-UTF-8 encodings
2. `ParseStream` — for streamed content
3. `resolveEntity` — for external DTD/entity content returned by the ExternEnt handler

**Step 4: ProtocolEncoding without XML declaration**

When `ProtocolEncoding` is set and the input has no `<?xml?>` declaration, prepend a synthetic declaration or use `InputSource.setEncoding()` with the mapped charset name.

#### Expected Results

- encoding.t: All tests should pass (standard encodings already work in JDK; x-sjis-unicode gets mapped to Shift_JIS)
- parament.t: foo.dtd loads successfully, enabling entity expansion and ATTLIST processing (~10 more tests)
- decl.t: External DTD text declaration processed, enabling 2 more tests

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
