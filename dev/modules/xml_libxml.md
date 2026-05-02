# XML::LibXML Support for PerlOnJava

## Status

**Tier A COMPLETE** — `jcpan -t XML::Diff` passes.

**Upstream test suite baseline**: `./jcpan -t XML::LibXML` runs all 77 test files
(XML-LibXML-2.0210).  As of this writing **19/77 pass** (including skips).
The remaining 58 failures are real implementation gaps documented here.

**Module**: XML::LibXML 2.0210 (XS, depends on Alien::Libxml2 → native libxml2)
**Trigger**: `jcpan -t XML::Diff` fails because XML::Diff requires
XML::LibXML 1.56, which cannot be installed under jperl.
**Branch**: `feature/xml-libxml-plan` (this PR adds the plan only)

## Problem Statement

XML::LibXML is one of the most widely-used CPAN XML modules and a hard
dependency of XML::Diff, XML::Compile, XML::LibXSLT, SOAP::WSDL, and many
others. It currently fails to install via jcpan with:

```
Cannot find either a share directory or a ConfigData module for Alien::Libxml2.
Can't locate Alien/Libxml2/ConfigData.pm in @INC ...
```

Root cause:

1. XML::LibXML's `Makefile.PL` requires `Alien::Libxml2`, which expects either
   a system `libxml2` discovered through `pkg-config` or a `share/`-installed
   build produced by `Alien::Build`. Neither is available under jperl.
2. Even if `Alien::Libxml2` were satisfied, **`LibXML.xs` cannot be loaded** —
   PerlOnJava runs on the JVM and cannot dlopen native `.so`/`.dylib` objects.
3. Without `XML/LibXML.pm` in `@INC`, every dependent test (e.g. XML::Diff's
   `t/1.t`) aborts on `use XML::LibXML;` with "Bad plan" and 0 tests run.

## Solution: Java XS Implementation

Implement XML::LibXML as a Java XS module (`XMLLibXML.java`) backed by the
JDK's built-in XML stack:

| XML::LibXML feature | JDK backing |
|---|---|
| Parsing (`parse_string`, `parse_file`, `parse_fh`) | `javax.xml.parsers.DocumentBuilder` |
| DOM tree (`Document`, `Element`, `Node`, `Attr`, `Text`, `Comment`, `CDATASection`, `PI`, `DocumentFragment`, `NodeList`, `NamedNodeMap`) | `org.w3c.dom.*` |
| XPath (`findnodes`, `find`, `findvalue`, `exists`, `XPathContext`) | `javax.xml.xpath.XPath` + custom `NamespaceContext` |
| Serialization (`toString`, `toFile`, `toFH`) | `javax.xml.transform.Transformer` / `LSSerializer` |
| Namespaces | `Element.setAttributeNS`, prefix maps |
| HTML parsing (Tier B) | Jsoup (already used by `HTML::Parser`) bridged into `org.w3c.dom` |
| Validation (Tier C) | `javax.xml.validation.Schema`, RelaxNG via Jing (optional) |

**No new Maven/Gradle dependencies** for Tier A — `java.xml` is part of the
JDK standard library. Tier B reuses Jsoup, which is already on the classpath.

### Why JDK DOM and not libxml2?

- PerlOnJava cannot load native libraries (no JNI/FFM for libxml2).
- JDK DOM is a faithful W3C DOM Level 3 implementation; XML::LibXML's API is
  essentially a Perl-flavoured wrapper over the same W3C DOM, so the mapping
  is largely 1:1.
- Same pattern as XML::Parser (JDK SAX), HTML::Parser (Jsoup), DateTime
  (`java.time`), Digest::MD5 (`java.security.MessageDigest`).
- XPath 1.0, namespace handling, and serialization are all in the JDK.

### Known divergences from upstream libxml2

These are inherent to using JDK XML and will be documented as known
limitations rather than worked around:

- **Error messages and line numbers** will differ in wording.
- **Round-trip serialization** may reorder attributes and renormalise
  whitespace differently than libxml2.
- **HTML parsing** uses Jsoup's HTML5 parser (Tier B), which is stricter and
  more standards-compliant than libxml2's HTML parser; some malformed input
  may produce different trees.
- **`recover` mode** maps to JAXP `setErrorHandler` with non-fatal recovery;
  exact error counts will differ.
- **DTD validation** is supported by JAXP but DTD *introspection* (the
  `XML::LibXML::Dtd` API surface) is limited.
- **XInclude** processing differs in fixup-base-URI behaviour.
- **No XSLT** — that's a separate module (`XML::LibXSLT`).

## Dependency Tree

```
XML::LibXML 2.0210 (target tier sets which methods are stubbed vs implemented)
├── (no XS dependencies under the Java backend)
├── XML::SAX::Base               (CPAN, pure Perl — for SAX adapter, Tier B)
├── Encode                       (bundled)
└── (optional, Tier C)
    ├── Jing JAR                 (RelaxNG)
    └── Saxon-HE JAR             (XPath 2.0 / future XSLT)
```

`Alien::Libxml2` is **not** a runtime dep under the Java backend; the
PerlOnJava-bundled `XML/LibXML.pm` shim does not `use Alien::Libxml2`.

## Upstream Test Baseline (XML-LibXML-2.0210)

Measured by running each `t/*.t` file individually with `./jperl`.

| Status | Count | Tests |
|--------|-------|-------|
| Pass (all subtests) | 19 | 00-report-prereqs.t, 01basic.t, 18docfree.t, 35huge_mode.t, 46err_column.t, 48_memleak_rt_83744.t, 48_rt93429_recover_2_in_html_parsing.t, 48_SAX_Builder_rt_91433.t, 48_rt123379_setNamespace.t, 80registryleak.t, 90stack.t, and several skip-all |
| Partial (some subtests run) | 18 | 02parse.t (182/533), 04node.t (6/195), 05text.t (3/59), 06elements.t (1/191), 09xpath.t (3/54), 12html.t (1/43), 13dtd.t (2/18), 14sax.t, 15nodelist.t, 19encoding.t, 20extras.t, 32xpc_variables.t, 41xinclude.t, 43options.t (30/291), 48_rt55000.t, 91unique_key.t |
| Crash/abort (0 subtests or early die) | 40 | 03doc.t, 07dtd.t, 08findnodes.t, 10ns.t, 16docnodes.t, 23rawfunctions.t, 24c14n.t, 25relaxng.t, 26schema.t, 27new_callbacks_simple.t, 28new_callbacks_multiple.t, 29id.t, 30keep_blanks.t, 30xpathcontext.t, 31xpc_functions.t, 40reader.t, 44extent.t, 45regex.t, 47load_xml_callbacks.t, 48_RH5_double_free_rt83779.t, 48_gh_pr63_detect_undef_values.t, 48_reader_undef_warning_on_empty_str_rt106830.t, 48_removeChild_crashes_rt_80395.t, 48_replaceNode_DTD_nodes_rT_80521.t, 48_importing_nodes_IDs_rt_69520.t, 49_load_html.t, 49callbacks_returning_undef.t, 49global_extent.t, 50devel.t, 51_parse_html_string_rt87089.t, 60error_prev_chain.t, 60struct_error.t, 61error.t, 62overload.t, 71overloads.t, 72destruction.t, 17callbacks.t, 21catalog.t, 42common.t |

## Implementation Gap Analysis

The failures fall into these categories, ordered by impact (tests unlocked):

---

### 1. `childNodes` list context — HIGH IMPACT

**Affects**: t/04node.t (stops at test 7), t/16docnodes.t (stops at line 29),
and any test that does `my @kids = $node->childNodes`.

**Root cause**: `childNodes` always returns a single `XML::LibXML::NodeList`
blessed arrayref, even in list context.  Perl's `my @arr = $node->childNodes`
puts the NodeList object itself into `@arr[0]` (scalar), not the individual nodes.

**Expected behaviour**:
- **List context**: returns a flat list of individual `XML::LibXML::*` node objects.
- **Scalar context**: returns an `XML::LibXML::NodeList` blessed reference.

**Fix**: In `XMLLibXML.java`, check `ctx == RuntimeContextType.LIST` and return
individual wrapped nodes (similar to how `findnodes` already does this).

```java
public static RuntimeList childNodes(RuntimeArray args, int ctx) {
    NodeList children = getNode(args.get(0)).getChildNodes();
    if (ctx == RuntimeContextType.LIST) {
        RuntimeList result = new RuntimeList();
        for (int i = 0; i < children.getLength(); i++)
            result.add(wrapNode(children.item(i)));
        return result;
    }
    RuntimeArray arr = new RuntimeArray();
    for (int i = 0; i < children.getLength(); i++)
        RuntimeArray.push(arr, wrapNode(children.item(i)));
    return ReferenceOperators.bless(arr.createReference(),
        new RuntimeScalar("XML::LibXML::NodeList")).getList();
}
```

Also add `getChildnodes` as an alias (used in t/16docnodes.t).

---

### 2. Missing `$XML::LibXML::skipXMLDeclaration` in `toString` — HIGH IMPACT

**Affects**: t/02parse.t stops producing output after test 181 (all remaining
tests require round-trip via `toString` with `$skipXMLDeclaration = 1`).

**Root cause**: `documentToString` and `toString` (node serialization) ignore
the `$XML::LibXML::skipXMLDeclaration` package variable.

**Fix**: In `XMLLibXML.java::serializeNode`, read the Perl global:

```java
private static String serializeNode(Node node, boolean format, boolean withDecl) {
    if (withDecl) {
        RuntimeScalar skip = GlobalVariable.getGlobalVariable("XML::LibXML::skipXMLDeclaration");
        if (skip != null && skip.getBoolean()) withDecl = false;
    }
    // ... existing Transformer code ...
}
```

Also respect `$XML::LibXML::skipDTD` (suppress DOCTYPE in output) and
`$XML::LibXML::setTagCompression` (self-closing vs empty element style).

---

### 3. `keep_blanks(0)` does not strip whitespace text nodes — HIGH IMPACT

**Affects**: t/02parse.t `toString` round-trip tests (`<a>    <b/> </a>` → `<a><b/></a>`).

**Root cause**: `setIgnoringElementContentWhitespace(true)` only strips
*element content whitespace* (whitespace in element-only content models defined
by DTD).  For XML with no DTD, JAXP cannot classify content models, so the
flag does nothing.

**Fix**: After parsing with `keepBlanks=false`, walk the DOM tree and remove
all `Text` nodes that contain only whitespace (i.e., `node.getNodeValue().trim().isEmpty()`).
Implement this as a post-parse helper `stripBlankTextNodes(Document doc)`.

---

### 4. Missing Document methods — HIGH IMPACT

Each missing method stops a whole test file at the first call.

| Missing method | Caller | Fix |
|---|---|---|
| `createDocument` | t/03doc.t line 140 | Creates new `Document`; calls `DocumentBuilder.newDocument()` then wraps |
| `getDocumentElement` | t/08findnodes.t, t/20extras.t, t/29id.t | Alias for `documentElement` |
| `getChildnodes` | t/16docnodes.t | Alias for `childNodes` on Document |
| `getVersion` | t/14sax.t (via SAX/Parser.pm) | Alias for `version` / `documentVersion` |
| `createExternalSubset` | t/07dtd.t | Creates external DTD reference; can return undef stub initially |
| `toStringHTML` | various | Serialize as HTML (no XML declaration, HTML entities); can delegate to Jsoup or Transformer |

`createDocument` signature: `XML::LibXML::Document->createDocument($version, $encoding)` —
returns a new, empty Document node blessed as `XML::LibXML::Document`.

---

### 5. Missing Element methods — HIGH IMPACT

| Missing method | Caller | Fix |
|---|---|---|
| `tagName` | t/06elements.t line 34 | Alias for `nodeName` (W3C DOM calls it `tagName` on Element) |
| `lookupNamespaceURI($prefix)` | t/10ns.t line 48 | `Element.lookupNamespaceURI(prefix)` in JDK DOM |
| `new($name)` | t/62overload.t line 10 | Constructor — creates a detached Element node |

`XML::LibXML::Element->new($name)` should call `createElementNS` on a
temporary document and return the wrapped node.  This requires a shared
scratch `Document` kept as a static field in `XMLLibXML.java`.

---

### 6. Missing `XML::LibXML::XPathExpression->new` — HIGH IMPACT

**Affects**: t/09xpath.t, t/30xpathcontext.t (stop at first call).

**Root cause**: No `new` method registered for the `XML::LibXML::XPathExpression` class.

Upstream `XML::LibXML::XPathExpression->new($expr_string)` compiles an XPath
expression for reuse.  JDK has no compiled-XPath type separate from
`XPathExpression` objects, but we can store the string and compile lazily.

**Fix**: Register a `new` method in `XMLLibXML.java`:
```java
module.registerMethodInPackage("XML::LibXML::XPathExpression", "new", "xpathExprNew");
module.registerMethodInPackage("XML::LibXML::XPathExpression", "expression", "xpathExprStr");
```
Store the expression string in the blessed hash.

---

### 7. Error message format mismatches — MEDIUM IMPACT

**Affects**: t/02parse.t test 29/170 (`like($@, qr/^Empty String at/)`).

**Root cause**: When `parse_string(undef)` is called, our implementation
throws "Premature end of file" (JAXP's message for empty input).  The test
expects the error to start with `"Empty String at"`.

**Fix**: In `_parse_string`, detect `undef` or zero-length input before
calling JAXP, and throw with the expected prefix:
```perl
if (!defined $str || $str eq '') {
    Carp::croak("Empty String");
}
```
(This is already done in the Perl shim `XML/LibXML.pm` for the `parse_string`
wrapper — double-check the code path for the case where `$str` is Perl `undef`
vs empty string vs `"\n"`.)

Also: `parse_file` error messages should start with `"$filename:$line: parser error : ..."`.
Currently we emit `"XML::LibXML::parse_file: The markup in the document following..."`.
Fix by catching `SAXParseException` and reformatting:
```java
throw new RuntimeException(filename + ":" + e.getLineNumber() +
    ": parser error : " + e.getMessage());
```

---

### 8. Missing Text methods — MEDIUM IMPACT

**Affects**: t/05text.t (stops at test 4).

| Missing method | Fix |
|---|---|
| `substringData($offset, $count)` | `node.getNodeValue().substring(offset, offset+count)` |
| `appendData($str)` | `node.setNodeValue(node.getNodeValue() + str)` |
| `insertData($offset, $str)` | String insertion at offset |
| `deleteData($offset, $count)` | String deletion |
| `replaceData($offset, $count, $str)` | String replacement |
| `splitText($offset)` | DOM `Text.splitText(offset)` |

These are `CharacterData` DOM methods — all straightforward.

---

### 9. Missing Dtd support — MEDIUM IMPACT

**Affects**: t/07dtd.t, t/13dtd.t.

| Missing | Fix |
|---|---|
| `XML::LibXML::Dtd->new($name, $url)` | Parse DTD from URL using `DocumentBuilder.parse` with DTD handler; stub can return an opaque object |
| `XML::LibXML::Dtd->parse_string($str)` | Parse DTD from string; stub possible |
| `$doc->createInternalSubset($name, $pid, $sid)` | Creates DOCTYPE declaration |
| `$doc->createExternalSubset($name, $pid, $sid)` | Creates external DTD ref |

Full DTD introspection (entity/element/attribute declarations) is a Tier C feature.
A stub that returns objects passing `isa('XML::LibXML::Dtd')` unblocks tests that
only check `defined($dtd)`.

---

### 10. `XML::LibXML::Common::encodeToUTF8` UTF-16 — MEDIUM IMPACT

**Affects**: t/42common.t tests 5–7 (UTF-16 string is expected to be 2× the byte length).

**Root cause**: Our `encodeToUTF8` returns a UTF-8 encoded string (4 bytes per
char for non-BMP), but the test expects the UTF-16 encoded version to have
`2 × char_count` bytes.

`XML::LibXML::Common::encodeToUTF8($charset, $str)` — converts $str
(already internal Perl string) *from* $charset *to* UTF-8 bytes.  When
`$charset` is `"UTF-16"`, libxml2 just re-encodes; JDK should do the same.
The fix is to call `str.getBytes(StandardCharsets.UTF_16)` (or the specific
variant) and wrap the result, not re-encode as UTF-8.

---

### 11. `parse_html_file` — MEDIUM IMPACT

**Affects**: t/12html.t (stops after test 1).

Our implementation has `parse_html_string` via Jsoup, but `parse_html_file`
is missing.  Fix: add a thin wrapper that reads the file and calls
`parse_html_string`.

---

### 12. SAX callbacks / `match_callback` etc. — MEDIUM IMPACT

**Affects**: t/17callbacks.t (stops at `match_callback`).

The callback system (`match_callback`, `read_callback`, `open_callback`,
`close_callback`) is used to intercept file loading during parse.
These are Tier B features.  Stub implementations that accept the
argument and do nothing will let most tests proceed.

---

### 13. `toStringC14N` / Canonical XML — LOW IMPACT

**Affects**: t/24c14n.t.

Canonical XML (C14N) serialization is a complex feature with well-defined
W3C semantics.  JDK ships no built-in C14N transformer.  Options:
- Apache Santuario (additional JAR dependency)
- Manual implementation (feasible for basic C14N but not exclusive-C14N)
- Stub that throws "not implemented"

---

### 14. `XML::LibXML::RelaxNG` and `XML::LibXML::Schema` — LOW IMPACT

**Affects**: t/25relaxng.t, t/26schema.t.

JDK supports XML Schema validation via `javax.xml.validation`.  RelaxNG
requires a third-party library (Jing JAR).  Both are Tier C.
Stub `->new` that throws "not implemented" or `->validate` that returns 1
will let unrelated tests in the same file proceed (none in these files).

---

### 15. `XML::LibXML::Reader` (pull reader) — LOW IMPACT

**Affects**: t/40reader.t, t/40reader_mem_error.t.

StAX-backed implementation.  Complex; deferred to Tier C.

---

### 16. `XML::LibXML::RegExp` — LOW IMPACT

**Affects**: t/45regex.t.

Wraps libxml2's regexp (based on XML Schema regex).  JDK `java.util.regex`
with schema-compatible mode.  Stub `->new` unblocks.

---

### 17. XInclude — LOW IMPACT

**Affects**: t/41xinclude.t.

JAXP `DocumentBuilder` can resolve XIncludes via `setXIncludeAware(true)`.
Currently not wired up.

---

### 18. DTD entity expansion — LOW IMPACT

**Affects**: t/44extent.t.

`expand_entities(0)` keeps entity references as entity-reference nodes in the
DOM.  JAXP by default expands entities.  To preserve entity refs, set
`DocumentBuilderFactory.setExpandEntityReferences(false)` (or use SAX with
`setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false)` and the lexical
handler).  Serialisation of entity refs in JAXP produces XML output with
`&name;` only if `setExpandEntityReferences(false)` is set.

---

### 19. `addChild` on Text/Node — LOW IMPACT

**Affects**: t/23rawfunctions.t.

`addChild` is an alias for `appendChild` in some contexts, or a specialized
form that accepts raw node types.  Register as alias.

---

### 20. `base_uri` parser option — LOW IMPACT

**Affects**: t/43options.t (stops at test 80, after 30 subtests pass).

Parser attribute getter/setter.  Store in `ParserOptions` hash; pass to
`DocumentBuilder` as `setDocumentURI` on parsed document.

---

## Phased Plan

### Tier A — XML::Diff unblock ✅ COMPLETE

**Goal**: pass `jcpan -t XML::Diff` (38 tests in `t/1.t`).

Implemented in `XMLLibXML.java` (~1,200 lines) + `XML/LibXML.pm` (~350 lines).

### Tier B — "Useful" coverage (~80% of CPAN consumers)

**Goal**: Reach ≥50% pass rate on upstream XML-LibXML-2.0210 test suite.

Extends Tier A with fixes for items 1–12 above (high and medium priority):

1. Fix `childNodes` list vs scalar context
2. Respect `$XML::LibXML::skipXMLDeclaration` in `toString`
3. Post-parse whitespace stripping for `keep_blanks(0)`
4. Add missing Document aliases (`getDocumentElement`, `createDocument`, `getVersion`, etc.)
5. Add missing Element methods (`tagName`, `lookupNamespaceURI`, `Element->new`)
6. Add `XML::LibXML::XPathExpression->new`
7. Fix error message format for `undef`/empty input and `parse_file`
8. Add CharacterData methods (`substringData`, `appendData`, etc.)
9. Add DTD stubs (`XML::LibXML::Dtd->new`)
10. Fix `encodeToUTF8` for UTF-16 input
11. Add `parse_html_file`
12. Stub callback methods (`match_callback`, etc.)

**Estimated effort**: 2–5 days.

### Tier C — Comprehensive

Adds: full C14N, RelaxNG/XSchema validation, XInclude, pull Reader,
RegExp, XInclude, catalog support, complete error objects with line/column,
full SAX adapter, `XML::LibXML::PrettyPrint`.

**Estimated effort**: 2–4 weeks.

## Architecture Notes

### Mapping `org.w3c.dom.Node` → Perl blessed reference

Each Java DOM `Node` is wrapped in a `RuntimeScalar` blessed into the
appropriate `XML::LibXML::*` class chosen from `node.getNodeType()`:

| `Node.ELEMENT_NODE`        | `XML::LibXML::Element`       |
| `Node.TEXT_NODE`           | `XML::LibXML::Text`          |
| `Node.CDATA_SECTION_NODE`  | `XML::LibXML::CDATASection`  |
| `Node.COMMENT_NODE`        | `XML::LibXML::Comment`       |
| `Node.PROCESSING_INSTRUCTION_NODE` | `XML::LibXML::PI`    |
| `Node.ATTRIBUTE_NODE`      | `XML::LibXML::Attr`          |
| `Node.DOCUMENT_NODE`       | `XML::LibXML::Document`      |
| `Node.DOCUMENT_FRAGMENT_NODE` | `XML::LibXML::DocumentFragment` |

Identity is preserved via a per-Document `IdentityHashMap<Node, RuntimeScalar>`
weakly held on the owning Document, so two Perl-side fetches of the same
underlying node compare equal under `==` (matching upstream XML::LibXML's
behaviour).

### NodeList / array context

`XML::LibXML::NodeList` is dual-natured: it overloads `@{}` (returns the
list), supports `->size`, `->pop`, `->shift`, `->get_node($i)`, and is what
`findnodes` returns in scalar context. The Java glue returns a `RuntimeArray`
blessed into `XML::LibXML::NodeList`; the small bit of overload (`@{}`,
scalar-context size) is in the `.pm` shim.

In **list context**, `childNodes` and `findnodes` must return a flat Perl list
of individual node objects (not the NodeList wrapper).

### XPath with namespace contexts

`XML::LibXML::XPathContext->registerNs($prefix, $uri)` builds a
`NamespaceContext` passed to a `javax.xml.xpath.XPath`. Element-level
`->findnodes` uses an implicit context derived from in-scope namespaces of
the context node (`Element.lookupNamespaceURI` walked up the tree).

### File layout

```
src/main/perl/lib/XML/
  LibXML.pm                  # main shim, blesses XS-loaded objects
  LibXML/
    Document.pm              # tiny inheritance/overload shims
    Element.pm
    Node.pm
    NodeList.pm
    Text.pm
    Attr.pm
    XPathContext.pm
    ...

src/main/java/org/perlonjava/runtime/perlmodule/
  XMLLibXML.java             # XSLoader entry point + static methods
```

## Open Questions

1. **Encoding round-trips**: libxml2 preserves the original byte-for-byte
   `<?xml version="1.0" encoding="UTF-8"?>` declaration; JAXP regenerates
   it. XML::Diff compares serialized output — we may need a custom
   serializer that mimics libxml2's whitespace/attribute order. Investigate
   in Tier B spike before committing to a method.
2. **`keep_blanks`**: JAXP has no direct equivalent for mixed-content models;
   we implement via post-parse DOM walk.  This should be tested for
   correctness with real-world XML.
3. **Identity map lifetime**: weak map vs strong — pick once we measure
   GC pressure on a real workload (the XML::Diff test is small, so this
   may only surface in Tier B).
4. **CPAN distribution form**: ship inside the jar as a bundled module
   (like XML::Parser), or as a separate "PerlOnJava-XMLLibXML"
   distribution that overrides the CPAN one when installed? Bundled is
   simpler for Tier A; revisit for Tier B.

## References

- Existing port to model after: [`dev/modules/xml_parser.md`](xml_parser.md),
  [`dev/design/xml_parser_xs.md`](../design/xml_parser_xs.md),
  `src/main/java/org/perlonjava/runtime/perlmodule/XMLParserExpat.java`
  (~2,200 lines, ~100 XS methods).
- Upstream: <https://metacpan.org/dist/XML-LibXML>.
- XML::Diff (test driver for Tier A): <https://metacpan.org/dist/XML-Diff>.
- W3C DOM Level 3: <https://www.w3.org/TR/DOM-Level-3-Core/>.

## Progress Tracking

### Current Status: Tier A complete; Tier B in planning

### Completed Phases
- [x] Tier A: XML::Diff unblock (2025-04)
  - Created `XMLLibXML.java` (~1,200 lines) with parser, DOM, XPath, serialization
  - Created `XML/LibXML.pm` Perl shim (~350 lines)
  - Fixed jcpan infrastructure (HandleConfig.pm, Distribution.pm, Config.pm)
  - XML::Diff passes: `jcpan -t XML::Diff` → PASS
- [x] jcpan infrastructure for upstream test suite (2025-05)
  - `./jcpan -t XML::LibXML` now runs all 77 test files (was blocked by wrong prefs dir)
  - Added `PERLONJAVA_SKIP` and `PERLONJAVA_TEST_IGNORE_FAILURES` distropref sentinels
  - Baseline: 19/77 test files pass (see table above)

### Next Steps (Tier B)

1. Fix `childNodes` list context (item 1 above) — unlocks t/04node.t
2. Add `$XML::LibXML::skipXMLDeclaration` to `toString` (item 2) — unlocks 02parse.t tests 182+
3. Post-parse blank-node stripping for `keep_blanks(0)` (item 3)
4. Add missing Document aliases: `getDocumentElement`, `createDocument`, `getVersion` (item 4)
5. Add missing Element methods: `tagName`, `lookupNamespaceURI`, `Element->new` (item 5)
6. Add `XML::LibXML::XPathExpression->new` (item 6) — unlocks t/09xpath.t, t/30xpathcontext.t
7. Fix error message format for undef/empty input and `parse_file` (item 7)
8. Add CharacterData methods: `substringData`, `appendData`, etc. (item 8)
9. Add `XML::LibXML::Dtd->new` stub (item 9)
10. Fix `encodeToUTF8` for UTF-16 (item 10)
11. Add `parse_html_file` (item 11)
12. Stub `match_callback` etc. (item 12)

### Open Questions
- Should `XML::LibXML::Element->new($name)` create a detached element using a
  scratch Document singleton, or require an owning document arg?
  (Upstream allows detached creation.)
