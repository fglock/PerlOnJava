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

| Test | Result | Category | Root Cause |
|------|--------|----------|------------|
| decl.t | 44/46 (2 incomplete) | SAX gaps | NOTATION type format bug + missing text declaration XMLDecl |
| foreign_dtd.t | 0/5 (4 ran, all fail) | Not implemented | UseForeignDTD requires synthetic ExternEnt handler call |
| checklib_findcc.t | 2/3 | Not XML::Parser | Source-code inspection of stub `inc/Devel/CheckLib.pm` |
| checklib_tmpdir.t | 1/3 | Not XML::Parser | Source-code inspection of stub `inc/Devel/CheckLib.pm` |

---

## Detailed Analysis of Remaining Failures

### 1. decl.t — 44/46 pass, 2 tests never emitted

**Difficulty**: Easy (bug fix) + Medium (feature addition)

Both missing tests are caused by behavioral differences between libexpat and our SAX-backed implementation. All 44 running tests pass correctly.

#### Missing Test A: NOTATION attribute type format (line 157)

**Impact**: 1 test  
**Root cause**: Off-by-one bug in `XMLParserExpat.java` line 1792  
**Difficulty**: Trivial fix

The test at line 156-157 of decl.t:
```perl
elsif ( $attname eq 'foo' and $type eq 'NOTATION(x|y|z)' ) {
    is( $default, '#IMPLIED' );   # NEVER REACHED
}
```

SAX reports `NOTATION (x|y|z)` (with a space after NOTATION). Our code at line 1788-1792 attempts to fix this but has an off-by-one error:

```java
// Current (broken):
fixedType = "NOTATION" + fixedType.substring(8);
// "NOTATION" is 8 chars, substring(8) starts at the space → unchanged

// Fix:
fixedType = "NOTATION" + fixedType.substring(9);
// substring(9) skips past the space → "NOTATION(x|y|z)"
```

**Verification**: System Perl with libexpat reports `NOTATION(x|y|z)` (no space). Our implementation reports `NOTATION (x|y|z)` (with space), so the `elsif` condition never matches and the `is()` test is never emitted.

#### Missing Test B: XMLDecl for external entity text declarations (line 175)

**Impact**: 1 test  
**Root cause**: SAX has no callback for text declarations in external parsed entities  
**Difficulty**: Medium

The test at line 174-176 of decl.t:
```perl
else {
    is( $enc, 'x-sjis-unicode' );   # NEVER REACHED
}
```

The `xd` (XMLDecl) handler expects two calls:
1. Main document `<?xml version="1.0" encoding="ISO-8859-1" ?>` → `$version` is defined → 3 tests (lines 170-173) ✅
2. External DTD `t/foo.dtd` text declaration `<?xml encoding="x-sjis-unicode"?>` → `$version` is undef → 1 test (line 175) ❌

In libexpat, `XML_SetXmlDeclHandler` fires for **both** the main document's XML declaration and text declarations in external parsed entities. In our SAX implementation, the XMLDecl handler is fired in `startDocument()` (line 1021), which only runs once for the main document. External entity text declarations are consumed internally by SAX with no callback.

**Suggested fix**: In `resolveEntity()`, after reading entity content bytes, use `extractDeclaredEncoding()` to detect a text declaration. If found and `state.xmlDeclHandler` is set, fire the callback with `version=undef`, `encoding=<detected>`, `standalone=undef` before returning the InputSource.

**Complication**: After `convertEncoding()`, the encoding declaration is rewritten to `UTF-8`. The XMLDecl handler must be fired **before** `convertEncoding()` to report the original encoding name. Also, the encoding reported should be the **original** encoding from the raw bytes, not the converted one.

**Additional note**: The `fixed` parameter in Attlist callbacks has a minor behavioral difference: our code returns `0` (false) for non-fixed attributes, while libexpat returns `undef`. This doesn't cause test failures because decl.t uses `ok(!$fixed)` which passes for both, but it's worth noting for completeness.

---

### 2. foreign_dtd.t — 0/5 pass (4 ran, 1 never emitted, 4 fail)

**Difficulty**: Hard  
**Tests affected**: 5 tests

#### What UseForeignDTD does

`UseForeignDTD => 1` tells libexpat to pretend there is an external DTD subset even when the document has no `<!DOCTYPE>` declaration. This causes expat to synthesize a call to the `ExternalEntityRefHandler` at the start of parsing with both `systemId` and `publicId` set to `NULL`. The handler can then return a filehandle to a DTD file, providing element declarations, attribute defaults, and entity definitions for a document that lacks its own DOCTYPE.

#### Test breakdown

The test creates a temporary DTD file `t/foreign.dtd` containing:
```
<!ELEMENT doc (#PCDATA)>
<!ATTLIST doc class CDATA "default_value">
<!ENTITY greeting "Hello from foreign DTD">
```

It then parses a DOCTYPE-less document:
```xml
<?xml version="1.0"?>
<doc>&greeting;</doc>
```

| Test # | Line | Expected | Actual | Analysis |
|--------|------|----------|--------|----------|
| 1 | 51 | `$sysid` is undef for foreign DTD | Never reached | ExternEnt handler never called (no synthesized call) |
| 2 | 68 | Parse succeeds (`$@ eq ''`) | `$@ = "not well-formed (invalid token)"` | `&greeting;` is undefined — no DTD was loaded |
| 3 | 69 | `$attrs{class} eq 'default_value'` | `undef` | No DTD → no attribute defaults applied |
| 4 | 70 | `$char_data eq 'Hello from foreign DTD'` | `''` | No DTD → entity not expanded |
| 5 | 84 | Error matches `/undefined entity/` | Error is `"not well-formed (invalid token)"` | SAX error message difference |

#### Implementation approach

There are three aspects to implement:

**A. Synthesize ExternEnt handler call** (fixes tests 1-4):

When `UseForeignDTD => 1` and `ParseParamEnt => 1`, before starting the SAX parse:
1. Check if the document has a `<!DOCTYPE>` declaration
2. If not, call the ExternEnt handler with `(parser, base, undef, undef)`
3. If the handler returns a filehandle or string, read the DTD content
4. Prepend a synthetic `<!DOCTYPE root_element [ ... ]>` wrapper around the DTD content, or inject it into the document before parsing

The challenge is that SAX doesn't support injecting DTD content after document parsing has begun. Possible approaches:
- **Pre-process the document**: Detect the root element name, prepend `<!DOCTYPE root_element SYSTEM "foreign.dtd">`, and set up the entity resolver to return the DTD content. This requires scanning ahead for the root element name.
- **Two-pass approach**: First parse to detect root element name, then reparse with injected DOCTYPE.
- **Wrap in synthetic DOCTYPE**: Use a well-known placeholder like `<!DOCTYPE __foreign_dtd__ [<!ENTITY % __foreign SYSTEM "__foreign__.dtd">%__foreign;]>` and resolve it via the entity resolver.

**B. Error message format** (fixes test 5):

SAX reports "not well-formed (invalid token)" for undefined entity references. Libexpat reports "undefined entity". These are different error messages for the same condition. The test uses `like($@, qr/undefined entity/)` which won't match our SAX error.

Fix: In the SAX error handler, detect when the error is about undefined entities (e.g., check if the error message contains "entity" and the context shows `&name;`) and reformat the message to match expat's wording.

---

### 3. checklib_findcc.t — 2/3 pass, 1 fail

**Difficulty**: Trivial (but not XML-related)  
**Root cause**: Source-code inspection of a stub file

These tests read the **source text** of `inc/Devel/CheckLib.pm` and use regex to verify specific code patterns exist. The file is a 9-line stub created during PerlOnJava's CPAN installation to bypass C compiler checks:

```perl
package Devel::CheckLib;
use strict;
use Exporter;
our @ISA = ('Exporter');
our @EXPORT = qw(assert_lib check_lib_or_exit check_lib);
sub assert_lib { 1 }
sub check_lib_or_exit { 1 }
sub check_lib { 1 }
1;
```

| Test # | What it checks | Result | Why |
|--------|----------------|--------|-----|
| 1 | `use_ok('Devel::CheckLib')` | PASS | Stub loads fine |
| 2 | No bare `_findcc();` call at package level | PASS | Stub has none |
| 3 | `die()` message interpolates `$Config{cc}` | **FAIL** | Stub has no `die` or `_findcc` at all |

**Fix options**:
- **Option A**: Replace the stub with the real upstream `Devel::CheckLib` source from the XML-Parser-2.56 tarball. All 3 tests would then pass.
- **Option B**: Skip these tests. They verify C-compiler-related source code quality, which is irrelevant in a JVM environment.

---

### 4. checklib_tmpdir.t — 1/3 pass, 2 fail

**Difficulty**: Trivial (but not XML-related)  
**Root cause**: Same stub file as above

| Test # | What it checks | Result | Why |
|--------|----------------|--------|-----|
| 1 | `tempfile()` uses `DIR => File::Spec->tmpdir()` | **FAIL** | Stub has no `tempfile` call |
| 2 | At least 2 `mktemp()` calls in source | **FAIL** | Stub has 0 `mktemp` calls |
| 3 | All `mktemp()` calls use `File::Spec->tmpdir()` | PASS | Vacuously true (0 calls found, `$all_use_tmpdir` stays 1) |

**Fix options**: Same as checklib_findcc.t above. These tests verify that GH#76 (NFS tmpdir fix) is properly implemented in the Devel::CheckLib source code.

---

## Completed Phases

### Phase 4: Encoding Conversion (2026-04-07)

**Tests fixed**: encoding.t (0→43/43), parament.t (1/4→13/13)

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

### Batch 3 fixes (2025-04-07)

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

### Batch 2 fixes (2025-04-07)

- UTF-8 double-encoding fix (BYTE_STRING → ISO_8859_1)
- Specified vs defaulted attributes (Attributes2.isSpecified)
- Error message format ("not well-formed" + hints)
- SystemId un-resolution (parseBaseUri tracking)
- String interpolation `${$ref}{key}` parser fix
- IO handle class detection (GLOB → IO::Handle)
- MakeMaker BASEEXT directory scanning

### Initial SAX-backed implementation (2025-04-06)

- All core handlers: Start, End, Char, Comment, PI, CDATA, Default
- DTD handlers: Entity, Element, Attlist, Notation, Unparsed, XMLDecl, Doctype
- Namespace support with dualvar names
- Position tracking (line, column, byte)
- MakeMaker integration for Style module installation
