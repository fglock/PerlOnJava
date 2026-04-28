# XML::LibXML Support for PerlOnJava

## Status

**Plan only — no implementation yet.** This document scopes a Java-backed
re-implementation of `XML::LibXML` for PerlOnJava, modelled on the existing
`XML::Parser` port (see [`dev/modules/xml_parser.md`](xml_parser.md) and
[`dev/design/xml_parser_xs.md`](../design/xml_parser_xs.md)).

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

## Phased Plan

### Tier A — XML::Diff unblock (target of this work)

**Goal**: pass `jcpan -t XML::Diff` (38 tests in `t/1.t`).

API surface required (extracted from `XML/Diff.pm` and `t/1.t`):

| Class | Methods |
|---|---|
| `XML::LibXML` (parser) | `new`, `keep_blanks`, `parse_string`, `parse_file` |
| `XML::LibXML::Document` | `documentElement`, `setDocumentElement`, `createElement`, `toString` |
| `XML::LibXML::Node` | `nodeName`, `nodeType`, `parentNode`, `nextSibling`, `previousSibling`, `childNodes`, `firstChild`, `attributes`, `cloneNode`, `appendChild`, `insertBefore`, `insertAfter`, `removeChild`, `unbindNode`, `hasChildNodes`, `textContent`, `toString`, `setData`, `setNamespace` |
| `XML::LibXML::Element` | `getAttribute`, `setAttribute`, `removeAttribute`, `findnodes` |
| `XML::LibXML::NodeList` | array deref, `size`, `pop`, `get_node` |

Total: ~30 user-visible methods.

**Estimated size**: ~400–700 lines of Perl shim (`XML/LibXML.pm`) +
~600–1,000 lines of Java glue (`XMLLibXML.java`) ≈ **1,000–1,700 lines**.
Most methods are one-line wrappers over `org.w3c.dom` calls.

**Effort**: 2–4 days.

**Acceptance criteria**:
1. `jcpan -t XML::Diff` reports `Result: PASS` (38/38 in `t/1.t`).
2. `make` (full unit-test suite) still green.
3. `make test-bundled-modules` still green.
4. New unit tests under `src/test/resources/unit/xml_libxml/` covering the
   methods above (parse → manipulate → serialize round-trips).

### Tier B — "Useful" coverage (~80% of CPAN consumers)

Extends Tier A with:

- Full namespace handling (`createElementNS`, `getAttributeNS`, `setAttributeNS`, `XPathContext` with `registerNs`).
- `find`, `findvalue`, `exists` (XPath returning string/boolean/list as appropriate).
- `parse_html_string`, `parse_html_file` via Jsoup.
- `Comment`, `PI`, `CDATASection`, `DocumentFragment` node types.
- `addNewChild`, `addChild`, `getElementsByTagName[NS]`, `getNamespaces`.
- Encoding/version on `Document`, `toFile`, `toStringHTML`.
- Basic `XML::LibXML::Reader` (StAX-backed, optional).
- Error objects with line/column where JAXP exposes them.

**Estimated size**: ~1,500–2,500 lines Perl + ~1,500–2,500 lines Java ≈
**3,000–5,000 lines total**.

**Effort**: 1.5–3 weeks.

Unblocks: XML::Atom, XML::Feed, SOAP::Lite (XML path), XML::Compile basics,
XML::RSS::LibXML, XML::Twig::XPath, etc.

### Tier C — Comprehensive

Adds: DTD/RelaxNG/XSD validation, XInclude, full SAX adapter
(`XML::LibXML::SAX`), `XML::LibXML::Pattern`, custom error handlers,
DOCTYPE manipulation, `XML::LibXML::PrettyPrint`, schema introspection.

**Estimated size**: ~7,000–11,000 lines total. Effort: 1.5–3 months.

Reference: upstream XML::LibXML 2.0210 ships ~10k lines of `.pm` plus
~5k lines of `.xs` glue.

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
`findnodes` returns. The Java glue returns a `RuntimeArray` blessed into
`XML::LibXML::NodeList`; the small bit of overload (`@{}`, scalar-context
size) is in the `.pm` shim.

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
  XMLLibXML.java             # XSLoader entry point + ~30 (Tier A) static methods
```

## Open Questions

1. **Encoding round-trips**: libxml2 preserves the original byte-for-byte
   `<?xml version="1.0" encoding="UTF-8"?>` declaration; JAXP regenerates
   it. XML::Diff compares serialized output — we may need a custom
   serializer that mimics libxml2's whitespace/attribute order. Investigate
   in Tier A spike before committing to a method.
2. **`keep_blanks`**: JAXP has no direct equivalent; we'll need a
   post-parse whitespace-text-node stripper. Default for XML::LibXML is
   `keep_blanks=1`, so the no-op path is the common one.
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

### Current status

Plan only — no code changes. **Tier A not yet started.**

### Next steps

1. Spike: parse + serialize round-trip vs upstream libxml2 output, decide
   on serializer strategy (open question 1).
2. Scaffold `src/main/perl/lib/XML/LibXML.pm` shim and
   `XMLLibXML.java` skeleton with `parse_string` only; verify XSLoader path.
3. Implement Tier A methods in dependency order: parser → Document/Element
   constructors → Node tree mutators → toString → findnodes.
4. Add unit tests under `src/test/resources/unit/xml_libxml/`.
5. Run `jcpan -t XML::Diff`; iterate until `Result: PASS`.
6. Update this doc with completed-phase markers per AGENTS.md conventions.
