# XML::LibXML — XS Shim Refactor Plan

## Summary

Replace our hand-written `src/main/perl/lib/XML/LibXML.pm` (~789 lines,
incomplete) with the original upstream `LibXML.pm` (2371 lines, complete
pure-Perl layer), patched only to remove `XSLoader`. Java continues to provide
the underlying DOM primitives — just registered under the XS function names that
the original Perl file expects.

**Expected outcome**: the ~2000 lines of pure-Perl convenience code in the
original file (wrappers, OO infrastructure, `NamedNodeMap`, `Namespace`,
`_SAXParser`, `Pattern`, `RegExp`, `XPathExpression`, `InputCallback`, …) are
picked up for free, with no additional Java work for those parts.

---

## Why this is better than the current approach

| | Current | XS-shim |
|---|---|---|
| Perl layer LOC | ~789 (incomplete) | 2371 (upstream, complete) |
| Missing methods | Dozens (removeChildNodes, nonBlankChildNodes, lookupNamespacePrefix, setNodeName, isEqualNode, toStringC14N, …) | None — already in upstream Perl |
| Maintenance | Must hand-port every new feature | Only the ~80 XS primitives need Java |
| Test compatibility | Custom semantics diverge from tests | Tests written for this exact Perl file |

---

## Step 0 — Understand what the original LibXML.pm calls

The upstream file calls two kinds of methods:

1. **Pure-Perl methods** — defined in the same file, need nothing from us.
2. **XS boundary methods** — called as `$self->_someMethod(...)` or as bare
   function calls (registered by `XSLoader` in the normal XS flow).  
   Java must register these.

### Complete list of XS boundary calls made by LibXML.pm

Extracted from the upstream source (`LibXML.pm` method calls that are NOT
defined as `sub` in that file, and that appear in `LibXML.xs`):

#### Registered on `XML::LibXML` (parser object)

| XS name | Java method | Status |
|---|---|---|
| `_parse_string` | `_parse_string` | ✅ registered |
| `_parse_fh` | `_parse_fh` | ✅ registered |
| `_parse_file` | `_parse_file` | ✅ registered |
| `_parse_html_string` | `_parse_html_string` | ✅ registered |
| `_parse_html_fh` | `_parse_html_fh` | ⚠ add stub |
| `_parse_html_file` | `_parse_html_file` | ⚠ add stub |
| `_parse_sax_string` | `_parse_sax_string` | ⚠ add stub (SAX) |
| `_parse_sax_fh` | `_parse_sax_fh` | ⚠ add stub (SAX) |
| `_parse_sax_file` | `_parse_sax_file` | ⚠ add stub (SAX) |
| `_parse_sax_xml_chunk` | `_parse_sax_xml_chunk` | ⚠ add stub (SAX) |
| `_parse_xml_chunk` | `_parse_xml_chunk` | ✅ registered |
| `_start_push` | `_start_push` | ✅ registered |
| `_push` | `_push` | ✅ registered |
| `_end_push` | `_end_push` | ✅ registered |
| `_end_sax_push` | stub | ⚠ add stub |
| `_processXIncludes` | stub | ⚠ add stub |
| `load_catalog` | stub | ⚠ add stub |
| `_default_catalog` | stub | ⚠ add stub |
| `_externalEntityLoader` | stub | ⚠ add stub |
| `LIBXML_RUNTIME_VERSION` | `LIBXML_RUNTIME_VERSION` | ✅ registered |
| `LIBXML_VERSION` | `LIBXML_VERSION` | ✅ registered |
| `INIT_THREAD_SUPPORT` | `INIT_THREAD_SUPPORT` | ✅ registered |
| `DISABLE_THREAD_SUPPORT` | `DISABLE_THREAD_SUPPORT` | ✅ registered |

#### Registered on `XML::LibXML::Node` (and subclasses via ISA)

| XS name | Java method | Status |
|---|---|---|
| `_childNodes(nonblank_flag)` | `childNodesFiltered` | ⚠ new method needed |
| `_attributes()` | `attributes` | ⚠ add alias `_attributes` |
| `_toString(format)` | `toString` | ⚠ add alias `_toString` |
| `_findnodes(xpath)` | `findnodes` | ✅ alias registered |
| `_find(xpath,bool)` | `nodeFindRaw` | ✅ alias registered |
| `_toStringC14N(…)` | stub | ⚠ add stub |
| `isSameNode` | `isSameNode` | ✅ registered |
| `addSibling` | `nodeAddSibling` | ✅ registered |
| `nodeName` | `nodeName` | ✅ |
| `nodeValue` | `nodeValue` | ✅ |
| `nodeType` | `nodeType` | ✅ |
| `parentNode` | `parentNode` | ✅ |
| `firstChild` | `firstChild` | ✅ |
| `lastChild` | `lastChild` | ✅ |
| `previousSibling` | `previousSibling` | ✅ |
| `nextSibling` | `nextSibling` | ✅ |
| `hasChildNodes` | `hasChildNodes` | ✅ |
| `hasAttributes` | `hasAttributes` | ✅ |
| `cloneNode` | `cloneNode` | ✅ |
| `appendChild` | `appendChild` | ✅ |
| `insertBefore` | `insertBefore` | ✅ |
| `insertAfter` | `insertAfter` | ✅ |
| `removeChild` | `removeChild` | ✅ |
| `replaceChild` | `replaceChild` | ✅ |
| `replaceNode` | `replaceNode` | ✅ |
| `unbindNode` | `unbindNode` | ✅ |
| `ownerDocument` | `ownerDocument` | ✅ |
| `getOwnerDocument` | `ownerDocument` | ✅ |
| `unique_key` | `unique_key` | ✅ |
| `baseURI` | `nodeBaseURI` | ✅ |
| `setBaseURI` | `nodeSetBaseURI` | ✅ |
| `appendText` | `appendText` | ✅ |
| `getData` | `getData` | ✅ |
| `setData` | `setData` | ✅ |
| `localname` | `localname` | ✅ |
| `prefix` | `prefix` | ✅ |
| `namespaceURI` | `namespaceURI` | ✅ |
| `nodePath` | `nodePath` | ✅ |
| `lookupNamespaceURI` | `elemLookupNamespaceURI` | ✅ |
| `lookupNamespacePrefix` | — | ❌ NEW |
| `removeChildNodes` | — | ❌ NEW |
| `firstNonBlankChild` | — | ❌ NEW |
| `nextNonBlankSibling` | — | ❌ NEW |
| `previousNonBlankSibling` | — | ❌ NEW |
| `setNodeName` | — | ❌ NEW |
| `_isEqual` | — | ❌ NEW (used by `isEqualNode`) |
| `_getNamespaceDeclURI` | — | ❌ NEW |
| `setNamespaceDeclURI` | — | ❌ NEW |

#### Registered on `XML::LibXML::Document`

| XS name | Java method | Status |
|---|---|---|
| `_setDocumentElement` | `setDocumentElement` | ⚠ add alias |
| `_toString(format)` | `documentToString` | ⚠ add alias `_toString` |
| `createEntityReference` | — | ❌ NEW |

#### Registered on `XML::LibXML::Element`

| XS name | Java method | Status |
|---|---|---|
| `_getAttribute` | `getAttribute` | ⚠ add alias |
| `_getAttributeNS` | `getAttributeNS` | ⚠ add alias |
| `_setAttribute` | `setAttribute` | ⚠ add alias |
| `_setAttributeNS` | `setAttributeNS` | ⚠ add alias |
| `_setNamespace` | `setNamespace` | ⚠ add alias |
| `_getNamespaceDeclURI` | — | ❌ NEW (same as node version) |
| `setNamespaceDeclURI` | — | ❌ NEW (same as node version) |
| `lookupNamespacePrefix` | — | ❌ NEW (same as node version) |
| `setNodeName` | — | ❌ NEW (same as node version) |

#### Registered on `XML::LibXML::Attr`

| XS name | Java method | Status |
|---|---|---|
| `_setData` | `setAttrValue` | ⚠ add alias |
| `serializeContent` | — | ⚠ add stub |
| `toString` | — | ⚠ override (` name="value"` format) |

#### Registered on `XML::LibXML::PI`

| XS name | Java method | Status |
|---|---|---|
| `_setData` | `piSetData` | ⚠ add alias |

#### Registered on `XML::LibXML::Namespace` (Perl hash object, not a DOM node)

| XS name | Java method | Notes |
|---|---|---|
| `localname` | — | Perl sub: `$self->{prefix}` |
| `declaredURI` | — | Perl sub: `$self->{href}` |
| `declaredPrefix` | — | Perl sub: `$self->{prefix}` |
| `unique_key` | — | Perl sub: `"$prefix\n$uri"` |
| `_isEqual` | `namespaceIsEqual` | ❌ NEW — compare prefix+uri |

The upstream `Namespace` package in `LibXML.pm` uses `$$self` (scalar-ref
dereference) for `isSameNode`. Our Namespace objects are hash refs, not scalar
refs. **Patch required** in the Perl file (see Step 1 below).

---

## Step 1 — Patch `LibXML.pm` for PerlOnJava

Source: `~/.cpan/build/XML-LibXML-2.0210-99/LibXML.pm`  
Target: `src/main/perl/lib/XML/LibXML.pm`

Copy the upstream file and apply **only** the following minimal patches:

### Patch A — Remove XSLoader, set `$__loaded`

In the `BEGIN` block, remove:
```perl
use XSLoader ();
```
and replace:
```perl
XSLoader::load( 'XML::LibXML', $VERSION );
undef &AUTOLOAD;
```
with:
```perl
# PerlOnJava: XS methods registered by XMLLibXML.initialize() via Java
$XML::LibXML::__loaded = 1;
```

### Patch B — Remove LIBXML_RUNTIME_VERSION version check

Remove the block that calls `LIBXML_RUNTIME_VERSION()` and `LIBXML_VERSION`:
```perl
{
  my ($runtime_version) = LIBXML_RUNTIME_VERSION() =~ /^(\d+)/;
  if ( $runtime_version < LIBXML_VERSION ) {
    warn "Warning: ...";
  }
}
```
(Java stubs for these functions return equal values so the check would be a
no-op, but removing it is cleaner.)

### Patch C — Guard thread-import path

In the `import` sub, wrap the `:threads_shared` path so it silently skips
rather than dying when thread support is unavailable:

```perl
  if (grep /^:threads_shared$/, @_) {
-   require threads;
+   eval { require threads };
    if (!defined($__threads_shared)) {
-     if (INIT_THREAD_SUPPORT()) {
+     if (eval { INIT_THREAD_SUPPORT() }) {
```

### Patch D — Fix `XML::LibXML::Namespace::isSameNode`

The upstream uses `$$self == $$ref` (scalar-ref address comparison).
PerlOnJava's Namespace objects are blessed hash refs, not scalar refs.

Replace:
```perl
sub isSameNode {
    my ( $self, $ref ) = @_;
    if ( $$self == $$ref ){
        return 1;
    }
    return 0;
}
```
with:
```perl
sub isSameNode {
    my ( $self, $ref ) = @_;
    return (ref($ref) && $self == $ref) ? 1 : 0;
}
```

### Patch E — `XML::LibXML::Namespace` field names

The upstream XS stores Namespace objects as scalar refs to C structs.
Our Java implementation (`makeNamespaceObject`) stores them as hash refs
with keys `{prefix}` and `{uri}`.

The upstream Perl package calls XS methods `localname`, `declaredURI`,
`declaredPrefix`, `unique_key` on the C struct. Since our objects are hashes,
add a pure-Perl fallback implementation of those XS methods inside the
`XML::LibXML::Namespace` package:

```perl
sub localname     { $_[0]->{prefix} }
sub getLocalName  { $_[0]->{prefix} }
sub declaredPrefix { $_[0]->{prefix} }
sub declaredURI   { $_[0]->{uri} }
sub getData       { $_[0]->{uri} }
sub getValue      { $_[0]->{uri} }
sub value         { $_[0]->{uri} }
sub nodeValue     { $_[0]->{uri} }
sub getNamespaceURI { 'http://www.w3.org/2000/xmlns/' }
sub getPrefix     { 'xmlns' }
sub prefix        { 'xmlns' }
sub nodeType      { 18 }  # XML_NAMESPACE_DECL
sub unique_key    { ($_[0]->{prefix}//'') . "\n" . ($_[0]->{uri}//'') }
```

These replace the XS calls; the `nodeName` sub already in `LibXML.pm` will
call `$self->localname` and pick up the pure-Perl version.

---

## Step 2 — New Java methods in `XMLLibXML.java`

### 2a. `isBlankNode` helper (private static)

```java
private static boolean isBlankNode(Node node) {
    short t = node.getNodeType();
    if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE) {
        String v = node.getNodeValue();
        return v == null || v.trim().isEmpty();
    }
    return false;
}
```

### 2b. `childNodesFiltered(self, only_nonblank)`

```java
public static RuntimeList childNodesFiltered(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    boolean onlyNonBlank = args.size() > 1 && args.get(1).getBoolean();
    NodeList children = node.getChildNodes();
    RuntimeList result = new RuntimeList();
    for (int i = 0; i < children.getLength(); i++) {
        Node child = children.item(i);
        if (onlyNonBlank && isBlankNode(child)) continue;
        result.add(wrapNode(child));
    }
    return result;
}
```

### 2c. `removeChildNodes`

```java
public static RuntimeList removeChildNodes(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    while (node.getFirstChild() != null)
        node.removeChild(node.getFirstChild());
    return scalarUndef.getList();
}
```

### 2d. `lookupNamespacePrefix(self, uri)`

```java
public static RuntimeList lookupNamespacePrefix(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    String uri = args.get(1).toString();
    Node cur = node;
    while (cur != null && cur.getNodeType() == Node.ELEMENT_NODE) {
        NamedNodeMap attrs = cur.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String n = a.getName();
            if (a.getValue().equals(uri)) {
                if (n.startsWith("xmlns:")) return new RuntimeScalar(n.substring(6)).getList();
                if (n.equals("xmlns"))   return new RuntimeScalar("").getList();
            }
        }
        cur = cur.getParentNode();
    }
    return scalarUndef.getList();
}
```

### 2e. `firstNonBlankChild`, `nextNonBlankSibling`, `previousNonBlankSibling`

```java
public static RuntimeList firstNonBlankChild(RuntimeArray args, int ctx) {
    Node n = getNode(args.get(0)).getFirstChild();
    while (n != null && isBlankNode(n)) n = n.getNextSibling();
    return (n != null ? wrapNode(n) : scalarUndef).getList();
}

public static RuntimeList nextNonBlankSibling(RuntimeArray args, int ctx) {
    Node n = getNode(args.get(0)).getNextSibling();
    while (n != null && isBlankNode(n)) n = n.getNextSibling();
    return (n != null ? wrapNode(n) : scalarUndef).getList();
}

public static RuntimeList previousNonBlankSibling(RuntimeArray args, int ctx) {
    Node n = getNode(args.get(0)).getPreviousSibling();
    while (n != null && isBlankNode(n)) n = n.getPreviousSibling();
    return (n != null ? wrapNode(n) : scalarUndef).getList();
}
```

### 2f. `setNodeName(self, newName)`

```java
public static RuntimeList setNodeName(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    String newName = args.get(1).toString();
    try {
        if (node.getOwnerDocument() != null)
            node.getOwnerDocument().renameNode(node, node.getNamespaceURI(), newName);
    } catch (Exception ignored) {}
    return scalarUndef.getList();
}
```

### 2g. `nodeIsEqual(self, other)` — for `_isEqual` / `isEqualNode`

```java
public static RuntimeList nodeIsEqual(RuntimeArray args, int ctx) {
    Node a = getNode(args.get(0));
    Node b = getNode(args.get(1));
    return new RuntimeScalar(a != null && a.isEqualNode(b) ? 1 : 0).getList();
}
```

### 2h. `getNamespaceDeclURI(self, prefix)`

```java
public static RuntimeList getNamespaceDeclURI(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    RuntimeScalar prefixArg = args.size() > 1 ? args.get(1) : null;
    String prefix = (prefixArg == null || prefixArg.type == RuntimeScalarType.UNDEF)
                    ? "" : prefixArg.toString();
    if (!(node instanceof Element)) return scalarUndef.getList();
    String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
    String val = ((Element) node).getAttribute(attrName);
    return (val != null && !val.isEmpty()) ? new RuntimeScalar(val).getList()
                                           : scalarUndef.getList();
}
```

### 2i. `setNamespaceDeclURI(self, prefix, newURI)`

```java
public static RuntimeList setNamespaceDeclURI(RuntimeArray args, int ctx) {
    Node node = getNode(args.get(0));
    String prefix = args.get(1).toString();
    RuntimeScalar newURI = args.size() > 2 ? args.get(2) : null;
    if (!(node instanceof Element)) return scalarUndef.getList();
    Element el = (Element) node;
    String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
    if (newURI == null || newURI.type == RuntimeScalarType.UNDEF)
        el.removeAttribute(attrName);
    else
        el.setAttribute(attrName, newURI.toString());
    return scalarUndef.getList();
}
```

### 2j. `createEntityReference(self, name)`

```java
public static RuntimeList createEntityReference(RuntimeArray args, int ctx) {
    Document doc = (Document) getNode(args.get(0));
    return wrapNode(doc.createEntityReference(args.get(1).toString())).getList();
}
```

### 2k. `namespaceIsEqual(self, other)` — for `XML::LibXML::Namespace._isEqual`

```java
public static RuntimeList namespaceIsEqual(RuntimeArray args, int ctx) {
    try {
        RuntimeHash h1 = args.get(0).hashDerefRaw();
        RuntimeHash h2 = args.get(1).hashDerefRaw();
        boolean eq = h1.get("prefix").toString().equals(h2.get("prefix").toString())
                  && h1.get("uri").toString().equals(h2.get("uri").toString());
        return new RuntimeScalar(eq ? 1 : 0).getList();
    } catch (Exception e) {
        return new RuntimeScalar(0).getList();
    }
}
```

### 2l. Stubs for unused but called XS functions

Add a single `nopMethod` stub and alias it to anything that just needs to
return undef/empty without crashing:

```java
public static RuntimeList nopMethod(RuntimeArray args, int ctx) {
    return scalarUndef.getList();
}
```
Register as: `_end_sax_push`, `_processXIncludes`, `load_catalog`,
`_default_catalog`, `_externalEntityLoader`, `_parse_sax_string`,
`_parse_sax_fh`, `_parse_sax_file`, `_parse_sax_xml_chunk`,
`_toStringC14N` (on Node), `attrSerializeContent`.

---

## Step 3 — Update `initialize()` registrations in `XMLLibXML.java`

### 3a. Parser-level additions (on `XML::LibXML`)

```java
module.registerMethod("_parse_html_fh",        null);    // (implement or stub)
module.registerMethod("_parse_html_file",       null);    // (implement or stub)
module.registerMethod("_end_sax_push",          "nopMethod");
module.registerMethod("_processXIncludes",      "nopMethod");
module.registerMethod("load_catalog",           "nopMethod");
module.registerMethod("_default_catalog",       "nopMethod");
module.registerMethod("_externalEntityLoader",  "nopMethod");
module.registerMethod("_parse_sax_string",      "nopMethod");
module.registerMethod("_parse_sax_fh",          "nopMethod");
module.registerMethod("_parse_sax_file",        "nopMethod");
module.registerMethod("_parse_sax_xml_chunk",   "nopMethod");
```

### 3b. Node-level additions (append to `nodeMethods` array)

```java
{"_childNodes",              "childNodesFiltered"},
{"_attributes",              "attributes"},
{"_toString",                "toString"},
{"_isEqual",                 "nodeIsEqual"},
{"_toStringC14N",            "nopMethod"},
{"removeChildNodes"},
{"lookupNamespacePrefix"},
{"firstNonBlankChild"},
{"nextNonBlankSibling"},
{"previousNonBlankSibling"},
{"setNodeName"},
{"_getNamespaceDeclURI",     "getNamespaceDeclURI"},
{"setNamespaceDeclURI"},
{"createEntityReference"},   // NOTE: belongs on Document, but harmless on Node too
```

### 3c. Document-level additions (append to `docMethods` array)

```java
{"_setDocumentElement",  "setDocumentElement"},
{"_toString",            "documentToString"},
{"createEntityReference"},
```

### 3d. Element-level additions (append to `elemMethods` array)

```java
{"_getAttribute",         "getAttribute"},
{"_getAttributeNS",       "getAttributeNS"},
{"_setAttribute",         "setAttribute"},
{"_setAttributeNS",       "setAttributeNS"},
{"_setNamespace",         "setNamespace"},
{"_getNamespaceDeclURI",  "getNamespaceDeclURI"},
{"setNamespaceDeclURI"},
{"lookupNamespacePrefix"},
{"setNodeName"},
```

### 3e. Attr additions

```java
module.registerMethodInPackage("XML::LibXML::Attr", "_setData",          "setAttrValue");
module.registerMethodInPackage("XML::LibXML::Attr", "serializeContent",  "nopMethod");
```

### 3f. PI additions

```java
module.registerMethodInPackage("XML::LibXML::PI", "_setData", "piSetData");
```

### 3g. Namespace additions

```java
module.registerMethodInPackage("XML::LibXML::Namespace", "_isEqual", "namespaceIsEqual");
```

---

## Step 4 — Remove our custom XML/LibXML.pm sections now covered upstream

After switching to the upstream file, these packages from our old
`XML/LibXML.pm` are **no longer needed** (upstream defines them):

- `XML::LibXML::Namespace` — upstream defines it (with our Patch E additions)
- `XML::LibXML::NamedNodeMap` — upstream defines it (pure Perl, complete)
- `XML::LibXML::Node` base stubs — upstream defines them
- `XML::LibXML::Document` Perl wrappers — upstream defines them
- `XML::LibXML::Element` Perl wrappers — upstream defines them

Anything in our old file that is NOT in the upstream file should be
reviewed; most will be redundant or superseded.

---

## Step 5 — Handle `XML::LibXML::SAX.pm`

The upstream `lib/XML/LibXML/SAX.pm` is a full SAX driver. Our current
`src/main/perl/lib/XML/LibXML/SAX.pm` is a custom rewrite.

Keep our custom `SAX.pm` for now. The upstream one calls many more XS
functions we haven't mapped yet. Revisit in a later phase.

---

## Step 6 — Build and test

```bash
cd /path/to/PerlOnJava2
make 2>&1 | tail -5                   # must be BUILD SUCCESSFUL

# Run the key test files
cd ~/.cpan/build/XML-LibXML-2.0210-99
/path/to/jperl t/04node.t   2>&1 | grep -c "^ok"   # was 93, target ~195
/path/to/jperl t/06elements.t 2>&1 | grep -c "^ok" # was 45
/path/to/jperl t/10ns.t     2>&1 | grep -c "^ok"   # was 11

# Full suite via perl_test_runner (or jcpan -t):
perl dev/tools/perl_test_runner.pl ~/.cpan/build/XML-LibXML-2.0210-99/t/ \
  > /tmp/libxml_results.txt 2>&1
grep "passed/total" /tmp/libxml_results.txt | tail -3
```

Target after this refactor: **≥ 70% pass rate** (up from 53.7%).

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Upstream `$$self` scalar-ref idiom in `Namespace::isSameNode` | Patch D covers it |
| `_childNodes` scalar context returns count (upstream XS behaviour) | The upstream Perl wrappers always call it in list context — no action needed |
| `AttributeHash` tie in `XML::LibXML::Element` calls `__destroy_tiecache` / `DESTROY` | These use `Scalar::Util::weaken`; test and stub if needed |
| `_toStringC14N` stub returns undef | Tests for C14N will still fail; acceptable for now |
| Some upstream `BEGIN` code calls XS functions before `initialize()` runs | The `BEGIN` block only calls `LIBXML_RUNTIME_VERSION()` (removed by Patch B) and sets `$__loaded` |
| `XML::LibXML::InputCallback` uses `lib_init_callbacks` / `lib_cleanup_callbacks` | These are registered on `XML::LibXML` already via our existing registrations (check and add if missing) |

---

## Files to change

| File | Change |
|---|---|
| `src/main/perl/lib/XML/LibXML.pm` | Replace with patched upstream (2371 lines) |
| `src/main/java/…/XMLLibXML.java` | Add ~15 new methods + registration aliases |
| `src/main/perl/lib/XML/LibXML/SAX.pm` | Keep as-is (our custom version) |

Do NOT change any test files.

---

## Progress tracking

- [ ] Step 1: Patch LibXML.pm written
- [ ] Step 2: New Java methods added
- [ ] Step 3: `initialize()` registrations updated
- [ ] Step 4: Build passes
- [ ] Step 5: t/04node.t ≥ 150/195
- [ ] Step 6: t/06elements.t ≥ 120/191
- [ ] Step 7: overall pass rate ≥ 70%
