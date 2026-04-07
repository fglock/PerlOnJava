# XML::Parser Support for PerlOnJava

> **Active plan and progress tracking**: See [`dev/design/xml_parser_xs.md`](../design/xml_parser_xs.md)
>
> This document contains the original architecture and reference material.
> For current status, TODOs, and implementation progress, use the design doc.

## Overview

**Module**: XML::Parser 2.56 (depends on XML::Parser::Expat XS backend)  
**Test command**: `./jcpan --jobs 8 -t use XML::Parser`  
**Status**: 41/47 test files pass (95%)  
**Branch**: `feature/xml-parser`

## Problem Statement

XML::Parser is one of the most widely-used CPAN XML modules. It's a required dependency for
XML::SAX::Expat, XML::Twig, XML::RSS, SVG::Parser, and many other modules. It's also an
optional dependency of XML::Simple (whose `t/A_XMLParser.t` and `t/C_External_Entities.t` tests
currently SKIP because XML::Parser is missing).

The module currently fails to install via jcpan because:

1. **Makefile.PL uses `Devel::CheckLib`** to verify that `libexpat` (a C library) is available.
   This check runs `check_lib(lib => ['expat'], header => ['expat.h'])` which tries to compile
   a C test program — this fails under PerlOnJava since there's no C compiler integration.
2. Even if Makefile.PL succeeded, **Expat.xs cannot be compiled** — PerlOnJava runs on the JVM
   and cannot load native `.so`/`.dylib` objects.

## Solution: Java XS Implementation

Implement `XML::Parser::Expat` as a **Java XS class** (`XMLParserExpat.java`) using the JDK's
built-in `javax.xml.parsers.SAXParser` as the XML parsing engine. This follows the established
pattern of `HTMLParser.java`, `DateTime.java`, `DigestMD5.java`, etc.

**No new Maven/Gradle dependencies required** — Java's SAX parser is part of the JDK standard
library (`java.xml` module).

### Why JDK SAX and not libexpat?

- PerlOnJava cannot load native libraries (no JNI/FFM for expat)
- JDK SAX provides event-based parsing identical in concept to expat
- Zero external dependencies — the project already follows this pattern for DateTime (`java.time`),
  Digest::MD5 (`java.security.MessageDigest`), HTML::Parser (Jsoup), etc.
- JDK SAX supports all core expat features: elements, attributes, characters, PIs, comments,
  CDATA sections, DTD declarations, namespace processing, external entities

## Dependency Tree

```
XML::Parser 2.56
├── XML::Parser::Expat (XS → Java XS implementation)
│   ├── XSLoader (loads XMLParserExpat.java)
│   ├── File::Spec (bundled)
│   └── File::ShareDir (CPAN, for encoding maps)
├── XML::Parser::Style::Debug (pure Perl, bundled in CPAN dist)
├── XML::Parser::Style::Subs (pure Perl)
├── XML::Parser::Style::Tree (pure Perl)
├── XML::Parser::Style::Objects (pure Perl)
├── XML::Parser::Style::Stream (pure Perl)
├── XML::Parser::ContentModel (pure Perl, in Expat.pm)
├── XML::Parser::ExpatNB (pure Perl, in Expat.pm)
└── LWP::UserAgent (optional, for external entity fetching)
```

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────┐
│  Perl layer (from CPAN, installed by jcpan) │
│  ├── XML::Parser (Parser.pm)                │
│  ├── XML::Parser::Style::* (pure Perl)      │
│  └── XML::Parser::ExpatNB (in Expat.pm)     │
├─────────────────────────────────────────────┤
│  Perl shim (bundled in jar:PERL5LIB)        │
│  └── XML/Parser/Expat.pm                    │
│      - Loads Java XS via XSLoader           │
│      - Pure Perl methods: setHandlers,      │
│        context, namespace methods, etc.      │
│      - Delegates XS calls to Java           │
├─────────────────────────────────────────────┤
│  Java XS (XMLParserExpat.java)              │
│  └── Implements XS functions:               │
│      ParserCreate, ParseString, ParseStream │
│      Set*Handler, Get*Position, etc.        │
│      Uses javax.xml.parsers.SAXParser       │
└─────────────────────────────────────────────┘
```

### Key Design Decisions

1. **Reuse CPAN's Parser.pm**: The high-level `XML::Parser` module is pure Perl. We install it
   from CPAN and only replace the XS backend (`XML::Parser::Expat`).

2. **Bundled Expat.pm shim**: We provide our own `XML/Parser/Expat.pm` in `jar:PERL5LIB` that:
   - Calls `XSLoader::load('XML::Parser::Expat')` to load `XMLParserExpat.java`
   - Contains all the pure Perl methods from the original Expat.pm (context tracking, namespace
     methods, xml_escape, etc.)
   - Delegates XS-only functions (ParserCreate, ParseString, etc.) to the Java class

3. **Opaque parser handle**: The `{Parser}` field in the Expat object stores a Java `SAXParser`
   wrapper object (as `RuntimeScalarType.JAVAOBJECT`), similar to how DigestMD5 stores
   `MessageDigest`.

4. **Callback dispatch**: The Java SAX `ContentHandler`/`LexicalHandler`/`DTDHandler` methods
   invoke Perl handler coderefs stored in the Expat hash, using `RuntimeCode.apply()`.

## XS Function Mapping

### Tier 1 — Core (required for basic XML::Parser usage)

| XS Function | Java SAX Backend | Notes |
|---|---|---|
| `ParserCreate(self, enc, ns)` | `SAXParserFactory.newInstance()` | Store parser in `$self->{Parser}` as JAVAOBJECT |
| `ParseString(parser, string)` | `parser.parse(InputSource)` | Convert string to `InputSource` |
| `ParseStream(parser, ioref, delim)` | `parser.parse(InputStream)` | Read from Perl IO handle; Stream_Delimiter support |
| `SetStartElementHandler` | `ContentHandler.startElement()` | Dispatch to Perl `Start` handler |
| `SetEndElementHandler` | `ContentHandler.endElement()` | Dispatch to Perl `End` handler |
| `SetCharacterDataHandler` | `ContentHandler.characters()` | Dispatch to Perl `Char` handler |
| `SetProcessingInstructionHandler` | `ContentHandler.processingInstruction()` | Dispatch to Perl `Proc` handler |
| `SetCommentHandler` | `LexicalHandler.comment()` | Dispatch to Perl `Comment` handler |
| `SetStartCdataHandler` | `LexicalHandler.startCDATA()` | Dispatch to Perl `CdataStart` handler |
| `SetEndCdataHandler` | `LexicalHandler.endCDATA()` | Dispatch to Perl `CdataEnd` handler |
| `SetDefaultHandler` | Custom tracking | Catch-all for unhandled events |
| `SetXMLDeclHandler` | Custom prolog detection | Parse `<?xml?>` prolog manually or via SAX property |
| `GetCurrentLineNumber` | `Locator.getLineNumber()` | SAX Locator |
| `GetCurrentColumnNumber` | `Locator.getColumnNumber()` | SAX Locator |
| `SetBase` / `GetBase` | Field on Java wrapper | Simple string get/set |
| `ParserRelease` / `ParserFree` | Clear references | No native memory to free |
| `UnsetAllHandlers` | Clear all handler SVs | Used by `finish()` |

### Tier 2 — DTD Features (required for ExifTool, XML::SAX::Expat)

| XS Function | Java SAX Backend | Notes |
|---|---|---|
| `SetUnparsedEntityDeclHandler` | `DTDHandler.unparsedEntityDecl()` | |
| `SetNotationDeclHandler` | `DTDHandler.notationDecl()` | |
| `SetExternalEntityRefHandler` | `EntityResolver.resolveEntity()` | Map to Perl's ExternEnt handler |
| `SetExtEntFinishHandler` | Post-entity callback | |
| `SetEntityDeclHandler` | `DeclHandler.internalEntityDecl()` / `externalEntityDecl()` | |
| `SetElementDeclHandler` | `DeclHandler.elementDecl()` | Return ContentModel object |
| `SetAttListDeclHandler` | `DeclHandler.attributeDecl()` | |
| `SetDoctypeHandler` | `LexicalHandler.startDTD()` | |
| `SetEndDoctypeHandler` | `LexicalHandler.endDTD()` | |
| `GetSpecifiedAttributeCount` | Track in `startElement` | |
| `ElementIndex` | Depth-first counter | |

### Tier 3 — Advanced / Incremental Parsing

| XS Function | Java SAX Backend | Notes |
|---|---|---|
| `ParsePartial` | Chunked `InputSource` | For ExpatNB `parse_more()` |
| `ParseDone` | Signal end-of-stream | |
| `GetCurrentByteIndex` | Approximate via char counting | SAX Locator lacks byte offset |
| `GetCurrentByteCount` | Approximate or stub | |
| `RecognizedString` | Reconstruct from events | Not directly available in SAX |
| `OriginalString` | Reconstruct or stub | Not directly available in SAX |
| `PositionContext` | Track input buffer | Reconstruct context around current position |
| `DefaultCurrent` | Re-fire to default handler | |
| `SkipUntil` | Suppress callbacks until index | |
| `GenerateNSName` | Perl-level implementation | Already in Expat.pm |
| `LoadEncoding` / `FreeEncoding` | Stub / no-op | Java handles encodings natively |
| `ExpatVersion` / `ExpatVersionInfo` | Return synthetic values | e.g., `"PerlOnJava SAX/1.0"` |
| `ErrorString` | Map SAX exception messages | |
| Security methods | No-op stubs | Java SAX has its own security model |

## Expat.pm Shim Design

The bundled `XML/Parser/Expat.pm` in `jar:PERL5LIB` replaces the CPAN version. It contains:

1. **All pure Perl code from the original Expat.pm** — namespace methods, context tracking,
   `xml_escape()`, `ContentModel` package, `ExpatNB` package, `Encinfo` package
2. **`XSLoader::load('XML::Parser::Expat')`** instead of native XS loading
3. **Adjusted `%Handler_Setters`** — maps handler type names to Java-backed setter functions
   registered by `XMLParserExpat.java`

The Perl methods that wrap XS calls (`parse`, `current_line`, `base`, etc.) work unchanged
because they delegate to the Java-registered functions through the same calling convention.

## MakeMaker Integration

### Problem
XML::Parser's `Makefile.PL` uses `Devel::CheckLib` (bundled in `./inc/`) to verify libexpat:

```perl
use lib './inc';
use Devel::CheckLib;
unless (check_lib(lib => ['expat'], header => ['expat.h'], ...)) {
    warn "Expat must be installed...";
    exit 0;   # ← exits BEFORE WriteMakefile() is called
}
WriteMakefile1( NAME => 'XML::Parser', DIR => ['Expat'], ... );
```

Because `exit 0` happens before `WriteMakefile()`, PerlOnJava's custom MakeMaker never runs,
no `Makefile` is generated, and CPAN::Distribution aborts with "No 'Makefile' created".

### Solution: Two-layer approach (Strategy D)

**Layer 1 — Stub `Devel::CheckLib` in build directory** (`CPAN/Distribution.pm`):

Before running `Makefile.PL`, detect `./inc/Devel/CheckLib.pm` in the build directory and
replace it with a PerlOnJava stub that always succeeds:

```perl
package Devel::CheckLib;
use Exporter; our @ISA = ('Exporter');
our @EXPORT = qw(assert_lib check_lib_or_exit check_lib);
sub assert_lib { 1 }
sub check_lib_or_exit { 1 }
sub check_lib { 1 }
1;
```

This lets `Makefile.PL` proceed to `WriteMakefile()`, where PerlOnJava's custom MakeMaker
detects XS files and installs `.pm` files via `_handle_xs_module()`.

**Layer 2 — Fallback Makefile.PL generation** (`CPAN/Distribution.pm`):

As a safety net, when `Makefile.PL` exits 0 but no `Makefile` is created, generate a
synthetic `Makefile.PL` from `META.yml`/`META.json` metadata and re-run it. This catches
any module that dies/exits before `WriteMakefile()` regardless of the reason.

### Additional complications

- **Non-standard layout**: `Parser.pm` lives at the distribution root, not in `lib/`.
  MakeMaker's `_install_pure_perl()` must handle this (it already scans for `.pm` files
  at the root for flat-layout dists).
- **Subdirectory build**: `DIR => ['Expat']` causes recursion into `Expat/Makefile.PL`,
  which also calls `Devel::CheckLib`. The stub handles this automatically.
- **File::ShareDir::Install**: Uses `install_share dist => 'share'` for encoding `.enc`
  files. These can be installed but are unused (Java handles encodings natively).
- **CPAN `Expat/Expat.pm` vs JAR shim**: Our `jar:PERL5LIB` Expat.pm shim takes
  precedence over the CPAN-installed version because MakeMaker's JAR-shim deduplication
  (lines 269-281) skips `.pm` files that already exist in `jar:PERL5LIB`.

## Test Suite Analysis

XML::Parser 2.56 has **47 test files**. Expected results by category:

### Expected to Pass (with Java SAX backend)

| Category | Test Files | Count | Notes |
|---|---|---|---|
| Core parsing | `styles.t`, `cdata.t`, `file.t`, `stream.t`, `partial.t` | 5 | Basic parse/style tests |
| Handlers | `decl.t`, `namespaces.t`, `skip.t`, `finish.t` | 4 | Handler dispatch |
| DTD | `parament.t`, `parament_internal.t`, `foreign_dtd.t` | 3 | DTD processing |
| Error handling | `xpcroak.t`, `xpcarp.t`, `parse_error_context.t`, `error_string.t`, `error_hint.t` | 5 | Error reporting |
| External entities | `external_ent.t`, `extern_ent_lexical_glob.t`, `nolwp.t`, `get_base.t` | 4 | Entity resolution |
| UTF-8 | `utf8_handling.t`, `utf8_stream.t`, `debug_multibyte.t` | 3 | Encoding |
| Security | `security_api.t`, `deep_nesting.t` | 2 | May need stubs |
| Misc | `xml_escape.t`, `g_void.t`, `subs_inherited.t`, `tree_entity_expand.t`, `combine_chars.t`, `defaulted.t`, `element_decl.t`, `stream_attr_escape.t`, `stream_localize.t`, `file_open_scalar.t`, `parsefile_base_restore.t`, `bare_glob_filehandle.t` | 12 | Various features |
| Stress | `astress.t` | 1 | Large document |

### Expected to Need Stubs/Workarounds

| Test File | Issue | Strategy |
|---|---|---|
| `current_byte.t` | SAX Locator lacks byte offset | Approximate via UTF-8 byte counting, or skip |
| `current_length.t` | SAX Locator lacks byte count | Approximate or skip |
| `encoding.t` | Custom `.enc` encoding maps | Stub `LoadEncoding`, use Java charset support |
| `expat_version.t` | Reports expat version string | Return synthetic version |
| `position_overflow.t` | Tests byte offset overflow | Depends on byte tracking impl |
| `memory_leak_symtab.t` | Tests symbol table cleanup | May need DESTROY (known limitation) |

### Build/Config Tests (may need adaptation)

| Test File | Issue |
|---|---|
| `checklib_findcc.t` | Tests Devel::CheckLib C compiler detection |
| `checklib_tmpdir.t` | Tests Devel::CheckLib temp directory |

## Implementation Plan

### Phase 1: Infrastructure and Installation (estimated: 1-2 sessions)

| Step | Description | File |
|---|---|---|
| 1a | Create `XMLParserExpat.java` skeleton extending `PerlModuleBase` | `src/main/java/.../perlmodule/XMLParserExpat.java` |
| 1b | Implement `ParserCreate` — create `SAXParser` wrapper, store in hash | `XMLParserExpat.java` |
| 1c | Implement all `Set*Handler` methods — store Perl coderefs | `XMLParserExpat.java` |
| 1d | Create `XML/Parser/Expat.pm` shim for `jar:PERL5LIB` | `src/main/perl/lib/XML/Parser/Expat.pm` |
| 1e | Fix installation path so `jcpan` can install Parser.pm and Style modules | `ExtUtils/MakeMaker.pm` or `Devel/CheckLib.pm` stub |
| 1f | Run `make` to verify unit tests pass | — |

**Result**: `use XML::Parser` loads without error; no parsing yet.

### Phase 2: Core Parsing (estimated: 2-3 sessions)

| Step | Description | File |
|---|---|---|
| 2a | Implement `ParseString` — feed string to SAX parser, dispatch callbacks | `XMLParserExpat.java` |
| 2b | Implement `ParseStream` — read from Perl IO handle, feed to SAX | `XMLParserExpat.java` |
| 2c | Implement Start/End/Char handler dispatch with Perl callback invocation | `XMLParserExpat.java` |
| 2d | Implement Comment, PI, CdataStart/CdataEnd dispatch | `XMLParserExpat.java` |
| 2e | Implement position tracking (`Locator` → `current_line`/`current_column`) | `XMLParserExpat.java` |
| 2f | Implement `base()` get/set | `XMLParserExpat.java` |
| 2g | Test: `styles.t`, `cdata.t`, `file.t`, basic parsing | — |

**Result**: Basic XML parsing works with Tree/Debug/Stream/Subs/Objects styles.

### Phase 3: DTD and Declarations (estimated: 1-2 sessions)

| Step | Description | File |
|---|---|---|
| 3a | Implement Doctype/DoctypeFin via `LexicalHandler.startDTD/endDTD` | `XMLParserExpat.java` |
| 3b | Implement Entity/Element/Attlist via `DeclHandler` | `XMLParserExpat.java` |
| 3c | Implement Unparsed/Notation via `DTDHandler` | `XMLParserExpat.java` |
| 3d | Implement ExternEnt/ExternEntFin via `EntityResolver` | `XMLParserExpat.java` |
| 3e | Implement `ContentModel` construction from `DeclHandler.elementDecl` | `XMLParserExpat.java` or Expat.pm |
| 3f | Implement XMLDecl handler (parse `<?xml?>` prolog) | `XMLParserExpat.java` |
| 3g | Test: `decl.t`, `parament.t`, `external_ent.t`, `namespaces.t` | — |

**Result**: DTD-heavy tests pass; XML::SAX::Expat can use our backend.

### Phase 4: Advanced Features (estimated: 1-2 sessions)

| Step | Description | File |
|---|---|---|
| 4a | Implement Default handler (catch-all for unhandled events) | `XMLParserExpat.java` |
| 4b | Implement `ParsePartial`/`ParseDone` for ExpatNB incremental parsing | `XMLParserExpat.java` |
| 4c | Implement `specified_attr()` and `element_index()` | `XMLParserExpat.java` |
| 4d | Implement byte position tracking (approximate) | `XMLParserExpat.java` |
| 4e | Stub security API methods (no-op) | `XMLParserExpat.java` |
| 4f | Stub `ExpatVersion()`/`ExpatVersionInfo()` | `XMLParserExpat.java` |
| 4g | Stub `LoadEncoding`/`FreeEncoding` (Java handles encodings natively) | `XMLParserExpat.java` |
| 4h | Test: full test suite, count pass/fail/skip | — |

**Result**: Near-complete XML::Parser support.

### Phase 5: Polish and Downstream Modules (estimated: 1 session)

| Step | Description |
|---|---|
| 5a | Fix remaining test failures discovered in Phase 4 |
| 5b | Test XML::Simple with XML::Parser backend (`t/A_XMLParser.t`, `t/C_External_Entities.t`) |
| 5c | Test XML::SAX::Expat integration |
| 5d | Update `dev/modules/xml_simple.md` to reflect XML::Parser availability |
| 5e | Update `dev/modules/README.md` with XML::Parser entry |

**Result**: XML::Parser fully working, downstream modules benefit.

## Known Limitations

### SAX vs Expat Behavioral Differences

| Feature | Expat (C) | JDK SAX | Impact |
|---|---|---|---|
| Byte offset/count | Exact | Not available | `current_byte()` returns approximate value or -1 |
| Original string | Exact verbatim bytes | Not available | `original_string()` returns reconstructed or undef |
| Recognized string | UTF-8 representation | Not available | `recognized_string()` returns reconstructed or undef |
| Custom `.enc` maps | Binary encoding files | Java charset support | `load_encoding()` is a no-op; Java handles encodings |
| Stream delimiter | Native support | Must be implemented in Java wrapper | Wrap InputStream to detect delimiter |
| Entity expansion control | `NoExpand` option | SAX `external-general-entities` feature | Map to SAX feature flags |
| Billion Laughs protection | libexpat 2.4.0+ API | Java SAX has its own limits | Stub the API; Java protects by default |

### Tests Expected to Remain Failing

| Test | Reason |
|---|---|
| `checklib_findcc.t` | Tests C compiler detection — not relevant on JVM |
| `checklib_tmpdir.t` | Tests C compiler temp dirs — not relevant on JVM |
| `memory_leak_symtab.t` | May test DESTROY behavior (known PerlOnJava limitation) |

## Progress Tracking

> See [`dev/design/xml_parser_xs.md`](../design/xml_parser_xs.md) for current progress.

### Completed
- [x] Investigation and API catalog (2025-04-07)
- [x] Phase 1: Infrastructure and installation (2025-04-06)
- [x] Phase 2: Core parsing (2025-04-06)
- [x] Phase 3: DTD and declarations (2025-04-07)
- [x] Phase 4 partial: Advanced features (2025-04-07)
- 41/47 test files pass (95%)

### Remaining
- Phase 4 continued: Encoding conversion (x-sjis-unicode)
- UseForeignDTD

## Related Documents

- `dev/modules/xml_simple.md` — XML::Simple (benefits from XML::Parser availability)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation
- `dev/modules/xsloader.md` — XSLoader architecture
