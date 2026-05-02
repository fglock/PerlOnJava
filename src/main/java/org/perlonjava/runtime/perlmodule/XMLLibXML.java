package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.runtimetypes.PerlDieException;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Java XS implementation of XML::LibXML.
 * Tier A: ~30 methods required by XML::Diff.
 * Backed by JDK's built-in XML stack.
 *
 * Node representation: each XML::LibXML node is a blessed hash reference
 * with key "_node" -> RuntimeScalar(JAVAOBJECT = org.w3c.dom.Node).
 */
public class XMLLibXML extends PerlModuleBase {

    public static final String XS_VERSION = "2.0210";

    private static final String NODE_KEY = "_node";
    private static final String OPTS_KEY = "_parser_opts";
    private static final String XPC_KEY  = "_xpc_state";

    /** Pseudo-namespace for functions registered without namespace ("{}name"). */
    private static final String NONS_NS     = "http://perlonjava.org/xpc-nons";
    private static final String NONS_PREFIX = "__pns__";

    private static final DocumentBuilderFactory DBF;
    private static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();

    static {
        DBF = DocumentBuilderFactory.newInstance();
        DBF.setNamespaceAware(true);
        DBF.setExpandEntityReferences(true);
    }

    // ----------------------------------------------------------------
    // Inner classes
    // ----------------------------------------------------------------

    static class ParserOptions {
        boolean keepBlanks      = true;
        boolean recover         = false;
        boolean expandEntities  = false; // XML_PARSE_NOENT; false = keep EntityReference nodes
    }

    static class XPathContextState {
        Node contextNode;
        final Map<String, String>         namespaces       = new LinkedHashMap<>();
        final Map<String, RuntimeScalar>  customFunctions  = new HashMap<>();
        RuntimeScalar                     varLookupCallback = null;  // single var-lookup func
    }

    static class SimpleNamespaceContext implements NamespaceContext {
        private final Map<String, String> pfxToUri;
        private final Map<String, String> uriToPfx;

        SimpleNamespaceContext(Map<String, String> ns) {
            pfxToUri = new HashMap<>(ns);
            uriToPfx = new HashMap<>();
            for (Map.Entry<String, String> e : ns.entrySet()) uriToPfx.put(e.getValue(), e.getKey());
        }

        public String getNamespaceURI(String prefix) {
            if (prefix == null) throw new IllegalArgumentException("Null prefix");
            return pfxToUri.getOrDefault(prefix, javax.xml.XMLConstants.NULL_NS_URI);
        }

        public String getPrefix(String uri) { return uriToPfx.get(uri); }

        public Iterator<String> getPrefixes(String uri) {
            String pfx = getPrefix(uri);
            return pfx == null ? Collections.emptyIterator() : Collections.singleton(pfx).iterator();
        }
    }

    // ----------------------------------------------------------------
    // Constructor / initialize
    // ----------------------------------------------------------------

    public XMLLibXML() {
        super("XML::LibXML", false);
    }

    public static void initialize() {
        XMLLibXML module = new XMLLibXML();
        try {
            // Parser / top-level methods
            module.registerMethod("_new_parser",          null);
            module.registerMethod("_keep_blanks",         null);
            module.registerMethod("_parse_string",        null);
            module.registerMethod("_parse_file",          null);
            module.registerMethod("_parse_fh",            null);
            module.registerMethod("_parse_html_string",   null);
            module.registerMethod("_parse_html_fh",       null);
            module.registerMethod("_parse_html_file",     null);
            module.registerMethod("LIBXML_RUNTIME_VERSION", null);
            module.registerMethod("LIBXML_VERSION",       null);
            module.registerMethod("INIT_THREAD_SUPPORT",  null);
            module.registerMethod("DISABLE_THREAD_SUPPORT", null);
            module.registerMethod("encodeToUTF8",         null);
            module.registerMethod("decodeFromUTF8",       null);
            // Push parsing
            module.registerMethod("_start_push",          null);
            module.registerMethod("_push",                null);
            module.registerMethod("_end_push",            null);
            module.registerMethod("_parse_xml_chunk",     null);
            // Stubs for SAX / XInclude / catalog functions
            module.registerMethod("_end_sax_push",        "nopMethod", null);
            module.registerMethod("_processXIncludes",    "nopMethod", null);
            module.registerMethod("load_catalog",         "nopMethod", null);
            module.registerMethod("_default_catalog",     "nopMethod", null);
            module.registerMethod("_externalEntityLoader","nopMethod", null);
            module.registerMethod("_parse_sax_string",    "nopMethod", null);
            module.registerMethod("_parse_sax_fh",        "nopMethod", null);
            module.registerMethod("_parse_sax_file",      "nopMethod", null);
            module.registerMethod("_parse_sax_xml_chunk", "nopMethod", null);
            module.registerMethod("lib_init_callbacks",   "nopMethod", null);
            module.registerMethod("lib_cleanup_callbacks","nopMethod", null);

            // InputCallback methods (nop stubs — no native callback support)
            for (String m : new String[]{"lib_init_callbacks","lib_cleanup_callbacks"}) {
                module.registerMethodInPackage("XML::LibXML::InputCallback", m, "nopMethod");
            }

            // Node methods
            String nodePkg = "XML::LibXML::Node";
            String[][] nodeMethods = {
                {"nodeName"},   {"nodeValue"},   {"nodeType"},
                {"getName", "nodeName"},
                {"parentNode"}, {"childNodes"},  {"firstChild"}, {"lastChild"},
                {"previousSibling"}, {"nextSibling"},
                {"attributes"}, {"hasAttributes"},
                {"cloneNode"},
                {"appendChild"}, {"insertBefore"}, {"insertAfter"},
                {"removeChild"}, {"replaceChild"}, {"replaceNode"}, {"unbindNode"},
                {"hasChildNodes"},
                {"textContent"}, {"string_value"},
                {"ownerDocument"}, {"getOwnerDocument"},
                {"isSameNode"},
                {"localname"}, {"getLocalName", "localname"},
                {"prefix"}, {"getPrefix", "prefix"},
                {"namespaceURI"}, {"getNamespaceURI", "namespaceURI"},
                {"nodePath"}, {"line_number"},
                {"localNS"}, {"getNamespaces"},
                {"appendText"},
                {"getData"}, {"setData"},
                {"setNamespace"},
                {"findnodes"}, {"find"}, {"exists"},
                {"unique_key"},
                // underscore-prefixed aliases used by LibXML.pm Perl wrappers
                {"_findnodes", "findnodes"}, {"_find", "nodeFindRaw"},
                {"toString"},
                // baseURI getter/setter
                {"baseURI",    "nodeBaseURI"},
                {"setBaseURI", "nodeSetBaseURI"},
                // node add/remove siblings/children
                {"addSibling", "nodeAddSibling"},
                {"addChild",   "addChildNode"},
                // XS underscore aliases needed by original LibXML.pm Perl wrappers
                {"_childNodes",  "childNodesFiltered"},  // _childNodes(onlyNonBlank flag)
                {"_attributes",  "attributes"},           // _attributes() called by attributes()
                {"_toString",    "toString"},              // _toString(format) called by toString()
                {"_isEqual",     "nodeIsEqual"},           // _isEqual(other) for isEqualNode()
                {"_toStringC14N","toStringC14N"},
                // Additional node methods
                {"removeChildNodes"},
                {"lookupNamespacePrefix"},
                {"firstNonBlankChild"},
                {"nextNonBlankSibling"},
                {"previousNonBlankSibling"},
                {"setNodeName"},
                {"_getNamespaceDeclURI", "getNamespaceDeclURI"},
                {"setNamespaceDeclURI"},
            };
            for (String[] m : nodeMethods) {
                module.registerMethodInPackage(nodePkg, m[0], m.length > 1 ? m[1] : m[0]);
            }

            // Document methods
            String docPkg = "XML::LibXML::Document";
            String[][] docMethods = {
                {"documentElement"},   {"setDocumentElement"},
                // aliases for documentElement
                {"getDocumentElement", "documentElement"},
                {"createElement"},     {"createElementNS"},
                {"createRawElement",   "createElement"},
                {"createRawElementNS", "createElementNS"},
                {"createTextNode"},    {"createComment"},
                {"createCDATASection"},
                {"createProcessingInstruction", "docCreatePI"},
                {"createPI",                    "docCreatePI"},
                {"createAttribute"},   {"createAttributeNS"},
                {"createDocumentFragment"},
                {"createDocument",   "docCreateDocument"},
                {"createExternalSubset", "docCreateExternalSubset"},
                {"createInternalSubset", "docCreateInternalSubset"},
                {"importNode"},        {"adoptNode"},
                {"toString",         "documentToString"},
                {"serialize",        "documentToString"},
                {"toFile"},
                {"toFH",             "docToFH"},
                {"URI",              "documentURI"},
                {"setURI",           "setDocumentURI"},
                {"encoding",         "documentEncoding"},
                {"getEncoding",      "documentEncoding"},
                {"actualEncoding",   "documentEncoding"},
                {"setEncoding",      "setDocumentEncoding"},
                {"version",          "documentVersion"},
                {"getVersion",       "documentVersion"},
                {"setVersion",       "setDocumentVersion"},
                {"standalone",       "documentStandalone"},
                {"setStandalone",    "setDocumentStandalone"},
                {"internalSubset",   "documentInternalSubset"},
                {"externalSubset",   "documentExternalSubset"},
                // childNodes alias
                {"getChildnodes",    "childNodes"},
                // compression: libxml2 gzip level, -1 = no compression at all
                {"compression",      "docCompression"},
                {"setCompression",   "docSetCompression"},
                // XPath-style search on Document (mirrors Element)
                {"getElementsByTagName"},
                {"getElementsByTagNameNS"},
                {"getElementsByLocalName"},
                // XS underscore aliases needed by original LibXML.pm Perl wrappers
                {"_setDocumentElement", "setDocumentElement"},
                {"_toString",           "documentToString"},
                // Additional document methods
                {"createEntityReference"},
                {"getElementById"},
            };
            for (String[] m : docMethods) {
                module.registerMethodInPackage(docPkg, m[0], m.length > 1 ? m[1] : m[0]);
            }

            // Element methods
            String elemPkg = "XML::LibXML::Element";
            String[][] elemMethods = {
                {"getAttribute"},    {"getAttributeNS"},
                {"setAttribute"},    {"setAttributeNS"},
                {"removeAttribute"}, {"removeAttributeNS"},
                {"hasAttribute"},    {"hasAttributeNS"},
                {"getAttributeNode"},  {"setAttributeNode"},  {"setAttributeNodeNS"},
                {"getAttributeNodeNS"},
                {"getElementsByTagName"},
                {"getElementsByTagNameNS"},
                {"getElementsByLocalName"},
                {"getChildrenByTagName"},
                {"getChildrenByLocalName"},
                {"getChildrenByTagNameNS"},
                {"appendTextChild"},
                {"appendWellBalancedChunk"},
                {"addNewChild"},
                // aliases / extra
                {"tagName",            "nodeName"},
                {"lookupNamespaceURI", "elemLookupNamespaceURI"},
                {"getNamespaces",      "elemGetNamespaces"},
                {"removeAttributeNode","elemRemoveAttributeNode"},
                // XS underscore aliases needed by original LibXML.pm Perl wrappers
                {"_getAttribute",    "getAttribute"},
                {"_getAttributeNS",  "getAttributeNS"},
                {"_setAttribute",    "setAttribute"},
                {"_setAttributeNS",  "setAttributeNS"},
                {"_setNamespace",    "setNamespace"},
                {"_getNamespaceDeclURI", "getNamespaceDeclURI"},
                {"setNamespaceDeclURI"},
                {"setNamespaceDeclPrefix"},
                {"lookupNamespacePrefix"},
                {"setNodeName"},
                {"_getChildrenByTagNameNS", "getChildrenByTagNameNS"},
            };
            for (String[] m : elemMethods) {
                module.registerMethodInPackage(elemPkg, m[0], m.length > 1 ? m[1] : m[0]);
            }
            // Element constructor: XML::LibXML::Element->new($name)
            module.registerMethodInPackage(elemPkg, "new", "elemNew");

            // Attr methods
            module.registerMethodInPackage("XML::LibXML::Attr", "name",             "attrName");
            module.registerMethodInPackage("XML::LibXML::Attr", "value",            "attrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "getValue",         "attrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "setValue",         "setAttrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "ownerElement",     "attrOwnerElement");
            module.registerMethodInPackage("XML::LibXML::Attr", "isId",             "attrIsId");
            module.registerMethodInPackage("XML::LibXML::Attr", "_setData",         "setAttrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "_setNamespace",    "setNamespace");
            module.registerMethodInPackage("XML::LibXML::Attr", "setNodeName",      "setNodeName");
            module.registerMethodInPackage("XML::LibXML::Attr", "serializeContent", "attrSerializeContent");
            module.registerMethodInPackage("XML::LibXML::Attr", "toString",         "attrToString");

            // Text / CDATASection
            module.registerMethodInPackage("XML::LibXML::Text", "data",    "getData");
            module.registerMethodInPackage("XML::LibXML::Text", "setData", "setData");
            module.registerMethodInPackage("XML::LibXML::Text", "new",     "textNew");
            module.registerMethodInPackage("XML::LibXML::Comment", "new",  "commentNew");
            // CharacterData methods (Text, CDATASection, Comment)
            for (String cdPkg : new String[]{"XML::LibXML::Text", "XML::LibXML::CDATASection", "XML::LibXML::Comment"}) {
                module.registerMethodInPackage(cdPkg, "substringData",    "charSubstringData");
                module.registerMethodInPackage(cdPkg, "appendData",       "charAppendData");
                module.registerMethodInPackage(cdPkg, "insertData",       "charInsertData");
                module.registerMethodInPackage(cdPkg, "deleteData",       "charDeleteData");
                module.registerMethodInPackage(cdPkg, "replaceData",      "charReplaceData");
                module.registerMethodInPackage(cdPkg, "length",           "charLength");
                module.registerMethodInPackage(cdPkg, "replaceDataString","charReplaceDataString");
                module.registerMethodInPackage(cdPkg, "replaceDataRegEx", "charReplaceDataRegEx");
            }
            module.registerMethodInPackage("XML::LibXML::Text", "splitText", "textSplitText");
            module.registerMethodInPackage("XML::LibXML::CDATASection", "data",    "getData");
            module.registerMethodInPackage("XML::LibXML::CDATASection", "setData", "setData");

            // PI
            module.registerMethodInPackage("XML::LibXML::PI", "target",  "piTarget");
            module.registerMethodInPackage("XML::LibXML::PI", "data",    "piData");
            module.registerMethodInPackage("XML::LibXML::PI", "setData", "piSetData");
            module.registerMethodInPackage("XML::LibXML::PI", "_setData","piSetData"); // XS alias

            // Namespace
            module.registerMethodInPackage("XML::LibXML::Namespace", "_isEqual", "namespaceIsEqual");

            // XPathContext
            String xpcPkg = "XML::LibXML::XPathContext";
            module.registerMethodInPackage(xpcPkg, "new",                 "xpcNew");
            module.registerMethodInPackage(xpcPkg, "setContextNode",      "xpcSetContextNode");
            module.registerMethodInPackage(xpcPkg, "getContextNode",      "xpcGetContextNode");
            module.registerMethodInPackage(xpcPkg, "registerNs",          "xpcRegisterNs");
            module.registerMethodInPackage(xpcPkg, "unregisterNs",        "xpcUnregisterNs");
            module.registerMethodInPackage(xpcPkg, "_findnodes",          "xpcFindNodes");
            module.registerMethodInPackage(xpcPkg, "_find",               "xpcFind");
            module.registerMethodInPackage(xpcPkg, "_free_node_pool",     "xpcFreeNodePool");
            module.registerMethodInPackage(xpcPkg, "registerFunctionNS",       "xpcRegisterFunctionNS");
            module.registerMethodInPackage(xpcPkg, "registerVarLookupFunc",    "xpcRegisterVarLookupFunc");

            // Common
            module.registerMethodInPackage("XML::LibXML::Common", "encodeToUTF8",   "encodeToUTF8");
            module.registerMethodInPackage("XML::LibXML::Common", "decodeFromUTF8", "decodeFromUTF8");

            // XPathExpression
            String xpePkg = "XML::LibXML::XPathExpression";
            module.registerMethodInPackage(xpePkg, "new",        "xpeNew");
            module.registerMethodInPackage(xpePkg, "expression", "xpeExpression");

            setupISA();

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing XMLLibXML method: " + e.getMessage());
        } catch (RuntimeException e) {
            // registerMethodInPackage wraps NoSuchMethodException in RuntimeException
            System.err.println("Warning: XMLLibXML.initialize() failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void setupISA() {
        String[] nodeSubclasses = {
            "XML::LibXML::Element",   "XML::LibXML::Document",
            "XML::LibXML::Text",      "XML::LibXML::Comment",
            "XML::LibXML::PI",        "XML::LibXML::Attr",
            "XML::LibXML::DocumentFragment", "XML::LibXML::Dtd",
        };
        for (String cls : nodeSubclasses) {
            RuntimeArray isa = GlobalVariable.getGlobalArray(cls + "::ISA");
            boolean found = false;
            for (int i = 0; i < isa.size(); i++) {
                if ("XML::LibXML::Node".equals(isa.get(i).toString())) { found = true; break; }
            }
            if (!found) RuntimeArray.push(isa, new RuntimeScalar("XML::LibXML::Node"));
        }
        // CDATASection isa Text isa Node
        RuntimeArray cdata = GlobalVariable.getGlobalArray("XML::LibXML::CDATASection::ISA");
        boolean hasTxt = false;
        for (int i = 0; i < cdata.size(); i++) {
            if ("XML::LibXML::Text".equals(cdata.get(i).toString())) { hasTxt = true; break; }
        }
        if (!hasTxt) RuntimeArray.push(cdata, new RuntimeScalar("XML::LibXML::Text"));
    }

    // ================================================================
    // Node wrapping helpers
    // ================================================================

    static RuntimeScalar wrapNode(Node node) {
        if (node == null) return scalarUndef;
        RuntimeHash hash = new RuntimeHash();
        hash.put(NODE_KEY, new RuntimeScalar(node));
        String perlClass = nodeTypeToPerlClass(node);
        RuntimeScalar ref = hash.createReferenceWithTrackedElements();
        return ReferenceOperators.bless(ref, new RuntimeScalar(perlClass));
    }

    static Node getNode(RuntimeScalar self) {
        if (self == null || self.type == RuntimeScalarType.UNDEF) return null;
        RuntimeHash hash;
        try { hash = self.hashDerefRaw(); }
        catch (Exception e) {
            throw new RuntimeException("Not a valid XML::LibXML node (cannot hashderef): " + self);
        }
        RuntimeScalar ns = hash.get(NODE_KEY);
        if (ns != null && ns.type == RuntimeScalarType.JAVAOBJECT && ns.value instanceof Node) {
            return (Node) ns.value;
        }
        throw new RuntimeException("Not a valid XML::LibXML node (missing " + NODE_KEY + " key)");
    }

    /**
     * Update the Java Node stored in a Perl XML::LibXML node object.
     * Needed after Document.renameNode() which may return a new Node instance.
     */
    static void updateNode(RuntimeScalar self, Node newNode) {
        if (self == null || self.type == RuntimeScalarType.UNDEF) return;
        try {
            RuntimeHash hash = self.hashDerefRaw();
            RuntimeScalar ns = hash.get(NODE_KEY);
            if (ns != null && ns.type == RuntimeScalarType.JAVAOBJECT) {
                ns.value = newNode;
            }
        } catch (Exception e) { /* ignore */ }
    }

    /**
     * Cascade namespace removal: rename element and its attributes/child-elements
     * that use the given prefix so they have no namespace and no prefix.
     * Recurses into child elements unless they have their own re-declaration
     * of the same prefix.
     * Returns the (possibly new) element node after renaming.
     */
    private static Element removePrefixFromSubtree(Element el, String prefix, Document doc) {
        // Rename element itself if it uses the given prefix
        String elPfx = el.getPrefix();
        String normalised = (elPfx != null) ? elPfx : "";
        if (normalised.equals(prefix)) {
            try {
                String localName = el.getLocalName();
                if (localName == null) localName = el.getNodeName();
                if (localName.contains(":")) localName = localName.substring(localName.indexOf(':') + 1);
                Node renamed = doc.renameNode(el, null, localName);
                if (renamed instanceof Element) el = (Element) renamed;
            } catch (Exception e) { /* ignore */ }
        }
        // Rename attributes that use the given prefix (collect first to avoid ConcurrentModification)
        NamedNodeMap attrs = el.getAttributes();
        List<Attr> toRename = new java.util.ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node n = attrs.item(i);
            if (n instanceof Attr) {
                Attr attr = (Attr) n;
                String aPfx = attr.getPrefix();
                // Skip xmlns declarations themselves
                if ("xmlns".equals(aPfx) || "xmlns".equals(attr.getNodeName())) continue;
                if (aPfx != null && aPfx.equals(prefix)) {
                    toRename.add(attr);
                }
            }
        }
        for (Attr attr : toRename) {
            try {
                String localName = attr.getLocalName();
                if (localName == null || localName.isEmpty()) {
                    String nm = attr.getNodeName();
                    localName = nm.contains(":") ? nm.substring(nm.indexOf(':') + 1) : nm;
                }
                doc.renameNode(attr, null, localName);
            } catch (Exception e) { /* ignore */ }
        }
        // Recurse into child elements (skip if child has its own xmlns:prefix declaration)
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element childEl = (Element) child;
                // Check if this child has its own declaration for the prefix
                String decl = childEl.getAttributeNS("http://www.w3.org/2000/xmlns/", prefix.isEmpty() ? "xmlns" : prefix);
                if (decl == null || decl.isEmpty()) decl = childEl.getAttribute(prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix);
                if (decl == null || decl.isEmpty()) {
                    removePrefixFromSubtree(childEl, prefix, doc);
                }
            }
        }
        return el;
    }

    /**
     * Normalise a Perl namespace-URI argument: undef or empty string → null (no namespace).
     * The JDK DOM treats "" and null differently in NS-aware methods, but libxml2 / XML::LibXML
     * treat both as "no namespace".
     */
    private static String nsArg(RuntimeScalar arg) {
        if (arg == null || arg.type == RuntimeScalarType.UNDEF) return null;
        String s = arg.toString();
        return s.isEmpty() ? null : s;
    }

    static String nodeTypeToPerlClass(Node node) {
        return switch (node.getNodeType()) {
            case Node.ELEMENT_NODE                -> "XML::LibXML::Element";
            case Node.TEXT_NODE                   -> "XML::LibXML::Text";
            case Node.CDATA_SECTION_NODE          -> "XML::LibXML::CDATASection";
            case Node.COMMENT_NODE                -> "XML::LibXML::Comment";
            case Node.PROCESSING_INSTRUCTION_NODE -> "XML::LibXML::PI";
            case Node.ATTRIBUTE_NODE              -> "XML::LibXML::Attr";
            case Node.DOCUMENT_NODE               -> "XML::LibXML::Document";
            case Node.DOCUMENT_FRAGMENT_NODE      -> "XML::LibXML::DocumentFragment";
            case Node.DOCUMENT_TYPE_NODE          -> "XML::LibXML::Dtd";
            default                               -> "XML::LibXML::Node";
        };
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;");
    }

    private static String serializeNode(Node node, boolean format, boolean withDecl) {
        // Attr node: libxml2 serializes as ' name="value"' (with leading space)
        if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
            Attr a = (Attr) node;
            return " " + a.getName() + "=\"" + escapeXmlAttr(a.getValue()) + "\"";
        }
        // Respect $XML::LibXML::skipXMLDeclaration
        if (withDecl && GlobalVariable.getGlobalVariable("XML::LibXML::skipXMLDeclaration").getBoolean()) {
            withDecl = false;
        }
        // Determine what encoding to use in the output XML declaration
        String outputEncoding = "UTF-8";
        boolean removeEncoding = false;
        if (withDecl && node instanceof Document) {
            Document doc = (Document) node;
            Object ud = doc.getUserData(UDATA_ENCODING);
            if (ud instanceof String) {
                String enc = (String) ud;
                if (enc.isEmpty()) {   // ENCODING_CLEARED sentinel: omit encoding= from decl
                    removeEncoding = true;
                } else {
                    outputEncoding = enc;
                }
            }
        }
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer tr = tf.newTransformer();
            tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, withDecl ? "no" : "yes");
            tr.setOutputProperty(OutputKeys.ENCODING, outputEncoding);
            if (format) {
                tr.setOutputProperty(OutputKeys.INDENT, "yes");
                tr.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            }
            StringWriter sw = new StringWriter();
            tr.transform(new DOMSource(node), new StreamResult(sw));
            String result = sw.toString();
            // Remove standalone="no" from declaration to match libxml2 output style
            if (withDecl) {
                result = result.replace(" standalone=\"no\"", "");
                // Remove encoding attribute when it was explicitly cleared
                if (removeEncoding) {
                    result = result.replaceFirst(" encoding=\"[^\"]*\"", "");
                }
                // libxml2 always emits a newline between the XML declaration and content
                int declEnd = result.indexOf("?>") + 2;
                if (declEnd > 2 && declEnd < result.length() && result.charAt(declEnd) != '\n') {
                    result = result.substring(0, declEnd) + "\n" + result.substring(declEnd);
                }
                // libxml2 always ends document serialization with a trailing newline
                if (!result.endsWith("\n")) {
                    result = result + "\n";
                }
            }
            // $XML::LibXML::setTagCompression = 1 serializes empty elements as <foo></foo>
            if (GlobalVariable.getGlobalVariable("XML::LibXML::setTagCompression").getBoolean()) {
                result = result.replaceAll("<([\\w:.-]+)([^>]*?)/>", "<$1$2></$1>");
            }
            return result;
        } catch (TransformerException e) {
            throw new RuntimeException("XML serialization error: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // Parser helpers
    // ================================================================

    private static final int XML_PARSE_NOBLANKS = 256; // keep_blanks(0) sets this flag
    private static final String PARSER_OPTIONS_KEY = "XML_LIBXML_PARSER_OPTIONS";

    private static ParserOptions getParserOptions(RuntimeScalar self) {
        RuntimeHash hash = self.hashDeref();
        RuntimeScalar os = hash.get(OPTS_KEY);
        ParserOptions opts;
        if (os != null && os.type == RuntimeScalarType.JAVAOBJECT
                && os.value instanceof ParserOptions) {
            opts = (ParserOptions) os.value;
        } else {
            opts = new ParserOptions();
        }
        // Also check the Perl-level XML_LIBXML_PARSER_OPTIONS flag set by keep_blanks() etc.
        RuntimeScalar flagsScalar = hash.get(PARSER_OPTIONS_KEY);
        if (flagsScalar != null && flagsScalar.type != RuntimeScalarType.UNDEF) {
            int flags = flagsScalar.getInt();
            if ((flags & XML_PARSE_NOBLANKS) != 0) opts.keepBlanks = false;
            opts.expandEntities = (flags & 2) != 0; // XML_PARSE_NOENT = 2
        }
        return opts;
    }

    private static DocumentBuilder newBuilder(ParserOptions opts) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            if (!opts.keepBlanks) f.setIgnoringElementContentWhitespace(true);
            // When expand_entities is false, keep EntityReference nodes in the tree
            f.setExpandEntityReferences(opts.expandEntities);
            DocumentBuilder db = f.newDocumentBuilder();
            db.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {}
                public void error(SAXParseException e) throws SAXException {
                    if (!opts.recover) throw e;
                }
                public void fatalError(SAXParseException e) throws SAXException {
                    if (!opts.recover) throw e;
                }
            });
            return db;
        } catch (Exception e) {
            throw new RuntimeException("Cannot create DocumentBuilder: " + e.getMessage(), e);
        }
    }

    /**
     * Strip whitespace-only text nodes from the DOM tree.
     * Required when keepBlanks=false and no DTD is present (JAXP's
     * setIgnoringElementContentWhitespace only works with DTD-defined content models).
     */
    private static void stripBlankTextNodes(Node node) {
        NodeList children = node.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String val = child.getNodeValue();
                if (val != null && val.trim().isEmpty()) {
                    toRemove.add(child);
                }
            } else {
                stripBlankTextNodes(child);
            }
        }
        for (Node n : toRemove) node.removeChild(n);
    }

    // ================================================================
    // Parser methods
    // ================================================================

    public static RuntimeList _new_parser(RuntimeArray args, int ctx) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(OPTS_KEY, new RuntimeScalar(new ParserOptions()));
        return hash.createReferenceWithTrackedElements().getList();
    }

    public static RuntimeList _keep_blanks(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        boolean val = args.size() < 2 || args.get(1).getBoolean();
        getParserOptions(self).keepBlanks = val;
        return self.getList();
    }

    public static RuntimeList _parse_string(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeScalar strArg = args.get(1);
        // libxml2 accepts a reference to a scalar — dereference if plain (unblessed) ref
        if (strArg.type == RuntimeScalarType.REFERENCE) {
            boolean isBlessed = (strArg.value instanceof RuntimeBase) && ((RuntimeBase) strArg.value).blessId != 0;
            if (!isBlessed) {
                // Unblessed scalar ref: \$string — dereference to get the string
                strArg = ((RuntimeScalar) strArg.value).scalar();
            }
            // Blessed ref: fall through — toString() will invoke "" overload
        }
        // libxml2 throws "Empty String" for undef or empty input
        if (strArg.type == RuntimeScalarType.UNDEF) {
            return WarnDie.die(new RuntimeScalar("Empty String\n"),
                new RuntimeScalar("\n")).getList();
        }
        String xmlStr = strArg.toString();
        ParserOptions opts = getParserOptions(self);
        try {
            DocumentBuilder db = newBuilder(opts);
            Document doc;
            // Detect binary XML (UTF-16/UTF-32 BOM) and parse as byte stream
            // so the parser can auto-detect the encoding from the BOM/declaration
            byte[] xmlBytes = xmlStr.getBytes(StandardCharsets.ISO_8859_1);
            if (hasBinaryXmlBom(xmlBytes)) {
                InputSource is = new InputSource(new ByteArrayInputStream(xmlBytes));
                doc = db.parse(is);
            } else {
                doc = db.parse(new InputSource(new StringReader(xmlStr)));
            }
            if (!opts.keepBlanks) stripBlankTextNodes(doc);
            // Store detected encoding in user data for getEncoding() calls
            String declEnc = doc.getXmlEncoding();
            if (declEnc != null && doc.getUserData(UDATA_ENCODING) == null) {
                doc.setUserData(UDATA_ENCODING, declEnc, null);
            }
            return wrapNode(doc).getList();
        } catch (SAXParseException e) {
            // Format: "file:line: parser error : message"
            String msg = ":" + e.getLineNumber() + ": parser error : " + e.getMessage();
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_string: " + msg + "\n"),
                new RuntimeScalar("\n")).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_string: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    private static boolean hasBinaryXmlBom(byte[] b) {
        if (b.length < 2) return false;
        // UTF-16 BE BOM: FE FF
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return true;
        // UTF-16 LE BOM: FF FE
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return true;
        // UTF-32 BE BOM: 00 00 FE FF
        if (b.length >= 4 && b[0] == 0x00 && b[1] == 0x00 && (b[2] & 0xFF) == 0xFE && (b[3] & 0xFF) == 0xFF) return true;
        // UTF-16 LE without BOM: '<' = 0x3C 0x00
        if (b[0] == 0x3C && b[1] == 0x00) return true;
        // UTF-16 BE without BOM: '<' = 0x00 0x3C
        if (b[0] == 0x00 && b[1] == 0x3C) return true;
        // UTF-32 LE without BOM: '<' = 0x3C 0x00 0x00 0x00
        if (b.length >= 4 && b[0] == 0x3C && b[1] == 0x00 && b[2] == 0x00 && b[3] == 0x00) return true;
        return false;
    }

    /**
     * Map JDK SAX parser error messages to libxml2-compatible messages.
     * The tests use like() with libxml2 message patterns.
     */
    private static String normalizeSaxError(String jdkMsg) {
        if (jdkMsg == null) return "";
        // JDK: "The markup in the document following the root element must be well-formed."
        // libxml2: "Extra content at the end of the document"
        if (jdkMsg.contains("markup in the document following the root element")) {
            return "Extra content at the end of the document";
        }
        return jdkMsg;
    }

    public static RuntimeList _parse_file(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        String filename = args.get(1).toString();
        ParserOptions opts = getParserOptions(self);
        File f = new File(filename);
        if (!f.exists()) {
            // Match libxml2 error: Could not create file parser context for file "...": No such file or directory
            return WarnDie.die(new RuntimeScalar(
                "Could not create file parser context for file \"" + filename + "\": No such file or directory\n"),
                new RuntimeScalar("\n")).getList();
        }
        try {
            DocumentBuilder db = newBuilder(opts);
            Document doc = db.parse(f);
            if (!opts.keepBlanks) stripBlankTextNodes(doc);
            doc.setDocumentURI(f.toURI().toString());
            return wrapNode(doc).getList();
        } catch (SAXParseException e) {
            // Format expected by tests: "filename:line: parser error : message"
            String msg = filename + ":" + e.getLineNumber() + ": parser error : " + normalizeSaxError(e.getMessage());
            return WarnDie.die(new RuntimeScalar(msg + "\n"),
                new RuntimeScalar("\n")).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_file: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList _parse_fh(RuntimeArray args, int ctx) {
        // args: (self, $fh, $uri)
        // Read all content from the Perl filehandle, then parse as string
        RuntimeScalar self = args.get(0);
        RuntimeScalar fhArg = args.size() > 1 ? args.get(1) : scalarUndef;
        ParserOptions opts = getParserOptions(self);

        // If fhArg is undef, mimic the Perl error for using undef as a symbol reference
        if (fhArg == null || fhArg.type == RuntimeScalarType.UNDEF) {
            return WarnDie.die(new RuntimeScalar(
                "Can't use an undefined value as a symbol reference"),
                new RuntimeScalar("\n")).getList();
        }

        // Try to read from the filehandle via Perl's readline (slurp all lines)
        String xmlStr;
        try {
            org.perlonjava.runtime.runtimetypes.RuntimeBase content =
                org.perlonjava.runtime.operators.Readline.readline(fhArg, RuntimeContextType.LIST);
            // content is a RuntimeList or RuntimeScalar; join into one string
            if (content instanceof RuntimeList rl) {
                StringBuilder sb = new StringBuilder();
                for (var elem : rl.elements) sb.append(elem.toString());
                xmlStr = sb.toString();
            } else {
                RuntimeScalar sc = (RuntimeScalar) content;
                if (sc.type == RuntimeScalarType.UNDEF) {
                    // Empty content or error → treat as undef FH
                    return WarnDie.die(new RuntimeScalar(
                        "Can't use an undefined value as a symbol reference"),
                        new RuntimeScalar("\n")).getList();
                }
                xmlStr = sc.toString();
            }
        } catch (Exception e) {
            // Fallback: stringify (e.g. for plain strings passed instead of FH)
            xmlStr = fhArg.toString();
        }

        try {
            DocumentBuilder db = newBuilder(opts);
            Document doc = db.parse(new InputSource(new StringReader(xmlStr)));
            if (!opts.keepBlanks) stripBlankTextNodes(doc);
            return wrapNode(doc).getList();
        } catch (SAXParseException e) {
            String msg = "Entity: line " + e.getLineNumber() + ": parser error : " + normalizeSaxError(e.getMessage());
            return WarnDie.die(new RuntimeScalar(msg + "\n"), new RuntimeScalar("\n")).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_fh: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList _parse_html_string(RuntimeArray args, int ctx) {
        // Try to parse as XML first; if that fails due to unclosed void HTML
        // elements, self-close them and retry.
        try {
            return _parse_string(args, ctx);
        } catch (Exception e) {
            // Fall through to HTML-aware fallback
        }
        RuntimeScalar self   = args.get(0);
        RuntimeScalar strArg = args.size() > 1 ? args.get(1) : scalarUndef;
        if (strArg.type == RuntimeScalarType.UNDEF) {
            return WarnDie.die(new RuntimeScalar("Empty String\n"), new RuntimeScalar("\n")).getList();
        }
        String html = strArg.toString();
        // Self-close HTML void elements that are not already self-closed
        String[] voidElements = {"area","base","br","col","embed","hr","img",
                                  "input","link","meta","param","source","track","wbr"};
        for (String tag : voidElements) {
            // Replace <tag ...> (not already self-closed) with <tag .../>
            html = html.replaceAll("(?i)<(" + tag + ")(\\s[^>]*)?>", "<$1$2/>");
            html = html.replaceAll("(?i)<(" + tag + ")>", "<$1/>");
        }
        RuntimeArray newArgs = new RuntimeArray();
        RuntimeArray.push(newArgs, self);
        RuntimeArray.push(newArgs, new RuntimeScalar(html));
        return _parse_string(newArgs, ctx);
    }

    public static RuntimeList _parse_html_fh(RuntimeArray args, int ctx) {
        // Read from the filehandle first, then parse as HTML string
        RuntimeScalar self = args.get(0);
        RuntimeScalar fhArg = args.size() > 1 ? args.get(1) : scalarUndef;
        if (fhArg == null || fhArg.type == RuntimeScalarType.UNDEF) {
            return WarnDie.die(new RuntimeScalar(
                "Can't use an undefined value as a symbol reference"),
                new RuntimeScalar("\n")).getList();
        }
        String htmlStr;
        try {
            org.perlonjava.runtime.runtimetypes.RuntimeBase content =
                org.perlonjava.runtime.operators.Readline.readline(fhArg, RuntimeContextType.LIST);
            if (content instanceof RuntimeList rl) {
                StringBuilder sb = new StringBuilder();
                for (var elem : rl.elements) sb.append(elem.toString());
                htmlStr = sb.toString();
            } else {
                htmlStr = content.toString();
            }
        } catch (Exception e) {
            htmlStr = fhArg.toString();
        }
        RuntimeArray newArgs = new RuntimeArray();
        RuntimeArray.push(newArgs, self);
        RuntimeArray.push(newArgs, new RuntimeScalar(htmlStr));
        return _parse_html_string(newArgs, ctx);
    }

    public static RuntimeList _parse_html_file(RuntimeArray args, int ctx) {
        // Read from the file, then parse as HTML string
        RuntimeScalar self = args.get(0);
        String filename = args.size() > 1 ? args.get(1).toString() : "";
        try {
            String htmlStr = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filename)));
            RuntimeArray newArgs = new RuntimeArray();
            RuntimeArray.push(newArgs, self);
            RuntimeArray.push(newArgs, new RuntimeScalar(htmlStr));
            return _parse_html_string(newArgs, ctx);
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_html_file: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    // ================================================================
    // Push / incremental parsing
    // ================================================================

    /** Context object for push (incremental) parsing. Buffers all chunks. */
    static class PushContext {
        final StringBuilder buffer = new StringBuilder();
    }

    /** _start_push(sax): initialise a push context and return it. */
    public static RuntimeList _start_push(RuntimeArray args, int ctx) {
        PushContext pctx = new PushContext();
        RuntimeScalar wrapped = new RuntimeScalar();
        wrapped.type = RuntimeScalarType.JAVAOBJECT;
        wrapped.value = pctx;
        return wrapped.getList();
    }

    /** _push(context, chunk): append a chunk to the push context. */
    public static RuntimeList _push(RuntimeArray args, int ctx) {
        // args: (self, context, chunk)
        if (args.size() < 3) return scalarUndef.getList();
        RuntimeScalar ctxScalar = args.get(1);
        String chunk = args.get(2).toString();
        if (ctxScalar.type == RuntimeScalarType.JAVAOBJECT && ctxScalar.value instanceof PushContext) {
            ((PushContext) ctxScalar.value).buffer.append(chunk);
        }
        return scalarTrue.getList();
    }

    /** _end_push(context, recover): finish push parsing and return document. */
    public static RuntimeList _end_push(RuntimeArray args, int ctx) {
        // args: (self, context, recover_flag)
        RuntimeScalar self = args.get(0);
        RuntimeScalar ctxScalar = args.size() > 1 ? args.get(1) : scalarUndef;
        if (ctxScalar.type != RuntimeScalarType.JAVAOBJECT || !(ctxScalar.value instanceof PushContext)) {
            return WarnDie.die(new RuntimeScalar("push context is invalid\n"),
                new RuntimeScalar("\n")).getList();
        }
        String xmlStr = ((PushContext) ctxScalar.value).buffer.toString();
        if (xmlStr.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("Empty String\n"),
                new RuntimeScalar("\n")).getList();
        }
        ParserOptions opts = getParserOptions(self);
        try {
            DocumentBuilder db = newBuilder(opts);
            Document doc = db.parse(new InputSource(new StringReader(xmlStr)));
            if (!opts.keepBlanks) stripBlankTextNodes(doc);
            String declEnc = doc.getXmlEncoding();
            if (declEnc != null && doc.getUserData(UDATA_ENCODING) == null) {
                doc.setUserData(UDATA_ENCODING, declEnc, null);
            }
            return wrapNode(doc).getList();
        } catch (SAXParseException e) {
            String msg = ":" + e.getLineNumber() + ": parser error : " + e.getMessage();
            return WarnDie.die(new RuntimeScalar("XML::LibXML::push_parse: " + msg + "\n"),
                new RuntimeScalar("\n")).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::push_parse: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    /** _parse_xml_chunk(chunk[, encoding]): parse a well-balanced XML fragment. */
    public static RuntimeList _parse_xml_chunk(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        if (args.size() < 2) {
            return WarnDie.die(new RuntimeScalar("Empty String\n"),
                new RuntimeScalar("\n")).getList();
        }
        RuntimeScalar chunkArg = args.get(1);
        if (chunkArg.type == RuntimeScalarType.UNDEF || chunkArg.toString().isEmpty()) {
            return WarnDie.die(new RuntimeScalar("Empty String\n"),
                new RuntimeScalar("\n")).getList();
        }
        String chunk = chunkArg.toString();

        // Wrap in a synthetic root so we can parse as a document
        String wrapped = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><__xml_chunk__>" + chunk + "</__xml_chunk__>";
        ParserOptions opts = getParserOptions(self);
        Document wrapDoc;
        try {
            DocumentBuilder db = newBuilder(opts);
            wrapDoc = db.parse(new InputSource(new StringReader(wrapped)));
        } catch (SAXParseException e) {
            String msg = ":" + (e.getLineNumber() - 1) + ": parser error : " + e.getMessage();
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_xml_chunk: " + msg + "\n"),
                new RuntimeScalar("\n")).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_xml_chunk: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }

        // Create a standalone document, move the children of __xml_chunk__ into a DocumentFragment
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db2 = dbf.newDocumentBuilder();
            Document fragDoc = db2.newDocument();
            DocumentFragment frag = fragDoc.createDocumentFragment();
            org.w3c.dom.Element wrapper = wrapDoc.getDocumentElement();
            NodeList children = wrapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                org.w3c.dom.Node imported = fragDoc.importNode(child, true);
                frag.appendChild(imported);
            }
            return wrapNode(frag).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::parse_xml_chunk: fragment error: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList LIBXML_RUNTIME_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar("20914").getList();
    }

    public static RuntimeList LIBXML_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(20914).getList();
    }

    public static RuntimeList INIT_THREAD_SUPPORT(RuntimeArray args, int ctx) {
        return scalarFalse.getList();
    }

    public static RuntimeList DISABLE_THREAD_SUPPORT(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    // ================================================================
    // XML::LibXML::Node methods
    // ================================================================

    public static RuntimeList nodeName(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String name = n.getNodeName();
        return new RuntimeScalar(name != null ? name : "").getList();
    }

    public static RuntimeList nodeValue(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String val = n.getNodeValue();
        return (val == null ? scalarUndef : new RuntimeScalar(val)).getList();
    }

    public static RuntimeList nodeType(RuntimeArray args, int ctx) {
        return new RuntimeScalar(getNode(args.get(0)).getNodeType()).getList();
    }

    public static RuntimeList parentNode(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getParentNode()).getList();
    }

    public static RuntimeList childNodes(RuntimeArray args, int ctx) {
        NodeList children = getNode(args.get(0)).getChildNodes();
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList result = new RuntimeList();
            for (int i = 0; i < children.getLength(); i++) result.add(wrapNode(children.item(i)));
            return result;
        }
        RuntimeArray arr = new RuntimeArray();
        for (int i = 0; i < children.getLength(); i++) RuntimeArray.push(arr, wrapNode(children.item(i)));
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NodeList")).getList();
    }

    public static RuntimeList firstChild(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getFirstChild()).getList();
    }

    public static RuntimeList lastChild(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getLastChild()).getList();
    }

    public static RuntimeList previousSibling(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getPreviousSibling()).getList();
    }

    public static RuntimeList nextSibling(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getNextSibling()).getList();
    }

    public static RuntimeList hasAttributes(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        NamedNodeMap attrs = n.getAttributes();
        boolean has = attrs != null && attrs.getLength() > 0;
        return (has ? scalarTrue : scalarFalse).getList();
    }

    /**
     * Create a blessed XML::LibXML::Namespace hashref {prefix=>, uri=>}.
     * This is the Perl-side representation for libxml2 namespace declaration nodes (type 18).
     */
    private static RuntimeScalar wrapNamespaceNode(String prefix, String uri) {
        RuntimeHash h = new RuntimeHash();
        h.put("prefix", new RuntimeScalar(prefix != null ? prefix : ""));
        h.put("uri",    new RuntimeScalar(uri    != null ? uri    : ""));
        RuntimeScalar ref = h.createReferenceWithTrackedElements();
        return ReferenceOperators.bless(ref, new RuntimeScalar("XML::LibXML::Namespace"));
    }

    /**
     * Wrap a single DOM Attr node, returning XML::LibXML::Namespace if it is
     * a namespace declaration (xmlns or xmlns:prefix), or XML::LibXML::Attr otherwise.
     */
    private static RuntimeScalar wrapAttrNode(Attr a) {
        String name = a.getName();
        if ("xmlns".equals(name)) {
            // Default namespace declaration
            return wrapNamespaceNode("", a.getValue());
        } else if (name.startsWith("xmlns:")) {
            // Prefixed namespace declaration
            return wrapNamespaceNode(name.substring(6), a.getValue());
        }
        return wrapNode(a);
    }

    /**
     * Collect namespace nodes for an element that are NOT already covered by explicit
     * xmlns: attributes on that element. This emulates libxml2's behavior of exposing
     * namespace bindings (including those inherited / restored by importNode) as
     * XML::LibXML::Namespace objects via attributes().
     */
    private static List<RuntimeScalar> collectImplicitNamespaceNodes(Element el, NamedNodeMap attrs) {
        List<RuntimeScalar> nsNodes = new ArrayList<>();
        // Gather explicit namespace prefixes already declared on this element
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String aname = a.getName();
            if ("xmlns".equals(aname)) declared.add("");
            else if (aname.startsWith("xmlns:")) declared.add(aname.substring(6));
        }
        // If the element itself has a namespace binding not yet covered, synthesize one
        String nsUri = el.getNamespaceURI();
        String pfx   = el.getPrefix();
        if (nsUri != null && !nsUri.isEmpty()) {
            String key = (pfx != null) ? pfx : "";
            if (!declared.contains(key)) {
                nsNodes.add(wrapNamespaceNode(pfx, nsUri));
            }
        }
        return nsNodes;
    }

    public static RuntimeList attributes(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        NamedNodeMap attrs = n.getAttributes();
        if (attrs == null) {
            // Non-element nodes (Text, Comment, etc.) have no attributes.
            // Return undef in scalar context, empty list in list context.
            return ctx == RuntimeContextType.LIST ? new RuntimeList() : scalarUndef.getList();
        }
        // Build the combined list: xmlns* attrs as Namespace nodes, others as Attr nodes,
        // plus any implicit namespace nodes (e.g. from importNode without explicit xmlns: attr).
        List<RuntimeScalar> implicit = (n instanceof Element)
            ? collectImplicitNamespaceNodes((Element) n, attrs) : java.util.Collections.emptyList();
        if (ctx == RuntimeContextType.LIST) {
            // In list context, return individual attribute node scalars so that
            // "for my $attr ($node->attributes)" iterates over Attr/Namespace nodes.
            RuntimeList result = new RuntimeList();
            for (int i = 0; i < attrs.getLength(); i++) result.add(wrapAttrNode((Attr) attrs.item(i)));
            for (RuntimeScalar ns : implicit) result.add(ns);
            return result;
        }
        // In scalar context, return the blessed NamedNodeMap reference.
        RuntimeArray arr = new RuntimeArray();
        for (int i = 0; i < attrs.getLength(); i++) RuntimeArray.push(arr, wrapAttrNode((Attr) attrs.item(i)));
        for (RuntimeScalar ns : implicit) RuntimeArray.push(arr, ns);
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NamedNodeMap")).getList();
    }

    /** _childNodes(onlyNonBlank) — used by original LibXML.pm childNodes/nonBlankChildNodes wrappers */
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

    private static boolean isBlankNode(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String val = node.getNodeValue();
            return val == null || val.trim().isEmpty();
        }
        return false;
    }

    /** removeChildNodes — removes all child nodes from a node */
    public static RuntimeList removeChildNodes(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            node.removeChild(children.item(i));
        }
        return scalarUndef.getList();
    }

    /** lookupNamespacePrefix(uri) — reverse lookup: namespace URI → prefix */
    public static RuntimeList lookupNamespacePrefix(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        if (args.size() < 2) return scalarUndef.getList();
        String uri = args.get(1).toString();
        if (!(node instanceof Element)) return scalarUndef.getList();
        Node cur = node;
        while (cur != null && cur.getNodeType() == Node.ELEMENT_NODE) {
            // Check the node's own namespace prefix first
            String nodeNsUri = cur.getNamespaceURI();
            if (uri.equals(nodeNsUri)) {
                String pfx = cur.getPrefix();
                return new RuntimeScalar(pfx != null ? pfx : "").getList();
            }
            // Check xmlns: attribute declarations
            NamedNodeMap attrs = cur.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr attr = (Attr) attrs.item(i);
                String attrName = attr.getName();
                if (attrName.startsWith("xmlns:") && attr.getValue().equals(uri)) {
                    return new RuntimeScalar(attrName.substring(6)).getList();
                }
                if (attrName.equals("xmlns") && attr.getValue().equals(uri)) {
                    return new RuntimeScalar("").getList();
                }
            }
            cur = cur.getParentNode();
        }
        return scalarUndef.getList();
    }

    /** firstNonBlankChild — first non-whitespace child node */
    public static RuntimeList firstNonBlankChild(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        Node child = node.getFirstChild();
        while (child != null) {
            if (!isBlankNode(child)) return wrapNode(child).getList();
            child = child.getNextSibling();
        }
        return scalarUndef.getList();
    }

    /** nextNonBlankSibling — next non-whitespace sibling node */
    public static RuntimeList nextNonBlankSibling(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        Node sib = node.getNextSibling();
        while (sib != null) {
            if (!isBlankNode(sib)) return wrapNode(sib).getList();
            sib = sib.getNextSibling();
        }
        return scalarUndef.getList();
    }

    /** previousNonBlankSibling — previous non-whitespace sibling node */
    public static RuntimeList previousNonBlankSibling(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        Node sib = node.getPreviousSibling();
        while (sib != null) {
            if (!isBlankNode(sib)) return wrapNode(sib).getList();
            sib = sib.getPreviousSibling();
        }
        return scalarUndef.getList();
    }

    /** setNodeName — rename a node (best-effort using DOM3 renameNode) */
    public static RuntimeList setNodeName(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        if (args.size() < 2) return scalarUndef.getList();
        String newName = args.get(1).toString();
        try {
            Document doc = node.getOwnerDocument();
            if (doc == null) return scalarUndef.getList();
            int nodeType = node.getNodeType();
            if (nodeType == Node.ELEMENT_NODE || nodeType == Node.ATTRIBUTE_NODE) {
                // Keep existing prefix: rename to "prefix:newLocalName" or just "newLocalName"
                String nsUri = node.getNamespaceURI();
                String prefix = node.getPrefix();
                String qualName = (prefix != null && !prefix.isEmpty())
                    ? prefix + ":" + newName : newName;
                // NOTE: renameNode may return a *new* Node instance (Xerces behavior),
                // so we must update the stored reference in the Perl object.
                Node renamed = doc.renameNode(node, nsUri, qualName);
                if (renamed != node) updateNode(args.get(0), renamed);
            }
        } catch (Exception e) {
            // ignore — best-effort
        }
        return scalarUndef.getList();
    }

    /** getNamespaceDeclURI(prefix) — get the URI for a namespace declaration on this element */
    public static RuntimeList getNamespaceDeclURI(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        if (!(node instanceof Element)) return scalarUndef.getList();
        Element el = (Element) node;
        String prefix = (args.size() > 1 && args.get(1).type != RuntimeScalarType.UNDEF)
            ? args.get(1).toString() : "";
        String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
        // Check explicit attribute (both non-namespaced and NS-aware variants)
        String val = el.getAttribute(attrName);
        if (val != null && !val.isEmpty()) return new RuntimeScalar(val).getList();
        String nsLocal = prefix.isEmpty() ? "xmlns" : prefix;
        val = el.getAttributeNS("http://www.w3.org/2000/xmlns/", nsLocal);
        if (val != null && !val.isEmpty()) return new RuntimeScalar(val).getList();
        // For default namespace (empty prefix): fall back to element's own namespace URI
        // ONLY if no ancestor already declares the same namespace (libxml2 does namespace
        // reconciliation on appendChild, stripping redundant declarations).
        if (prefix.isEmpty()) {
            String elPfx = el.getPrefix();
            if (elPfx == null || elPfx.isEmpty()) {
                String ns = el.getNamespaceURI();
                if (ns != null && !ns.isEmpty()) {
                    if (!isNsDeclaredByAncestor(el, "", ns)) {
                        return new RuntimeScalar(ns).getList();
                    }
                }
            }
        }
        // For non-empty prefix: fall back to element's own namespace URI
        // if the element uses this prefix and no ancestor has declared it.
        if (!prefix.isEmpty()) {
            String elPfx = el.getPrefix();
            if (prefix.equals(elPfx)) {
                String ns = el.getNamespaceURI();
                if (ns != null && !ns.isEmpty()) {
                    if (!isNsDeclaredByAncestor(el, prefix, ns)) {
                        return new RuntimeScalar(ns).getList();
                    }
                }
            }
        }
        return scalarUndef.getList();
    }

    /**
     * Returns true if an ancestor of el (not el itself) has an explicit namespace
     * declaration for the given prefix and URI.
     * For empty prefix (default namespace), also checks if an ancestor element
     * is in the same namespace without prefix (implicit default ns).
     * For non-empty prefix, also checks if an ancestor element itself uses
     * the same prefix (implicit declaration via the element's own namespace binding).
     */
    private static boolean isNsDeclaredByAncestor(Element el, String prefix, String ns) {
        Node parentNode = el.getParentNode();
        while (parentNode != null && parentNode.getNodeType() == Node.ELEMENT_NODE) {
            Element parentEl = (Element) parentNode;
            String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
            // Check explicit xmlns attribute on the ancestor
            String parentDecl = parentEl.getAttribute(attrName);
            if (parentDecl == null || parentDecl.isEmpty()) {
                String nsLocal = prefix.isEmpty() ? "xmlns" : prefix;
                parentDecl = parentEl.getAttributeNS("http://www.w3.org/2000/xmlns/", nsLocal);
            }
            if (ns.equals(parentDecl)) return true;
            if (prefix.isEmpty()) {
                // Also treat an ancestor without prefix (namespace via createElementNS) as declaring it
                String pPfx = parentEl.getPrefix();
                if ((pPfx == null || pPfx.isEmpty()) && ns.equals(parentEl.getNamespaceURI())) return true;
            } else {
                // Also treat an ancestor that has the same prefix (namespace via createElementNS) as declaring it
                String pPfx = parentEl.getPrefix();
                if (prefix.equals(pPfx) && ns.equals(parentEl.getNamespaceURI())) return true;
            }
            parentNode = parentNode.getParentNode();
        }
        return false;
    }

    /** setNamespaceDeclURI(prefix, newURI) — set/remove a namespace declaration */
    public static RuntimeList setNamespaceDeclURI(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        if (!(node instanceof Element)) return scalarUndef.getList();
        Element el = (Element) node;
        String prefix = args.size() > 1 ? args.get(1).toString() : "";
        String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
        boolean removing = (args.size() < 3 || args.get(2).type == RuntimeScalarType.UNDEF);
        if (removing) {
            el.removeAttributeNS("http://www.w3.org/2000/xmlns/", attrName.contains(":") ? prefix : "xmlns");
            // Also try removeAttribute in case the attr was set without a namespace
            el.removeAttribute(attrName);
            // Cascade: rename element, its attributes, and all descendant elements/attrs
            // that use this prefix (matching libxml2 behavior).
            Element newEl = removePrefixFromSubtree(el, prefix, el.getOwnerDocument());
            if (newEl != el) {
                // Xerces returned a new Element object; update the Perl wrapper
                updateNode(args.get(0), newEl);
            }
        } else {
            String newUri = args.get(2).toString();
            // Use setAttributeNS so that DOM's lookupNamespaceURI() recognizes the declaration.
            el.setAttributeNS("http://www.w3.org/2000/xmlns/", attrName, newUri);
            // Also rename the element itself when its own namespace changes — matching libxml2 behavior.
            String elPfx = el.getPrefix();
            String normalised = (elPfx != null) ? elPfx : "";
            if (normalised.equals(prefix)) {
                try {
                    String localName = el.getLocalName();
                    if (localName == null) localName = el.getNodeName();
                    if (localName.contains(":")) localName = localName.substring(localName.indexOf(':') + 1);
                    String qualName = prefix.isEmpty() ? localName : prefix + ":" + localName;
                    Node renamed = el.getOwnerDocument().renameNode(el, newUri, qualName);
                    if (renamed != el) updateNode(args.get(0), renamed);
                } catch (Exception e) { /* ignore */ }
            }
        }
        // Return 1 (truthy) so that the || chain in setAttribute's xmlns handler
        // does not fall through to the redundant setNamespace call.
        return scalarOne.getList();
    }

    /** setNamespaceDeclPrefix(oldPrefix, newPrefix) — rename a namespace declaration prefix */
    public static RuntimeList setNamespaceDeclPrefix(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        if (!(node instanceof Element)) return scalarUndef.getList();
        Element el = (Element) node;
        String oldPfx = args.size() > 1 ? args.get(1).toString() : "";
        String newPfx = args.size() > 2 ? args.get(2).toString() : "";
        String oldAttr = oldPfx.isEmpty() ? "xmlns" : "xmlns:" + oldPfx;
        // Find the namespace URI declared under oldPfx
        String uri = el.getAttributeNS("http://www.w3.org/2000/xmlns/", oldPfx.isEmpty() ? "xmlns" : oldPfx);
        if (uri == null || uri.isEmpty()) uri = el.getAttribute(oldAttr);
        if (uri == null || uri.isEmpty()) {
            // prefix not found — treat as a no-op (matches libxml2 behavior)
            return scalarOne.getList();
        }
        // Error if new prefix is already in use on this element (prefix occupied)
        if (!newPfx.isEmpty()) {
            String existing = el.getAttributeNS("http://www.w3.org/2000/xmlns/", newPfx);
            if (existing == null || existing.isEmpty()) existing = el.getAttribute("xmlns:" + newPfx);
            if (existing != null && !existing.isEmpty()) {
                throw new PerlDieException(new RuntimeScalar("setNamespaceDeclPrefix: prefix '" + newPfx + "' is in use"));
            }
        } else {
            // Cannot rename to empty prefix if already occupied or if uri is non-empty
            String existing = el.getAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns");
            if (existing == null || existing.isEmpty()) existing = el.getAttribute("xmlns");
            if (existing != null && !existing.isEmpty()) {
                throw new PerlDieException(new RuntimeScalar("setNamespaceDeclPrefix: cannot set non-empty prefix for empty namespace"));
            }
        }
        // Rename: remove old, add new
        el.removeAttributeNS("http://www.w3.org/2000/xmlns/", oldPfx.isEmpty() ? "xmlns" : oldPfx);
        el.removeAttribute(oldAttr);
        String newAttr = newPfx.isEmpty() ? "xmlns" : "xmlns:" + newPfx;
        el.setAttributeNS("http://www.w3.org/2000/xmlns/", newAttr, uri);
        // If the element itself uses the old prefix, rename it to use the new prefix
        String elPfx = el.getPrefix();
        String normElPfx = (elPfx != null) ? elPfx : "";
        if (normElPfx.equals(oldPfx)) {
            try {
                String localName = el.getLocalName();
                if (localName == null) localName = el.getNodeName();
                if (localName.contains(":")) localName = localName.substring(localName.indexOf(':') + 1);
                String qualName = newPfx.isEmpty() ? localName : newPfx + ":" + localName;
                Node renamed = el.getOwnerDocument().renameNode(el, uri, qualName);
                if (renamed != el) updateNode(args.get(0), renamed);
            } catch (Exception e) { /* ignore */ }
        }
        return scalarOne.getList();
    }


    public static RuntimeList toStringC14N(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        // Full C14N is complex; return standard serialization as fallback
        return new RuntimeScalar(serializeNode(node, false, false)).getList();
    }

    /** namespaceIsEqual — for XML::LibXML::Namespace _isEqual */
    public static RuntimeList namespaceIsEqual(RuntimeArray args, int ctx) {
        RuntimeScalar self  = args.get(0);
        RuntimeScalar other = args.size() > 1 ? args.get(1) : scalarUndef;
        try {
            RuntimeHash h1 = self.hashDerefRaw();
            RuntimeHash h2 = other.hashDerefRaw();
            String p1 = h1.get("prefix").toString();
            String p2 = h2.get("prefix").toString();
            String u1 = h1.get("uri").toString();
            String u2 = h2.get("uri").toString();
            return new RuntimeScalar((p1.equals(p2) && u1.equals(u2)) ? 1 : 0).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    /** nopMethod — stub for unimplemented XS functions; returns undef */
    public static RuntimeList nopMethod(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    /** nodeIsEqual — for _isEqual on DOM nodes (used by isEqualNode) */
    public static RuntimeList nodeIsEqual(RuntimeArray args, int ctx) {
        Node a = getNode(args.get(0));
        Node b = args.size() > 1 ? getNode(args.get(1)) : null;
        return new RuntimeScalar((a != null && b != null && a.isEqualNode(b)) ? 1 : 0).getList();
    }

    /** attrSerializeContent — serializes attribute value */
    public static RuntimeList attrSerializeContent(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String val = node.getNodeValue();
        return new RuntimeScalar(val != null ? val : "").getList();
    }

    /** attrToString — serializes attribute as ' name="value"' */
    public static RuntimeList attrToString(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String name = node.getNodeName();
        String val  = node.getNodeValue() != null ? node.getNodeValue() : "";
        val = val.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        return new RuntimeScalar(" " + name + "=\"" + val + "\"").getList();
    }

    public static RuntimeList cloneNode(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        // libxml2: cloneNode() with no arg = shallow, cloneNode(1) = deep
        boolean deep = args.size() > 1 && args.get(1).getBoolean();
        return wrapNode(n.cloneNode(deep)).getList();
    }

    public static RuntimeList appendChild(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        Node child  = getNode(args.get(1));
        Document ownerDoc = (parent.getNodeType() == Node.DOCUMENT_NODE)
            ? (Document) parent : parent.getOwnerDocument();
        if (ownerDoc != null && child.getOwnerDocument() != null && child.getOwnerDocument() != ownerDoc) {
            child = ownerDoc.importNode(child, true);
        }
        parent.appendChild(child);
        // Namespace reconciliation: strip redundant declarations from child
        if (child instanceof Element) {
            reconcileNamespaces((Element) child);
        }
        return wrapNode(child).getList();
    }

    /**
     * Strips namespace declarations on el that are already declared with the same
     * URI by an ancestor element.  This mirrors libxml2's namespace reconciliation
     * on appendChild.
     */
    private static void reconcileNamespaces(Element el) {
        NamedNodeMap attrs = el.getAttributes();
        List<Attr> toRemove = new java.util.ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node n = attrs.item(i);
            if (!(n instanceof Attr)) continue;
            Attr attr = (Attr) n;
            String name = attr.getNodeName();
            if (!name.startsWith("xmlns")) continue;
            // Determine the prefix this declaration covers
            String declPrefix;
            if (name.equals("xmlns")) {
                declPrefix = "";
            } else if (name.startsWith("xmlns:")) {
                declPrefix = name.substring(6);
                if (declPrefix.isEmpty()) continue;
            } else {
                continue;
            }
            String declURI = attr.getValue();
            if (declURI == null || declURI.isEmpty()) continue;
            // If an ancestor already declares the same prefix→URI, this one is redundant
            if (isNsDeclaredByAncestor(el, declPrefix, declURI)) {
                toRemove.add(attr);
            }
        }
        for (Attr attr : toRemove) {
            el.removeAttributeNode(attr);
        }
    }

    /**
     * $parent->addChild($node) — like appendChild but handles Attr nodes:
     * an Attr is set as an attribute rather than appended as a child element.
     */
    public static RuntimeList addChildNode(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        Node child  = getNode(args.get(1));
        if (child.getNodeType() == Node.ATTRIBUTE_NODE && parent.getNodeType() == Node.ELEMENT_NODE) {
            Attr attr = (Attr) child;
            Document ownerDoc = parent.getOwnerDocument();
            if (ownerDoc != null && attr.getOwnerDocument() != ownerDoc) {
                attr = (Attr) ownerDoc.importNode(attr, true);
            }
            if (attr.getNamespaceURI() != null) {
                ((Element) parent).setAttributeNodeNS(attr);
            } else {
                ((Element) parent).setAttributeNode(attr);
            }
            return wrapNode(attr).getList();
        }
        return appendChild(args, ctx);
    }

    public static RuntimeList insertBefore(RuntimeArray args, int ctx) {
        Node parent   = getNode(args.get(0));
        Node newChild = getNode(args.get(1));
        Node refChild = (args.size() > 2 && args.get(2).getDefinedBoolean()) ? getNode(args.get(2)) : null;
        newChild = importNodeIfNeeded(parent, newChild);
        parent.insertBefore(newChild, refChild);
        return wrapNode(newChild).getList();
    }

    public static RuntimeList insertAfter(RuntimeArray args, int ctx) {
        Node parent   = getNode(args.get(0));
        Node newChild = getNode(args.get(1));
        Node refChild = (args.size() > 2 && args.get(2).getDefinedBoolean()) ? getNode(args.get(2)) : null;
        Node nextRef  = (refChild != null) ? refChild.getNextSibling() : null;
        newChild = importNodeIfNeeded(parent, newChild);
        parent.insertBefore(newChild, nextRef);
        return wrapNode(newChild).getList();
    }

    /** Import a node into the parent's document if they are in different documents. */
    private static Node importNodeIfNeeded(Node parent, Node child) {
        Document ownerDoc = (parent.getNodeType() == Node.DOCUMENT_NODE)
            ? (Document) parent : parent.getOwnerDocument();
        if (ownerDoc != null && child.getOwnerDocument() != null && child.getOwnerDocument() != ownerDoc) {
            child = ownerDoc.importNode(child, true);
        }
        return child;
    }

    public static RuntimeList removeChild(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        Node child  = getNode(args.get(1));
        parent.removeChild(child);
        // Namespace reconciliation: re-add namespace declarations for prefixes used
        // by this node that are no longer in scope (they were declared on the former parent).
        if (child instanceof Element) {
            readdMissingNsDecls((Element) child);
        }
        return wrapNode(child).getList();
    }

    /**
     * After a node is detached from its parent, any namespace prefixes used by
     * the node's own attributes (or its prefix) that are no longer in scope must
     * be re-declared on the node itself.  This mirrors libxml2's behavior.
     */
    private static void readdMissingNsDecls(Element el) {
        // Collect prefixes used by attributes that have actual namespace URIs
        Map<String, String> needed = new java.util.LinkedHashMap<>();
        // Check the element's own prefix
        String elPfx = el.getPrefix();
        String elNs  = el.getNamespaceURI();
        if (elPfx != null && !elPfx.isEmpty() && elNs != null && !elNs.isEmpty()) {
            needed.put(elPfx, elNs);
        }
        // Check attribute prefixes
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node n = attrs.item(i);
            if (!(n instanceof Attr)) continue;
            Attr attr = (Attr) n;
            // Skip xmlns declarations themselves
            String aName = attr.getNodeName();
            if (aName.equals("xmlns") || aName.startsWith("xmlns:")) continue;
            String aPfx = attr.getPrefix();
            String aNs  = attr.getNamespaceURI();
            if (aPfx != null && !aPfx.isEmpty() && aNs != null && !aNs.isEmpty()) {
                needed.put(aPfx, aNs);
            }
        }
        // For each needed prefix, check if it's already declared on el or an ancestor
        for (Map.Entry<String, String> entry : needed.entrySet()) {
            String pfx = entry.getKey();
            String ns  = entry.getValue();
            // Check if el itself has an explicit xmlns:pfx declaration
            String existing = el.getAttribute("xmlns:" + pfx);
            if (existing == null || existing.isEmpty()) {
                existing = el.getAttributeNS("http://www.w3.org/2000/xmlns/", pfx);
            }
            if (existing != null && !existing.isEmpty()) continue;  // already declared
            // el is now detached (no parent), so we need to add the declaration
            el.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:" + pfx, ns);
        }
    }

    /**
     * $node->appendText($text) — append a text node child with the given content.
     * Returns the new Text node.
     */
    public static RuntimeList appendText(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        String text = args.size() > 1 ? args.get(1).toString() : "";
        Document ownerDoc = (parent.getNodeType() == Node.DOCUMENT_NODE)
            ? (Document) parent : parent.getOwnerDocument();
        if (ownerDoc == null) ownerDoc = getScratchDoc();
        Text textNode = ownerDoc.createTextNode(text);
        parent.appendChild(textNode);
        return wrapNode(textNode).getList();
    }

    public static RuntimeList replaceChild(RuntimeArray args, int ctx) {
        Node parent   = getNode(args.get(0));
        Node newChild = getNode(args.get(1));
        Node oldChild = getNode(args.get(2));
        // If newChild belongs to a different document, adopt it first (libxml2 behavior)
        Document targetDoc = parent.getOwnerDocument();
        if (targetDoc != null && newChild.getOwnerDocument() != targetDoc) {
            newChild = targetDoc.adoptNode(newChild);
        }
        parent.replaceChild(newChild, oldChild);
        return wrapNode(oldChild).getList();
    }

    public static RuntimeList replaceNode(RuntimeArray args, int ctx) {
        Node node    = getNode(args.get(0));
        Node newNode = getNode(args.get(1));
        Node parent  = node.getParentNode();
        if (parent != null) parent.replaceChild(newNode, node);
        return wrapNode(newNode).getList();
    }

    public static RuntimeList unbindNode(RuntimeArray args, int ctx) {
        Node node   = getNode(args.get(0));
        Node parent = node.getParentNode();
        if (parent != null) parent.removeChild(node);
        return wrapNode(node).getList();
    }

    public static RuntimeList hasChildNodes(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        // libxml2 returns 0 for Attr->hasChildNodes() even though the attr has a text child
        if (n.getNodeType() == Node.ATTRIBUTE_NODE) return scalarZero.getList();
        return (n.hasChildNodes() ? scalarOne : scalarZero).getList();
    }

    public static RuntimeList textContent(RuntimeArray args, int ctx) {
        String tc = getNode(args.get(0)).getTextContent();
        return new RuntimeScalar(tc != null ? tc : "").getList();
    }

    public static RuntimeList string_value(RuntimeArray args, int ctx) {
        return textContent(args, ctx);
    }

    public static RuntimeList ownerDocument(RuntimeArray args, int ctx) {
        return wrapNode(getNode(args.get(0)).getOwnerDocument()).getList();
    }

    public static RuntimeList getOwnerDocument(RuntimeArray args, int ctx) {
        return ownerDocument(args, ctx);
    }

    public static RuntimeList isSameNode(RuntimeArray args, int ctx) {
        Node a = getNode(args.get(0)), b = getNode(args.get(1));
        return (a == b ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList localname(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String ln = n.getLocalName();
        return new RuntimeScalar(ln != null ? ln : n.getNodeName()).getList();
    }

    public static RuntimeList prefix(RuntimeArray args, int ctx) {
        String p = getNode(args.get(0)).getPrefix();
        return (p != null ? new RuntimeScalar(p) : scalarUndef).getList();
    }

    public static RuntimeList namespaceURI(RuntimeArray args, int ctx) {
        String ns = getNode(args.get(0)).getNamespaceURI();
        return (ns != null ? new RuntimeScalar(ns) : scalarUndef).getList();
    }

    /**
     * $element->localNS() — returns a XML::LibXML::Namespace object for the
     * element's own namespace (its prefix binding), or undef if none.
     */
    public static RuntimeList localNS(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String prefix = n.getPrefix();
        String ns = n.getNamespaceURI();
        if (ns == null) return scalarUndef.getList();
        return makeNamespaceObject(prefix != null ? prefix : "", ns).getList();
    }

    /**
     * $element->getNamespaces() — return all namespace declarations on this
     * element as a list of XML::LibXML::Namespace objects.
     * Includes both the element's own namespace and explicit xmlns: attributes.
     */
    public static RuntimeList getNamespaces(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        RuntimeList result = new RuntimeList();
        Set<String> seen = new java.util.HashSet<>();

        // Include the element's own namespace binding (from its prefix/nsURI)
        String ownNsUri = n.getNamespaceURI();
        String ownPrefix = n.getPrefix();
        if (ownNsUri != null && !ownNsUri.isEmpty()) {
            String pfx = (ownPrefix != null) ? ownPrefix : "";
            result.add(makeNamespaceObject(pfx, ownNsUri));
            seen.add(pfx);
        }

        // Also scan explicit xmlns:* attribute declarations
        NamedNodeMap attrs = n.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                String attrName = a.getNodeName();
                if (attrName.equals("xmlns")) {
                    if (!seen.contains("")) {
                        result.add(makeNamespaceObject("", a.getValue()));
                        seen.add("");
                    }
                } else if (attrName.startsWith("xmlns:")) {
                    String pfx = attrName.substring(6);
                    if (!seen.contains(pfx)) {
                        result.add(makeNamespaceObject(pfx, a.getValue()));
                        seen.add(pfx);
                    }
                }
            }
        }
        return result;
    }

    private static RuntimeScalar makeNamespaceObject(String prefix, String uri) {
        RuntimeHash h = new RuntimeHash();
        h.put("prefix", new RuntimeScalar(prefix));
        h.put("uri",    new RuntimeScalar(uri));
        return ReferenceOperators.bless(h.createReference(),
            new RuntimeScalar("XML::LibXML::Namespace"));
    }

    public static RuntimeList nodePath(RuntimeArray args, int ctx) {
        return new RuntimeScalar(buildNodePath(getNode(args.get(0)))).getList();
    }

    private static String buildNodePath(Node n) {
        if (n == null) return "";
        if (n.getNodeType() == Node.DOCUMENT_NODE) return "/";
        StringBuilder sb = new StringBuilder();
        Node cur = n;
        while (cur != null && cur.getNodeType() != Node.DOCUMENT_NODE) {
            String name;
            if (cur.getNodeType() == Node.ELEMENT_NODE) {
                name = cur.getNodeName();
                int pos = 1;
                Node sib = cur.getPreviousSibling();
                while (sib != null) {
                    if (sib.getNodeType() == Node.ELEMENT_NODE && name.equals(sib.getNodeName())) pos++;
                    sib = sib.getPreviousSibling();
                }
                sb.insert(0, "/" + name + "[" + pos + "]");
            } else {
                sb.insert(0, "/" + cur.getNodeName());
            }
            cur = cur.getParentNode();
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    public static RuntimeList line_number(RuntimeArray args, int ctx) {
        return scalarFalse.getList();
    }

    public static RuntimeList getData(RuntimeArray args, int ctx) {
        String val = getNode(args.get(0)).getNodeValue();
        return (val != null ? new RuntimeScalar(val) : scalarUndef).getList();
    }

    public static RuntimeList setData(RuntimeArray args, int ctx) {
        getNode(args.get(0)).setNodeValue(args.size() > 1 ? args.get(1).toString() : "");
        return scalarUndef.getList();
    }

    public static RuntimeList setNamespace(RuntimeArray args, int ctx) {
        Node n     = getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        String pfx = (args.size() > 2) ? args.get(2).toString() : null;
        // act flag (arg 4): when true the element/attr is moved to this namespace
        boolean act = args.size() < 4 || args.get(3).getBoolean();

        // Empty/null ns means "remove namespace" (libxml2 behavior)
        if (ns == null) {
            if (n instanceof Element) {
                try {
                    String localName = n.getLocalName();
                    if (localName == null) localName = n.getNodeName();
                    if (localName.contains(":")) localName = localName.substring(localName.indexOf(':') + 1);
                    Node renamed = n.getOwnerDocument().renameNode(n, null, localName);
                    if (renamed != n) updateNode(args.get(0), renamed);
                } catch (Exception e) { /* ignore */ }
            } else if (n instanceof Attr) {
                try {
                    String localName = n.getLocalName();
                    if (localName == null) localName = n.getNodeName();
                    if (localName.contains(":")) localName = localName.substring(localName.indexOf(':') + 1);
                    Node renamed = n.getOwnerDocument().renameNode(n, null, localName);
                    if (renamed != n) updateNode(args.get(0), renamed);
                } catch (Exception e) { /* ignore */ }
            }
            return scalarOne.getList();
        }

        if (n instanceof Element && pfx != null) {
            // Declare the namespace binding on the element.
            // Empty prefix = default namespace declaration ("xmlns"), not "xmlns:"
            String xmlnsAttr = pfx.isEmpty() ? "xmlns" : "xmlns:" + pfx;
            ((Element) n).setAttributeNS("http://www.w3.org/2000/xmlns/", xmlnsAttr, ns);
            // If act=true, rename the element to prefix:localname
            // NOTE: renameNode may return a *new* Node instance (Xerces behavior),
            // so we must update the stored reference in the Perl object.
            if (act) {
                try {
                    String localName = n.getLocalName();
                    if (localName == null) localName = n.getNodeName();
                    Node renamed = n.getOwnerDocument().renameNode(n, ns, pfx + ":" + localName);
                    if (renamed != n) updateNode(args.get(0), renamed);
                } catch (Exception e) { /* ignore */ }
            }
            return scalarOne.getList();
        } else if (n instanceof Attr && pfx != null) {
            // For attribute nodes: rename to prefix:localname with given namespace
            // NOTE: renameNode may return a *new* Node instance (Xerces behavior),
            // so we must update the stored reference in the Perl object.
            try {
                String localName = n.getLocalName();
                if (localName == null) localName = n.getNodeName();
                Node renamed = n.getOwnerDocument().renameNode(n, ns, pfx + ":" + localName);
                if (renamed != n) updateNode(args.get(0), renamed);
            } catch (Exception e) { /* ignore */ }
            return scalarOne.getList();
        }
        return scalarOne.getList();
    }

    public static RuntimeList toString(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        boolean format = args.size() > 1 && args.get(1).getBoolean();
        return new RuntimeScalar(serializeNode(n, format, false)).getList();
    }

    // ================================================================
    // XPath on nodes
    // ================================================================

    public static RuntimeList findnodes(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String expr = args.size() > 1 ? toXPathString(args.get(1)) : "";
        List<RuntimeScalar> nodes = evaluateXPathToNodeList(node, expr, null, null);
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList result = new RuntimeList();
            for (RuntimeScalar ns : nodes) result.add(ns);
            return result;
        }
        RuntimeArray arr = new RuntimeArray();
        for (RuntimeScalar ns : nodes) RuntimeArray.push(arr, ns);
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NodeList")).getList();
    }

    public static RuntimeList find(RuntimeArray args, int ctx) {
        // Public API: returns actual object (NodeList/Literal/Number/Boolean)
        Node node = getNode(args.get(0));
        String expr = args.size() > 1 ? toXPathString(args.get(1)) : "";
        RuntimeList raw = evaluateXPath(node, expr, null, false);
        return wrapXPathResult(raw);
    }

    /**
     * Internal _find: returns (type_class, @params) for use by XPathContext's _guarded_find_call.
     */
    public static RuntimeList nodeFindRaw(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String expr  = args.size() > 1 ? toXPathString(args.get(1)) : "";
        boolean existsOnly = args.size() > 2 && args.get(2).getBoolean();
        return evaluateXPath(node, expr, null, existsOnly);
    }

    public static RuntimeList findvalue(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String expr = args.size() > 1 ? toXPathString(args.get(1)) : "";
        try {
            return new RuntimeScalar(XPATH_FACTORY.newXPath().evaluate(expr, node)).getList();
        } catch (XPathExpressionException e) {
            throw new RuntimeException("findvalue XPath error: " + e.getMessage(), e);
        }
    }

    public static RuntimeList exists(RuntimeArray args, int ctx) {
        Node node = getNode(args.get(0));
        String expr = args.size() > 1 ? toXPathString(args.get(1)) : "";
        try {
            Boolean val = (Boolean) XPATH_FACTORY.newXPath().evaluate(expr, node, XPathConstants.BOOLEAN);
            return new RuntimeScalar(val ? 1 : 0).getList();
        } catch (XPathExpressionException e) {
            return new RuntimeScalar(0).getList();
        }
    }

    // ================================================================
    // XML::LibXML::Document methods
    // ================================================================

    public static RuntimeList documentElement(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).getDocumentElement()).getList();
    }

    public static RuntimeList setDocumentElement(RuntimeArray args, int ctx) {
        Document doc  = (Document) getNode(args.get(0));
        Element  elem = (Element) getNode(args.get(1));
        Element old = doc.getDocumentElement();
        if (old != null) doc.removeChild(old);
        doc.appendChild(elem);
        return wrapNode(elem).getList();
    }

    public static RuntimeList createElement(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).createElement(args.get(1).toString())).getList();
    }

    public static RuntimeList createElementNS(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        return wrapNode(doc.createElementNS(ns, args.get(2).toString())).getList();
    }

    public static RuntimeList createTextNode(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).createTextNode(args.get(1).toString())).getList();
    }

    public static RuntimeList createComment(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).createComment(args.get(1).toString())).getList();
    }

    public static RuntimeList createCDATASection(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).createCDATASection(args.get(1).toString())).getList();
    }

    public static RuntimeList createAttribute(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        String name = args.get(1).toString();
        Attr attr = doc.createAttribute(name);
        if (args.size() > 2) attr.setValue(args.get(2).toString());
        return wrapNode(attr).getList();
    }

    public static RuntimeList createAttributeNS(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        String qualName = args.get(2).toString();
        // libxml2 requires a root element to resolve namespace prefixes for NS attributes
        if (ns != null && qualName.contains(":") && doc.getDocumentElement() == null) {
            return WarnDie.die(new RuntimeScalar("createAttributeNS: no root element in document\n"),
                new RuntimeScalar("\n")).getList();
        }
        Attr attr = doc.createAttributeNS(ns, qualName);
        if (args.size() > 3) attr.setValue(args.get(3).toString());
        return wrapNode(attr).getList();
    }

    public static RuntimeList createDocumentFragment(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).createDocumentFragment()).getList();
    }

    public static RuntimeList createEntityReference(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        String name = args.get(1).toString();
        try {
            return wrapNode(doc.createEntityReference(name)).getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    /**
     * getElementById(id) — find element by xml:id or id attribute.
     * Xerces getElementById only works with DTD-declared IDs; we supplement
     * with an explicit tree walk that checks xml:id and plain id attributes.
     *
     * libxml2 maintains a persistent ID index so detached nodes are still
     * findable after they are removed from the tree.  We emulate this with
     * a per-Document HashMap stored via setUserData("__xmlIdCache__").
     * The cache is populated (or refreshed) on each successful live-tree lookup.
     */
    @SuppressWarnings("unchecked")
    public static RuntimeList getElementById(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        String id = args.get(1).toString();

        // 1. Try the live tree first (xml:id or id attribute walk)
        Element found = doc.getElementById(id);            // DTD-declared IDs
        if (found == null) {
            found = findElementById(doc.getDocumentElement(), id);   // tree walk
        }
        if (found != null) {
            // Refresh the persistent cache with this live-tree result
            Map<String, Element> cache = (Map<String, Element>) doc.getUserData("__xmlIdCache__");
            if (cache == null) {
                cache = new HashMap<>();
                doc.setUserData("__xmlIdCache__", cache, null);
            }
            cache.put(id, found);
            return wrapNode(found).getList();
        }

        // 2. Not in the live tree — consult the persistent cache.
        //    This mirrors libxml2's behaviour: nodes that were once in the tree
        //    (and thus had their ID registered) are still returned even after removal.
        Map<String, Element> cache = (Map<String, Element>) doc.getUserData("__xmlIdCache__");
        if (cache != null) {
            Element cached = cache.get(id);
            if (cached != null) return wrapNode(cached).getList();
        }

        return wrapNode(null).getList();
    }

    private static Element findElementById(Element el, String id) {
        if (el == null) return null;
        String xmlId = el.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id");
        if (id.equals(xmlId)) return el;
        String plainId = el.getAttribute("id");
        if (id.equals(plainId)) return el;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element result = findElementById((Element) child, id);
                if (result != null) return result;
            }
        }
        return null;
    }

    public static RuntimeList importNode(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        Node node    = getNode(args.get(1));
        boolean deep = args.size() < 3 || args.get(2).getBoolean();
        return wrapNode(doc.importNode(node, deep)).getList();
    }

    public static RuntimeList adoptNode(RuntimeArray args, int ctx) {
        return wrapNode(((Document) getNode(args.get(0))).adoptNode(getNode(args.get(1)))).getList();
    }

    public static RuntimeList documentToString(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        boolean format = args.size() > 1 && args.get(1).getBoolean();
        return new RuntimeScalar(serializeNode(n, format, true)).getList();
    }

    public static RuntimeList toFile(RuntimeArray args, int ctx) {
        Node   n    = getNode(args.get(0));
        String path = args.get(1).toString();
        boolean fmt = args.size() > 2 && args.get(2).getBoolean();
        try (FileWriter fw = new FileWriter(path)) {
            fw.write(serializeNode(n, fmt, true));
        } catch (IOException e) {
            throw new RuntimeException("toFile: " + e.getMessage(), e);
        }
        return scalarTrue.getList();
    }

    public static RuntimeList docToFH(RuntimeArray args, int ctx) {
        Node    n   = getNode(args.get(0));
        boolean fmt = args.size() > 2 && args.get(2).getBoolean();
        String xml  = serializeNode(n, fmt, true);
        // Write to Perl filehandle via IO print
        if (args.size() > 1) {
            RuntimeScalar fh = args.get(1);
            RuntimeIO io = fh.getRuntimeIO();
            if (io == null) {
                throw new RuntimeException("toFH: not a valid filehandle");
            }
            io.write(xml);
        }
        return scalarTrue.getList();
    }

    public static RuntimeList documentURI(RuntimeArray args, int ctx) {
        String uri = ((Document) getNode(args.get(0))).getDocumentURI();
        return (uri != null ? new RuntimeScalar(uri) : scalarUndef).getList();
    }

    public static RuntimeList setDocumentURI(RuntimeArray args, int ctx) {
        ((Document) getNode(args.get(0))).setDocumentURI(args.size() > 1 ? args.get(1).toString() : null);
        return scalarUndef.getList();
    }

    // User-data keys for attributes JDK DOM does not track
    private static final String UDATA_ENCODING   = "perlonjava.xmlEncoding";
    private static final String UDATA_VERSION    = "perlonjava.xmlVersion";
    private static final String UDATA_STANDALONE  = "perlonjava.xmlStandaloneSet";
    // Sentinel stored in UDATA_ENCODING when encoding was explicitly cleared via setEncoding()
    private static final String ENCODING_CLEARED = "";

    public static RuntimeList documentEncoding(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        Object ud = doc.getUserData(UDATA_ENCODING);
        if (ud != null) {
            // "" sentinel means explicitly cleared
            String s = (String) ud;
            return s.isEmpty() ? scalarUndef.getList() : new RuntimeScalar(s).getList();
        }
        // Fall back to encoding declared in parsed XML prolog
        String enc = doc.getXmlEncoding();
        return (enc != null ? new RuntimeScalar(enc) : scalarUndef).getList();
    }

    public static RuntimeList setDocumentEncoding(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            String enc = args.get(1).toString();
            // Store empty-string sentinel if value is empty; otherwise store value
            doc.setUserData(UDATA_ENCODING, enc, null);
        } else {
            // No arg or undef: explicitly clear the encoding (sentinel = "")
            doc.setUserData(UDATA_ENCODING, ENCODING_CLEARED, null);
        }
        return scalarUndef.getList();
    }

    public static RuntimeList documentVersion(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        Object ud = doc.getUserData(UDATA_VERSION);
        if (ud != null) return new RuntimeScalar((String) ud).getList();
        String ver = doc.getXmlVersion();
        return new RuntimeScalar(ver != null ? ver : "1.0").getList();
    }

    public static RuntimeList setDocumentVersion(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        if (args.size() > 1) {
            String ver = args.get(1).toString();
            // JDK only accepts "1.0" and "1.1"; store arbitrary values in user data
            doc.setUserData(UDATA_VERSION, ver, null);
            try { doc.setXmlVersion(ver); } catch (Exception e) { /* non-standard version */ }
        }
        return scalarUndef.getList();
    }

    public static RuntimeList documentStandalone(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        // If standalone was never explicitly set/parsed, return -1 (libxml2 convention)
        Object ud = doc.getUserData(UDATA_STANDALONE);
        if (ud == null) return new RuntimeScalar(-1).getList();
        return new RuntimeScalar(doc.getXmlStandalone() ? 1 : 0).getList();
    }

    public static RuntimeList setDocumentStandalone(RuntimeArray args, int ctx) {
        Document doc = (Document) getNode(args.get(0));
        boolean val = args.size() > 1 && args.get(1).getBoolean();
        doc.setXmlStandalone(val);
        doc.setUserData(UDATA_STANDALONE, Boolean.TRUE, null);
        return scalarUndef.getList();
    }

    public static RuntimeList documentInternalSubset(RuntimeArray args, int ctx) {
        DocumentType dt = ((Document) getNode(args.get(0))).getDoctype();
        return dt == null ? scalarUndef.getList() : wrapNode(dt).getList();
    }

    public static RuntimeList documentExternalSubset(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    /** $doc->createProcessingInstruction($target, $data) */
    public static RuntimeList docCreatePI(RuntimeArray args, int ctx) {
        Document doc    = (Document) getNode(args.get(0));
        String target   = args.get(1).toString();
        String data     = args.size() > 2 ? args.get(2).toString() : "";
        return wrapNode(doc.createProcessingInstruction(target, data)).getList();
    }

    /** XML::LibXML::Document->createDocument($version, $encoding) */
    public static RuntimeList docCreateDocument(RuntimeArray args, int ctx) {
        // args.get(0) is the class name (called as class method)
        String version  = args.size() > 1 && args.get(1).type != RuntimeScalarType.UNDEF
            ? args.get(1).toString() : "1.0";
        if (version.isEmpty()) version = "1.0";
        String encoding = args.size() > 2 && args.get(2).type != RuntimeScalarType.UNDEF
            ? args.get(2).toString() : null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().newDocument();
            doc.setXmlVersion(version);
            if (encoding != null && !encoding.isEmpty()) {
                // Store encoding in user data (JDK DOM has no setXmlEncoding)
                doc.setUserData(UDATA_ENCODING, encoding, null);
            }
            // Note: UDATA_STANDALONE is intentionally NOT set → documentStandalone returns -1
            return wrapNode(doc).getList();
        } catch (Exception e) {
            throw new RuntimeException("createDocument: " + e.getMessage(), e);
        }
    }

    /** $doc->createExternalSubset($name, $publicId, $systemId) — stub, returns undef */
    public static RuntimeList docCreateExternalSubset(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    /** $doc->createInternalSubset($name, $publicId, $systemId) — stub, returns undef */
    public static RuntimeList docCreateInternalSubset(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    // ================================================================
    // XML::LibXML::Element extra methods
    // ================================================================

    /** XML::LibXML::Element->new($name) — create a detached element */
    private static Document SCRATCH_DOC = null;
    private static synchronized Document getScratchDoc() {
        if (SCRATCH_DOC == null) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                SCRATCH_DOC = f.newDocumentBuilder().newDocument();
            } catch (Exception e) {
                throw new RuntimeException("Cannot create scratch Document: " + e.getMessage(), e);
            }
        }
        return SCRATCH_DOC;
    }

    public static RuntimeList elemNew(RuntimeArray args, int ctx) {
        // First arg is class name (string) when called as XML::LibXML::Element->new
        String name = args.size() > 1 ? args.get(1).toString() : args.get(0).toString();
        Element el = getScratchDoc().createElement(name);
        return wrapNode(el).getList();
    }

    public static RuntimeList elemLookupNamespaceURI(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        String prefix = args.size() > 1 && args.get(1).type != RuntimeScalarType.UNDEF
            ? args.get(1).toString() : "";
        // Walk up the element tree looking ONLY at explicit xmlns: attribute declarations.
        // We must NOT fall back to DOM's lookupNamespaceURI() because it also inspects
        // attribute prefixes, which gives false positives after xmlns: declarations are removed.
        Node cur = el;
        while (cur != null && cur.getNodeType() == Node.ELEMENT_NODE) {
            Element curEl = (Element) cur;
            NamedNodeMap attrs = curEl.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                String aname = a.getName();
                if (prefix.isEmpty()) {
                    if ("xmlns".equals(aname)) return new RuntimeScalar(a.getValue()).getList();
                } else {
                    if (("xmlns:" + prefix).equals(aname)) return new RuntimeScalar(a.getValue()).getList();
                }
            }
            // Also check NS-aware attribute (set via setAttributeNS)
            String nsLocal = prefix.isEmpty() ? "xmlns" : prefix;
            String val = curEl.getAttributeNS("http://www.w3.org/2000/xmlns/", nsLocal);
            if (val != null && !val.isEmpty()) return new RuntimeScalar(val).getList();
            cur = cur.getParentNode();
        }
        // Final fallback: element's own namespace if it has no prefix (default namespace)
        // Only apply when looking for the default namespace AND the element has no prefix.
        if (prefix.isEmpty()) {
            String elPfx = el.getPrefix();
            if (elPfx == null || elPfx.isEmpty()) {
                String ns = el.getNamespaceURI();
                if (ns != null && !ns.isEmpty()) return new RuntimeScalar(ns).getList();
            }
        }
        return scalarUndef.getList();
    }

    public static RuntimeList elemGetNamespaces(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        NamedNodeMap attrs = el.getAttributes();
        RuntimeList result = new RuntimeList();
        if (attrs == null) return result;
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String name = a.getName();
            if (name.startsWith("xmlns:") || name.equals("xmlns")) {
                result.add(wrapNode(a));
            }
        }
        return result;
    }

    // ================================================================
    // XML::LibXML::Element methods
    // ================================================================

    public static RuntimeList getAttribute(RuntimeArray args, int ctx) {
        Element el  = (Element) getNode(args.get(0));
        String name = args.get(1).toString();
        return el.hasAttribute(name)
            ? new RuntimeScalar(el.getAttribute(name)).getList()
            : scalarUndef.getList();
    }

    public static RuntimeList getAttributeNS(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        String name = args.get(2).toString();
        return el.hasAttributeNS(ns, name)
            ? new RuntimeScalar(el.getAttributeNS(ns, name)).getList()
            : scalarUndef.getList();
    }

    public static RuntimeList setAttribute(RuntimeArray args, int ctx) {
        Element el  = (Element) getNode(args.get(0));
        String name = args.get(1).toString();
        String val  = args.size() > 2 ? args.get(2).toString() : "";
        // If the attribute name has a prefix, resolve it to a namespace URI
        // so we can use setAttributeNS (matching libxml2 behavior).
        int colon = name.indexOf(':');
        if (colon > 0) {
            String prefix = name.substring(0, colon);
            String nsUri = el.lookupNamespaceURI(prefix);
            if (nsUri != null) {
                el.setAttributeNS(nsUri, name, val);
                return wrapNode(el.getAttributeNodeNS(nsUri, name.substring(colon + 1))).getList();
            }
        }
        el.setAttribute(name, val);
        return wrapNode(el.getAttributeNode(name)).getList();
    }

    public static RuntimeList setAttributeNS(RuntimeArray args, int ctx) {
        Element el    = (Element) getNode(args.get(0));
        String ns     = args.size() > 1 ? nsArg(args.get(1)) : null;
        String qname  = args.get(2).toString();
        String val    = args.size() > 3 ? args.get(3).toString() : "";
        el.setAttributeNS(ns, qname, val);
        return scalarTrue.getList();
    }

    public static RuntimeList removeAttribute(RuntimeArray args, int ctx) {
        ((Element) getNode(args.get(0))).removeAttribute(args.get(1).toString());
        return scalarTrue.getList();
    }

    public static RuntimeList removeAttributeNS(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        el.removeAttributeNS(ns, args.get(2).toString());
        return scalarTrue.getList();
    }

    public static RuntimeList hasAttribute(RuntimeArray args, int ctx) {
        return (((Element) getNode(args.get(0))).hasAttribute(args.get(1).toString())
            ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList hasAttributeNS(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        return (el.hasAttributeNS(ns, args.get(2).toString()) ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList getElementsByTagName(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String tagName = args.get(1).toString();
        NodeList nl = (n instanceof Document)
            ? ((Document) n).getElementsByTagName(tagName)
            : ((Element)  n).getElementsByTagName(tagName);
        return nodeListToRuntimeList(nl, ctx);
    }

    public static RuntimeList getElementsByTagNameNS(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        String local = args.get(2).toString();
        NodeList nl = (n instanceof Document)
            ? ((Document) n).getElementsByTagNameNS(ns, local)
            : ((Element)  n).getElementsByTagNameNS(ns, local);
        return nodeListToRuntimeList(nl, ctx);
    }

    public static RuntimeList getElementsByLocalName(RuntimeArray args, int ctx) {
        Node n  = getNode(args.get(0));
        String name = args.get(1).toString();
        List<RuntimeScalar> results = new ArrayList<>();
        if (n instanceof Document) {
            // Include document element itself, then its descendants
            Node docEl = ((Document) n).getDocumentElement();
            if (docEl != null) collectByLocalNameWithSelf(docEl, name, results);
        } else {
            collectByLocalName(n, name, results);
        }
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList rl = new RuntimeList();
            for (RuntimeScalar r : results) rl.add(r);
            return rl;
        }
        RuntimeArray arr = new RuntimeArray();
        for (RuntimeScalar r : results) RuntimeArray.push(arr, r);
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NodeList")).getList();
    }

    private static void collectByLocalName(Node n, String name, List<RuntimeScalar> out) {
        NodeList children = n.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if (name.equals(child.getLocalName()) || "*".equals(name)) out.add(wrapNode(child));
                collectByLocalName(child, name, out);
            }
        }
    }

    // Like collectByLocalName but also checks n itself (used when starting from document element)
    private static void collectByLocalNameWithSelf(Node n, String name, List<RuntimeScalar> out) {
        if (n.getNodeType() == Node.ELEMENT_NODE) {
            if (name.equals(n.getLocalName()) || "*".equals(name)) out.add(wrapNode(n));
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                collectByLocalNameWithSelf(children.item(i), name, out);
            }
        }
    }

    // getChildrenByTagName: direct children matching nodeName (qualified name)
    public static RuntimeList getChildrenByTagName(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        String name = args.get(1).toString();
        List<RuntimeScalar> results = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if ("*".equals(name) || name.equals(child.getNodeName())) {
                    results.add(wrapNode(child));
                }
            }
        }
        return buildNodeList(results, ctx);
    }

    // getChildrenByLocalName: direct children matching localName
    public static RuntimeList getChildrenByLocalName(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        String name = args.get(1).toString();
        List<RuntimeScalar> results = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if ("*".equals(name) || name.equals(child.getLocalName())) {
                    results.add(wrapNode(child));
                }
            }
        }
        return buildNodeList(results, ctx);
    }

    // getChildrenByTagNameNS: direct children matching namespace and localName
    public static RuntimeList getChildrenByTagNameNS(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        String ns    = nsArg(args.get(1));
        String local = args.get(2).toString();
        List<RuntimeScalar> results = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            boolean nsMatch  = "*".equals(ns)    || Objects.equals(ns,    child.getNamespaceURI());
            boolean nameMatch= "*".equals(local) || local.equals(child.getLocalName());
            if (nsMatch && nameMatch) results.add(wrapNode(child));
        }
        return buildNodeList(results, ctx);
    }

    private static RuntimeList buildNodeList(List<RuntimeScalar> results, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList rl = new RuntimeList();
            for (RuntimeScalar r : results) rl.add(r);
            return rl;
        }
        RuntimeArray arr = new RuntimeArray();
        for (RuntimeScalar r : results) RuntimeArray.push(arr, r);
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NodeList")).getList();
    }

    public static RuntimeList getAttributeNode(RuntimeArray args, int ctx) {
        return wrapNode(((Element) getNode(args.get(0))).getAttributeNode(args.get(1).toString())).getList();
    }

    public static RuntimeList getAttributeNodeNS(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        String ns  = args.size() > 1 ? nsArg(args.get(1)) : null;
        return wrapNode(el.getAttributeNodeNS(ns, args.get(2).toString())).getList();
    }

    public static RuntimeList setAttributeNode(RuntimeArray args, int ctx) {
        return wrapNode(((Element) getNode(args.get(0))).setAttributeNode(
            (Attr) getNode(args.get(1)))).getList();
    }

    public static RuntimeList setAttributeNodeNS(RuntimeArray args, int ctx) {
        Element el   = (Element) getNode(args.get(0));
        Attr    attr = (Attr)    getNode(args.get(1));
        Attr result  = el.setAttributeNodeNS(attr);
        // libxml2 quirk: if the attribute has a prefix whose namespace is not yet
        // declared anywhere in the ancestor chain, libxml2 places the xmlns: declaration
        // on the document root element rather than on the element receiving the attribute.
        String attrNS = attr.getNamespaceURI();
        String attrQName = attr.getName();
        if (attrNS != null && !attrNS.isEmpty()
                && attrQName != null && attrQName.contains(":")) {
            String attrPrefix = attrQName.substring(0, attrQName.indexOf(':'));
            // lookupNamespaceURI walks up the tree from el
            String scopedNS = el.lookupNamespaceURI(attrPrefix);
            if (scopedNS == null || !attrNS.equals(scopedNS)) {
                // Prefix not in scope — declare on document root
                Element root = el.getOwnerDocument().getDocumentElement();
                if (root != null) {
                    root.setAttributeNS(
                        "http://www.w3.org/2000/xmlns/",
                        "xmlns:" + attrPrefix, attrNS);
                }
            }
        }
        return wrapNode(result).getList();
    }

    public static RuntimeList appendTextChild(RuntimeArray args, int ctx) {
        Element el  = (Element) getNode(args.get(0));
        String name = args.get(1).toString();
        String text = args.size() > 2 ? args.get(2).toString() : "";
        Document doc = el.getOwnerDocument();
        Element child = doc.createElement(name);
        child.setTextContent(text);
        el.appendChild(child);
        return wrapNode(child).getList();
    }

    public static RuntimeList appendWellBalancedChunk(RuntimeArray args, int ctx) {
        Element el  = (Element) getNode(args.get(0));
        String xml  = args.get(1).toString();
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            Document tmp = db.parse(new InputSource(new StringReader("<fragment>" + xml + "</fragment>")));
            Document ownerDoc = el.getOwnerDocument();
            Node frag = ownerDoc.createDocumentFragment();
            NodeList children = tmp.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++)
                frag.appendChild(ownerDoc.importNode(children.item(i), true));
            el.appendChild(frag);
        } catch (Exception e) {
            throw new RuntimeException("appendWellBalancedChunk: " + e.getMessage(), e);
        }
        return scalarUndef.getList();
    }

    public static RuntimeList addNewChild(RuntimeArray args, int ctx) {
        Element parent = (Element) getNode(args.get(0));
        String ns   = args.size() > 1 ? nsArg(args.get(1)) : null;
        String name = args.get(2).toString();
        Document doc = parent.getOwnerDocument();
        Element child = ns != null ? doc.createElementNS(ns, name) : doc.createElement(name);
        parent.appendChild(child);
        return wrapNode(child).getList();
    }

    // ================================================================
    // XML::LibXML::Attr methods
    // ================================================================

    public static RuntimeList attrName(RuntimeArray args, int ctx) {
        // XML::LibXML's Attr->name returns the local name (without namespace prefix).
        // nodeName() returns the qualified name (prefix:local).
        Attr a = (Attr) getNode(args.get(0));
        String local = a.getLocalName();
        return new RuntimeScalar(local != null ? local : a.getName()).getList();
    }

    public static RuntimeList attrValue(RuntimeArray args, int ctx) {
        return new RuntimeScalar(((Attr) getNode(args.get(0))).getValue()).getList();
    }

    public static RuntimeList setAttrValue(RuntimeArray args, int ctx) {
        Attr a = (Attr) getNode(args.get(0));
        if (args.size() > 1) a.setValue(args.get(1).toString());
        return scalarUndef.getList();
    }

    public static RuntimeList attrOwnerElement(RuntimeArray args, int ctx) {
        return wrapNode(((Attr) getNode(args.get(0))).getOwnerElement()).getList();
    }

    public static RuntimeList attrIsId(RuntimeArray args, int ctx) {
        return (((Attr) getNode(args.get(0))).isId() ? scalarTrue : scalarFalse).getList();
    }

    // ================================================================
    // XML::LibXML::PI methods
    // ================================================================

    public static RuntimeList piTarget(RuntimeArray args, int ctx) {
        return new RuntimeScalar(((ProcessingInstruction) getNode(args.get(0))).getTarget()).getList();
    }

    public static RuntimeList piData(RuntimeArray args, int ctx) {
        return new RuntimeScalar(((ProcessingInstruction) getNode(args.get(0))).getData()).getList();
    }

    public static RuntimeList piSetData(RuntimeArray args, int ctx) {
        ProcessingInstruction pi = (ProcessingInstruction) getNode(args.get(0));
        if (args.size() <= 1) {
            pi.setData("");
            return scalarUndef.getList();
        }
        // If exactly one additional argument, set it directly
        if (args.size() == 2) {
            pi.setData(args.get(1).toString());
            return scalarUndef.getList();
        }
        // If multiple arguments (key => value pairs), format as XML PI attributes
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i + 1 < args.size(); i += 2) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args.get(i).toString())
              .append('=')
              .append('"')
              .append(escapeXmlAttr(args.get(i + 1).toString()))
              .append('"');
        }
        pi.setData(sb.toString());
        return scalarUndef.getList();
    }

    // ================================================================
    // XML::LibXML::XPathContext methods
    // ================================================================

    public static RuntimeList xpcNew(RuntimeArray args, int ctx) {
        RuntimeHash hash = new RuntimeHash();
        XPathContextState state = new XPathContextState();
        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            state.contextNode = getNode(args.get(1));
        }
        hash.put(XPC_KEY, new RuntimeScalar(state));
        return ReferenceOperators.bless(hash.createReferenceWithTrackedElements(),
            new RuntimeScalar("XML::LibXML::XPathContext")).getList();
    }

    private static XPathContextState getXpcState(RuntimeScalar self) {
        RuntimeScalar s = self.hashDeref().get(XPC_KEY);
        if (s != null && s.type == RuntimeScalarType.JAVAOBJECT && s.value instanceof XPathContextState)
            return (XPathContextState) s.value;
        throw new RuntimeException("Not a valid XML::LibXML::XPathContext object");
    }

    public static RuntimeList xpcSetContextNode(RuntimeArray args, int ctx) {
        getXpcState(args.get(0)).contextNode = getNode(args.get(1));
        return scalarTrue.getList();
    }

    public static RuntimeList xpcGetContextNode(RuntimeArray args, int ctx) {
        return wrapNode(getXpcState(args.get(0)).contextNode).getList();
    }

    public static RuntimeList xpcRegisterNs(RuntimeArray args, int ctx) {
        XPathContextState state = getXpcState(args.get(0));
        String prefix = args.get(1).toString();
        if (args.size() > 2 && args.get(2).getDefinedBoolean()) {
            state.namespaces.put(prefix, args.get(2).toString());
        } else {
            state.namespaces.remove(prefix);
        }
        return scalarTrue.getList();
    }

    public static RuntimeList xpcUnregisterNs(RuntimeArray args, int ctx) {
        getXpcState(args.get(0)).namespaces.remove(args.get(1).toString());
        return scalarTrue.getList();
    }

    public static RuntimeList xpcFindNodes(RuntimeArray args, int ctx) {
        XPathContextState state = getXpcState(args.get(0));
        String expr             = args.get(1).toString();
        Node contextNode        = (args.size() > 2 && args.get(2).getDefinedBoolean())
            ? getNode(args.get(2)) : state.contextNode;
        List<RuntimeScalar> nodes = evaluateXPathToNodeList(contextNode, expr, state.namespaces, state.customFunctions, state.varLookupCallback);
        RuntimeList result = new RuntimeList();
        for (RuntimeScalar n : nodes) result.add(n);
        return result;
    }

    public static RuntimeList xpcFind(RuntimeArray args, int ctx) {
        XPathContextState state = getXpcState(args.get(0));
        String expr             = args.get(1).toString();
        boolean existsOnly      = args.size() > 2 && args.get(2).getBoolean();
        return evaluateXPath(state.contextNode, expr, state.namespaces, existsOnly, state.customFunctions, state.varLookupCallback);
    }

    public static RuntimeList xpcFreeNodePool(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    public static RuntimeList xpcRegisterFunctionNS(RuntimeArray args, int ctx) {
        XPathContextState state  = getXpcState(args.get(0));
        String localName         = args.get(1).toString();
        String namespaceUri      = args.size() > 2 ? args.get(2).toString() : "";
        String key = "{" + namespaceUri + "}" + localName;
        if (args.size() < 4 || args.get(3).type == RuntimeScalarType.UNDEF) {
            // Unregister: remove the function
            state.customFunctions.remove(key);
        } else {
            state.customFunctions.put(key, args.get(3));
        }
        return scalarTrue.getList();
    }

    public static RuntimeList xpcRegisterVarLookupFunc(RuntimeArray args, int ctx) {
        XPathContextState state = getXpcState(args.get(0));
        // args[1] = callback (or undef to unregister), args[2] = ns context (ignored for now)
        RuntimeScalar callback = args.size() > 1 ? args.get(1) : null;
        if (callback != null && callback.type != RuntimeScalarType.UNDEF) {
            state.varLookupCallback = callback;
        } else {
            state.varLookupCallback = null;
        }
        return scalarTrue.getList();
    }

    // ================================================================
    // XML::LibXML::Common  encode/decode
    // ================================================================

    public static RuntimeList encodeToUTF8(RuntimeArray args, int ctx) {
        // encodeToUTF8($encoding, $string): convert $string from $encoding to a Unicode char string
        String enc = args.get(0).toString();
        if (args.size() < 2 || args.get(1).type == RuntimeScalarType.UNDEF) {
            return scalarUndef.getList();
        }
        String str = args.get(1).toString();
        if (str.isEmpty()) return new RuntimeScalar(str).getList();
        try {
            java.nio.charset.Charset charset = getCharsetFor(enc);
            // Treat input as raw bytes (ISO-8859-1 maps bytes 0-255 to chars 0-255)
            byte[] bytes = str.getBytes(StandardCharsets.ISO_8859_1);
            return new RuntimeScalar(new String(bytes, charset)).getList();
        } catch (java.nio.charset.UnsupportedCharsetException e) {
            return WarnDie.die(new RuntimeScalar("Unknown encoding: " + enc + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList decodeFromUTF8(RuntimeArray args, int ctx) {
        // decodeFromUTF8($encoding, $string): convert Unicode char string to $encoding byte string
        String enc = args.get(0).toString();
        if (args.size() < 2 || args.get(1).type == RuntimeScalarType.UNDEF) {
            return scalarUndef.getList();
        }
        String str = args.get(1).toString();
        if (str.isEmpty()) return new RuntimeScalar(str).getList();
        try {
            java.nio.charset.Charset charset = getCharsetFor(enc);
            byte[] bytes = str.getBytes(charset);
            // Return as Perl byte string (ISO-8859-1 maps bytes 0-255 back to chars)
            return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1)).getList();
        } catch (java.nio.charset.UnsupportedCharsetException e) {
            return WarnDie.die(new RuntimeScalar("Unknown encoding: " + enc + "\n"),
                new RuntimeScalar("\n")).getList();
        }
    }

    /** Map encoding name to Java Charset, using UTF-16LE for "UTF-16" (to avoid BOM). */
    private static java.nio.charset.Charset getCharsetFor(String enc) {
        // Use UTF-16LE for plain "UTF-16" to avoid the 2-byte BOM Java adds
        if ("UTF-16".equalsIgnoreCase(enc)) {
            return java.nio.charset.Charset.forName("UTF-16LE");
        }
        return java.nio.charset.Charset.forName(enc);
    }

    // ================================================================
    // XML::LibXML::CharacterData methods (Text, CDATASection, Comment)
    // ================================================================

    public static RuntimeList charSubstringData(RuntimeArray args, int ctx) {
        String data   = getNode(args.get(0)).getNodeValue();
        if (data == null) data = "";
        int offset = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        int count  = args.size() > 2 ? (int) args.get(2).getLong() : data.length();
        offset = Math.max(0, Math.min(offset, data.length()));
        int end = Math.min(offset + count, data.length());
        return new RuntimeScalar(data.substring(offset, end)).getList();
    }

    public static RuntimeList charAppendData(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String cur  = n.getNodeValue();
        String add  = args.size() > 1 ? args.get(1).toString() : "";
        n.setNodeValue((cur != null ? cur : "") + add);
        return scalarUndef.getList();
    }

    public static RuntimeList charInsertData(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String data = n.getNodeValue(); if (data == null) data = "";
        int offset  = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        String ins  = args.size() > 2 ? args.get(2).toString() : "";
        offset = Math.max(0, Math.min(offset, data.length()));
        n.setNodeValue(data.substring(0, offset) + ins + data.substring(offset));
        return scalarUndef.getList();
    }

    public static RuntimeList charDeleteData(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String data = n.getNodeValue(); if (data == null) data = "";
        int offset  = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        int count   = args.size() > 2 ? (int) args.get(2).getLong() : 0;
        offset = Math.max(0, Math.min(offset, data.length()));
        int end = Math.min(offset + count, data.length());
        n.setNodeValue(data.substring(0, offset) + data.substring(end));
        return scalarUndef.getList();
    }

    public static RuntimeList charReplaceData(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String data = n.getNodeValue(); if (data == null) data = "";
        int offset  = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        int count   = args.size() > 2 ? (int) args.get(2).getLong() : 0;
        String repl = args.size() > 3 ? args.get(3).toString() : "";
        offset = Math.max(0, Math.min(offset, data.length()));
        int end = Math.min(offset + count, data.length());
        n.setNodeValue(data.substring(0, offset) + repl + data.substring(end));
        return scalarUndef.getList();
    }

    public static RuntimeList charLength(RuntimeArray args, int ctx) {
        String data = getNode(args.get(0)).getNodeValue();
        return new RuntimeScalar(data != null ? data.length() : 0).getList();
    }

    public static RuntimeList textSplitText(RuntimeArray args, int ctx) {
        Text t = (Text) getNode(args.get(0));
        int offset = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        try {
            return wrapNode(t.splitText(offset)).getList();
        } catch (Exception e) {
            throw new RuntimeException("splitText: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // XML::LibXML::XPathExpression
    // ================================================================

    private static final String XPE_KEY = "_xpe_expr";

    public static RuntimeList xpeNew(RuntimeArray args, int ctx) {
        // args.get(0) = class name, args.get(1) = expression string
        String expr = args.size() > 1 ? args.get(1).toString() : "";
        // Validate the expression compiles
        try {
            XPATH_FACTORY.newXPath().compile(expr);
        } catch (XPathExpressionException e) {
            return WarnDie.die(new RuntimeScalar("XML::LibXML::XPathExpression: invalid expression: " + e.getMessage() + "\n"),
                new RuntimeScalar("\n")).getList();
        }
        RuntimeHash hash = new RuntimeHash();
        hash.put(XPE_KEY, new RuntimeScalar(expr));
        return ReferenceOperators.bless(hash.createReferenceWithTrackedElements(),
            new RuntimeScalar("XML::LibXML::XPathExpression")).getList();
    }

    public static RuntimeList xpeExpression(RuntimeArray args, int ctx) {
        RuntimeScalar s = args.get(0).hashDeref().get(XPE_KEY);
        return (s != null ? s : scalarEmptyString).getList();
    }

    // ================================================================
    // Internal XPath helpers
    // ================================================================

    /** Extract XPath expression string from a string arg or XPathExpression object */
    private static String toXPathString(RuntimeScalar arg) {
        if (arg == null) return "";
        // Try to deref as a hash; if it contains XPE_KEY it's an XPathExpression
        try {
            RuntimeHash h = arg.hashDeref();
            RuntimeScalar s = h.get(XPE_KEY);
            if (s != null) return s.toString();
        } catch (Exception ignored) {}
        return arg.toString();
    }

    /**
     * Collect all namespace prefix → URI mappings declared anywhere in the document.
     * This lets plain findnodes("//a:foo") work even without an explicit XPathContext.
     */
    private static Map<String, String> collectDocumentNamespaces(Node contextNode) {
        Map<String, String> ns = new LinkedHashMap<>();
        Document doc = contextNode.getNodeType() == Node.DOCUMENT_NODE
            ? (Document) contextNode : contextNode.getOwnerDocument();
        if (doc != null) collectNsFromNode(doc.getDocumentElement(), ns);
        return ns;
    }

    private static void collectNsFromNode(Node n, Map<String, String> ns) {
        if (n == null) return;
        if (n.getNodeType() == Node.ELEMENT_NODE) {
            NamedNodeMap attrs = n.getAttributes();
            if (attrs != null) {
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node a = attrs.item(i);
                    String attrName = a.getNodeName();
                    if (attrName.startsWith("xmlns:")) {
                        String prefix = attrName.substring(6);
                        // First-encountered wins (outer scope takes priority)
                        ns.putIfAbsent(prefix, a.getNodeValue());
                    }
                }
            }
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                collectNsFromNode(children.item(i), ns);
            }
        }
    }

    /**
     * XPathFunctionResolver that calls Perl code refs registered via registerFunctionNS.
     */
    static class PerlFunctionResolver implements XPathFunctionResolver {
        private final Map<String, RuntimeScalar> functions;

        PerlFunctionResolver(Map<String, RuntimeScalar> functions) {
            this.functions = functions;
        }

        @Override
        public XPathFunction resolveFunction(QName functionName, int arity) {
            String nsUri = functionName.getNamespaceURI();
            if (nsUri == null) nsUri = "";
            String key = "{" + nsUri + "}" + functionName.getLocalPart();
            RuntimeScalar callback = functions.get(key);
            if (callback == null) {
                // Return a function that throws when invoked so the XPath error propagates.
                // (Returning null causes Xalan/JAXP to silently return empty instead of erroring.)
                final String missingKey = key;
                return (xpathArgs) -> {
                    throw new javax.xml.xpath.XPathFunctionException(
                        "Could not find function: " + functionName.getLocalPart());
                };
            }
            // Built-in Java function stored as JAVAOBJECT(XPathFunction) sentinel
            if (callback.type == RuntimeScalarType.JAVAOBJECT && callback.value instanceof XPathFunction) {
                return (XPathFunction) callback.value;
            }
            return (xpathArgs) -> {
                // Convert XPath argument types to Perl RuntimeScalars
                RuntimeArray perlArgs = new RuntimeArray();
                for (Object arg : xpathArgs) {
                    if (arg instanceof String)   perlArgs.push(new RuntimeScalar((String) arg));
                    else if (arg instanceof Double) perlArgs.push(new RuntimeScalar((Double) arg));
                    else if (arg instanceof Boolean) perlArgs.push(new RuntimeScalar(((Boolean) arg) ? 1 : 0));
                    else if (arg instanceof NodeList) {
                        // Wrap as XML::LibXML::NodeList blessed array ref — single argument
                        NodeList nl = (NodeList) arg;
                        RuntimeArray arr = new RuntimeArray();
                        for (int i = 0; i < nl.getLength(); i++) RuntimeArray.push(arr, wrapNode(nl.item(i)));
                        perlArgs.push(ReferenceOperators.bless(arr.createReference(),
                            new RuntimeScalar("XML::LibXML::NodeList")));
                    } else {
                        perlArgs.push(new RuntimeScalar(arg == null ? "" : arg.toString()));
                    }
                }
                RuntimeList result = RuntimeCode.apply(callback, perlArgs, RuntimeContextType.SCALAR);
                RuntimeScalar first = result.getFirst();
                // Convert Perl return to XPath type
                if (first.type == RuntimeScalarType.ARRAYREFERENCE) {
                    // Could be a blessed XML::LibXML::NodeList — convert to Java NodeList
                    RuntimeArray arr = (RuntimeArray) ((RuntimeBase) first.value);
                    List<Node> nodes = new ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        Node n = getNode(arr.get(i));
                        if (n != null) nodes.add(n);
                    }
                    return (NodeList) new NodeList() {
                        public Node item(int i) { return (i >= 0 && i < nodes.size()) ? nodes.get(i) : null; }
                        public int getLength() { return nodes.size(); }
                    };
                }
                if (first.type == RuntimeScalarType.JAVAOBJECT || first.type == RuntimeScalarType.HASHREFERENCE) {
                    // It's a single node — return as a 1-element NodeList
                    Node n = getNode(first);
                    if (n != null) {
                        return (NodeList) new NodeList() {
                            public Node item(int i) { return i == 0 ? n : null; }
                            public int getLength() { return 1; }
                        };
                    }
                }
                // Try numeric first; if it looks like a number, return Double
                try {
                    return Double.parseDouble(first.toString());
                } catch (NumberFormatException e2) {
                    return first.toString();
                }
            };
        }
    }

    /**
     * XPathVariableResolver that calls a Perl callback registered via registerVarLookupFunc.
     * The callback is invoked with (varName, nsUri) and should return a value.
     */
    static class PerlVariableResolver implements javax.xml.xpath.XPathVariableResolver {
        private final RuntimeScalar callback;

        PerlVariableResolver(RuntimeScalar callback) {
            this.callback = callback;
        }

        @Override
        public Object resolveVariable(QName variableName) {
            // Call the Perl callback with (varName, nsUri)
            RuntimeArray perlArgs = new RuntimeArray();
            perlArgs.push(new RuntimeScalar(variableName.getLocalPart()));
            String nsUri = variableName.getNamespaceURI();
            perlArgs.push(nsUri != null && !nsUri.isEmpty()
                ? new RuntimeScalar(nsUri) : RuntimeScalarCache.scalarUndef);
            RuntimeList result = RuntimeCode.apply(callback, perlArgs, RuntimeContextType.SCALAR);
            RuntimeScalar first = result.getFirst();
            if (first == null || first.type == RuntimeScalarType.UNDEF) return null;
            if (first.type == RuntimeScalarType.ARRAYREFERENCE) {
                // Blessed XML::LibXML::NodeList or plain array ref — convert to NodeList
                RuntimeArray arr = (RuntimeArray) ((RuntimeBase) first.value);
                List<Node> nodes = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    Node n = getNode(arr.get(i));
                    if (n != null) nodes.add(n);
                }
                return (NodeList) new NodeList() {
                    public Node item(int i) { return (i >= 0 && i < nodes.size()) ? nodes.get(i) : null; }
                    public int getLength() { return nodes.size(); }
                };
            }
            if (first.type == RuntimeScalarType.JAVAOBJECT || first.type == RuntimeScalarType.HASHREFERENCE) {
                Node n = getNode(first);
                if (n != null) {
                    return (NodeList) new NodeList() {
                        public Node item(int i) { return i == 0 ? n : null; }
                        public int getLength() { return 1; }
                    };
                }
            }
            try { return Double.parseDouble(first.toString()); }
            catch (NumberFormatException ignored) { return first.toString(); }
        }
    }


    /**
     * XPathFunctionResolver that handles built-in XSLT-style functions
     * (e.g. document()) and chains to a user-provided resolver.
     */
    static class BuiltinXPathFunctionResolver implements XPathFunctionResolver {
        private final XPathFunctionResolver userResolver;
        private final Node contextNode;

        BuiltinXPathFunctionResolver(XPathFunctionResolver userResolver, Node contextNode) {
            this.userResolver = userResolver;
            this.contextNode = contextNode;
        }

        @Override
        public XPathFunction resolveFunction(QName functionName, int arity) {
            String localPart = functionName.getLocalPart();
            String nsUri = functionName.getNamespaceURI();

            // Handle document(string) - XSLT extension function
            if ("document".equals(localPart) && (nsUri == null || nsUri.isEmpty())) {
                return (args) -> {
                    if (args.isEmpty()) return emptyNodeList();
                    String uriStr = args.get(0) == null ? "" : args.get(0).toString();
                    try {
                        // Resolve relative to contextNode's document URI or CWD
                        File f;
                        String baseUriStr = null;
                        if (contextNode != null) {
                            Document doc = (contextNode instanceof Document)
                                ? (Document) contextNode : contextNode.getOwnerDocument();
                            if (doc != null) baseUriStr = doc.getDocumentURI();
                        }
                        if (baseUriStr != null && !uriStr.startsWith("/") && !uriStr.contains("://")) {
                            URI base = new URI(baseUriStr);
                            URI resolved = base.resolve(uriStr);
                            f = new File(resolved);
                        } else {
                            f = new File(uriStr);
                        }
                        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                        dbf.setNamespaceAware(true);
                        DocumentBuilder db = dbf.newDocumentBuilder();
                        Document loadedDoc = db.parse(f);
                        final Document finalDoc = loadedDoc;
                        return (NodeList) new NodeList() {
                            public Node item(int i) { return i == 0 ? finalDoc : null; }
                            public int getLength() { return 1; }
                        };
                    } catch (Exception e) {
                        throw new javax.xml.xpath.XPathFunctionException("document() failed: " + e.getMessage());
                    }
                };
            }

            // Chain to user resolver
            if (userResolver != null) {
                return userResolver.resolveFunction(functionName, arity);
            }
            return null;
        }

        private static NodeList emptyNodeList() {
            return new NodeList() {
                public Node item(int i) { return null; }
                public int getLength() { return 0; }
            };
        }
    }


    /**
     * Adds built-in XSLT-style XPath functions (like document()) to the function map
     * using the JAVAOBJECT sentinel mechanism so PerlFunctionResolver can dispatch them.
     * Uses the "{}name" key format so rewriteNoNsFunctions picks them up.
     */
    private static void addBuiltinXPathFunctions(Map<String, RuntimeScalar> funcs, Node contextNode) {
        // document(string) — load an XML file and return its document node
        XPathFunction documentFunc = (args) -> {
            if (args.isEmpty()) return emptyNodeList();
            String uriStr = args.get(0) == null ? "" : args.get(0).toString();
            try {
                File f;
                String baseUriStr = null;
                if (contextNode != null) {
                    Document doc = (contextNode instanceof Document)
                        ? (Document) contextNode : contextNode.getOwnerDocument();
                    if (doc != null) baseUriStr = doc.getDocumentURI();
                }
                if (baseUriStr != null && !uriStr.startsWith("/") && !uriStr.contains("://")) {
                    URI base = new URI(baseUriStr);
                    URI resolved = base.resolve(uriStr);
                    f = new File(resolved);
                } else {
                    f = new File(uriStr);
                }
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document loadedDoc = db.parse(f);
                final Document finalDoc = loadedDoc;
                return (NodeList) new NodeList() {
                    public Node item(int i) { return i == 0 ? finalDoc : null; }
                    public int getLength() { return 1; }
                };
            } catch (Exception e) {
                throw new javax.xml.xpath.XPathFunctionException("document() failed: " + e.getMessage());
            }
        };
        RuntimeScalar documentSentinel = new RuntimeScalar();
        documentSentinel.type = RuntimeScalarType.JAVAOBJECT;
        documentSentinel.value = documentFunc;
        // Only add if not already overridden by user
        funcs.putIfAbsent("{}document", documentSentinel);
    }

    private static NodeList emptyNodeList() {
        return new NodeList() {
            public Node item(int i) { return null; }
            public int getLength() { return 0; }
        };
    }

    /**
     * Rewrites an XPath expression to add a pseudo-namespace prefix to
     * no-namespace custom function calls.  Java's JAXP XPath only calls
     * XPathFunctionResolver for namespace-prefixed functions; plain names
     * are rejected as "unknown function".  We work around this by:
     *   1. Finding all "{}name" entries in customFunctions.
     *   2. Replacing bare `name(` with `__pns__:name(` in the expression.
     *   3. Adding NONS_NS to the namespace map under the NONS_PREFIX alias.
     *   4. Registering the same callback also under the "{NONS_NS}name" key.
     * Returns the (possibly modified) expression; the ns map is mutated in place.
     */
    private static String rewriteNoNsFunctions(String expr,
            Map<String, String> ns, Map<String, RuntimeScalar> customFunctions) {
        if (customFunctions == null || customFunctions.isEmpty()) return expr;

        // Collect plain (no-namespace) function names
        Map<String, RuntimeScalar> extras = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeScalar> e : customFunctions.entrySet()) {
            if (e.getKey().startsWith("{}")) {
                String funcName = e.getKey().substring(2);
                extras.put(funcName, e.getValue());
            }
        }
        if (extras.isEmpty()) return expr;

        // Add pseudo-namespace mapping and register functions under it
        ns.put(NONS_PREFIX, NONS_NS);
        for (Map.Entry<String, RuntimeScalar> e : extras.entrySet()) {
            customFunctions.put("{" + NONS_NS + "}" + e.getKey(), e.getValue());
        }

        // Rewrite: replace bare `funcName(` → `__pns__:funcName(` in the expression
        // Only replace when NOT already prefixed (char before is not ':') and followed by `(`
        for (String funcName : extras.keySet()) {
            expr = expr.replaceAll(
                "(?<![:\\w])" + java.util.regex.Pattern.quote(funcName) + "(?=\\s*\\()",
                NONS_PREFIX + ":" + funcName);
        }
        return expr;
    }

    /**
     * If the XPathExpressionException was caused by a Perl die, re-throw it.
     * Otherwise return the exception for normal handling.
     */
    private static void rethrowIfPerlDie(XPathExpressionException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof PerlDieException) throw (PerlDieException) cause;
            if (cause instanceof RuntimeException && cause.getCause() instanceof PerlDieException)
                throw (PerlDieException) cause.getCause();
            cause = cause.getCause();
        }
    }

    /** Returns true if this XPathExpressionException is about a function not being found. */
    private static boolean isFunctionNotFoundError(XPathExpressionException e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lc = msg.toLowerCase(java.util.Locale.ROOT);
                if (lc.contains("could not find function") ||
                    lc.contains("unknown function") ||
                    lc.contains("undeclared function") ||
                    (lc.contains("function") && lc.contains("not found"))) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private static List<RuntimeScalar> evaluateXPathToNodeList(
            Node contextNode, String expr, Map<String, String> namespaces,
            Map<String, RuntimeScalar> customFunctions) {
        return evaluateXPathToNodeList(contextNode, expr, namespaces, customFunctions, null);
    }

    private static List<RuntimeScalar> evaluateXPathToNodeList(
            Node contextNode, String expr, Map<String, String> namespaces,
            Map<String, RuntimeScalar> customFunctions, RuntimeScalar varLookupCallback) {
        List<RuntimeScalar> results = new ArrayList<>();
        if (contextNode == null) return results;
        try {
            XPath xp = XPATH_FACTORY.newXPath();
            Map<String, String> ns = new LinkedHashMap<>(namespaces != null ? namespaces : collectDocumentNamespaces(contextNode));
            Map<String, RuntimeScalar> funcs = customFunctions != null
                ? new LinkedHashMap<>(customFunctions) : new LinkedHashMap<>();

            // Register built-in XSLT-style functions (e.g. document()) with the
            // namespace-rewriting mechanism so JAXP dispatches them correctly.
            addBuiltinXPathFunctions(funcs, contextNode);

            expr = rewriteNoNsFunctions(expr, ns, funcs);
            if (!ns.isEmpty())
                xp.setNamespaceContext(new SimpleNamespaceContext(ns));
            xp.setXPathFunctionResolver(new PerlFunctionResolver(funcs));
            if (varLookupCallback != null && varLookupCallback.type != RuntimeScalarType.UNDEF)
                xp.setXPathVariableResolver(new PerlVariableResolver(varLookupCallback));
            NodeList nl = (NodeList) xp.evaluate(expr, contextNode, XPathConstants.NODESET);
            for (int i = 0; i < nl.getLength(); i++) results.add(wrapNode(nl.item(i)));
        } catch (XPathExpressionException e) {
            rethrowIfPerlDie(e);
            throw new RuntimeException("XPath error in findnodes('" + expr + "'): " + e.getMessage(), e);
        }
        return results;
    }

    private static RuntimeList evaluateXPath(Node contextNode, String expr,
            Map<String, String> namespaces, boolean existsOnly) {
        return evaluateXPath(contextNode, expr, namespaces, existsOnly, null, null);
    }

    private static RuntimeList evaluateXPath(Node contextNode, String expr,
            Map<String, String> namespaces, boolean existsOnly,
            Map<String, RuntimeScalar> customFunctions) {
        return evaluateXPath(contextNode, expr, namespaces, existsOnly, customFunctions, null);
    }

    private static RuntimeList evaluateXPath(Node contextNode, String expr,
            Map<String, String> namespaces, boolean existsOnly,
            Map<String, RuntimeScalar> customFunctions, RuntimeScalar varLookupCallback) {
        if (contextNode == null) {
            RuntimeList r = new RuntimeList();
            r.add(new RuntimeScalar("XML::LibXML::NodeList"));
            return r;
        }
        XPath xp = XPATH_FACTORY.newXPath();
        Map<String, String> ns = new LinkedHashMap<>(namespaces != null ? namespaces : collectDocumentNamespaces(contextNode));
        Map<String, RuntimeScalar> funcs = customFunctions != null ? new LinkedHashMap<>(customFunctions) : null;
        expr = rewriteNoNsFunctions(expr, ns, funcs);
        if (!ns.isEmpty())
            xp.setNamespaceContext(new SimpleNamespaceContext(ns));
        if (funcs != null && !funcs.isEmpty())
            xp.setXPathFunctionResolver(new PerlFunctionResolver(funcs));
        if (varLookupCallback != null && varLookupCallback.type != RuntimeScalarType.UNDEF)
            xp.setXPathVariableResolver(new PerlVariableResolver(varLookupCallback));

        XPathExpressionException funcNotFoundError = null;

        // Try NODESET first — only return if it actually has nodes
        try {
            NodeList nl = (NodeList) xp.evaluate(expr, contextNode, XPathConstants.NODESET);
            if (nl.getLength() > 0) {
                if (existsOnly) return scalarTrue.getList();
                RuntimeList result = new RuntimeList();
                result.add(new RuntimeScalar("XML::LibXML::NodeList"));
                for (int i = 0; i < nl.getLength(); i++) result.add(wrapNode(nl.item(i)));
                return result;
            }
        } catch (XPathExpressionException e) {
            rethrowIfPerlDie(e);
            if (funcNotFoundError == null && isFunctionNotFoundError(e)) funcNotFoundError = e;
        }

        // Try NUMBER — catches numeric literals and math expressions
        try {
            Double num = (Double) xp.evaluate(expr, contextNode, XPathConstants.NUMBER);
            funcNotFoundError = null; // expression is valid — clear any saved function error
            if (!num.isNaN()) {
                // Check if it's actually a STRING expression (string returns "true"/"false" for booleans)
                String str = (String) xp.evaluate(expr, contextNode, XPathConstants.STRING);
                if (str != null && (str.equals("true") || str.equals("false"))) {
                    // It's a boolean expression
                    boolean boolVal = str.equals("true");
                    if (existsOnly) return new RuntimeScalar(boolVal ? 1 : 0).getList();
                    RuntimeList r = new RuntimeList();
                    r.add(new RuntimeScalar("XML::LibXML::Boolean"));
                    r.add(new RuntimeScalar(boolVal ? 1 : 0));
                    return r;
                }
                if (existsOnly) return new RuntimeScalar(num != 0 ? 1 : 0).getList();
                RuntimeList r = new RuntimeList();
                r.add(new RuntimeScalar("XML::LibXML::Number"));
                r.add(new RuntimeScalar(num));
                return r;
            }
        } catch (XPathExpressionException e) {
            rethrowIfPerlDie(e);
            if (funcNotFoundError == null && isFunctionNotFoundError(e)) funcNotFoundError = e;
        }

        // Try STRING
        try {
            String str = (String) xp.evaluate(expr, contextNode, XPathConstants.STRING);
            funcNotFoundError = null; // expression is valid — clear any saved function error
            if (str != null && !str.isEmpty()) {
                if (existsOnly) return scalarTrue.getList();
                RuntimeList r = new RuntimeList();
                r.add(new RuntimeScalar("XML::LibXML::Literal"));
                r.add(new RuntimeScalar(str));
                return r;
            }
        } catch (XPathExpressionException e) {
            rethrowIfPerlDie(e);
            if (funcNotFoundError == null && isFunctionNotFoundError(e)) funcNotFoundError = e;
        }

        // Try BOOLEAN
        try {
            Boolean bool = (Boolean) xp.evaluate(expr, contextNode, XPathConstants.BOOLEAN);
            funcNotFoundError = null; // expression is valid
            if (existsOnly) return new RuntimeScalar(bool ? 1 : 0).getList();
            RuntimeList r = new RuntimeList();
            r.add(new RuntimeScalar("XML::LibXML::Boolean"));
            r.add(new RuntimeScalar(bool ? 1 : 0));
            return r;
        } catch (XPathExpressionException e) {
            rethrowIfPerlDie(e);
            if (funcNotFoundError == null && isFunctionNotFoundError(e)) funcNotFoundError = e;
        }

        // Fallback: propagate function-not-found, or return empty NodeList
        if (funcNotFoundError != null) {
            Throwable root = funcNotFoundError;
            while (root.getCause() != null) root = root.getCause();
            throw new PerlDieException(new RuntimeScalar("XPath error: " + root.getMessage() + "\n"));
        }
        if (existsOnly) return scalarFalse.getList();
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar("XML::LibXML::NodeList"));
        return result;
    }

    private static RuntimeList nodeListToRuntimeList(NodeList nl, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList rl = new RuntimeList();
            for (int i = 0; i < nl.getLength(); i++) rl.add(wrapNode(nl.item(i)));
            return rl;
        }
        RuntimeArray arr = new RuntimeArray();
        for (int i = 0; i < nl.getLength(); i++) RuntimeArray.push(arr, wrapNode(nl.item(i)));
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NodeList")).getList();
    }

    /**
     * Convert the (type, @params) raw result from evaluateXPath into an
     * actual Perl object (NodeList, Literal, Number, or Boolean instance).
     * Used by the public-facing find() method.
     */
    private static RuntimeList wrapXPathResult(RuntimeList raw) {
        if (raw.elements.isEmpty()) return scalarUndef.getList();
        String type = raw.elements.get(0).toString();
        if ("XML::LibXML::NodeList".equals(type)) {
            RuntimeArray arr = new RuntimeArray();
            for (int i = 1; i < raw.elements.size(); i++) {
                RuntimeArray.push(arr, (RuntimeScalar) raw.elements.get(i));
            }
            return ReferenceOperators.bless(arr.createReference(),
                new RuntimeScalar("XML::LibXML::NodeList")).getList();
        }
        if ("XML::LibXML::Literal".equals(type)) {
            RuntimeScalar str = raw.elements.size() > 1
                ? (RuntimeScalar) raw.elements.get(1) : scalarEmptyString;
            return ReferenceOperators.bless(str.createReference(),
                new RuntimeScalar("XML::LibXML::Literal")).getList();
        }
        if ("XML::LibXML::Number".equals(type)) {
            RuntimeScalar num = raw.elements.size() > 1
                ? (RuntimeScalar) raw.elements.get(1) : new RuntimeScalar(0);
            return ReferenceOperators.bless(num.createReference(),
                new RuntimeScalar("XML::LibXML::Number")).getList();
        }
        if ("XML::LibXML::Boolean".equals(type)) {
            RuntimeScalar bv = raw.elements.size() > 1
                ? (RuntimeScalar) raw.elements.get(1) : scalarFalse;
            return ReferenceOperators.bless(bv.createReference(),
                new RuntimeScalar("XML::LibXML::Boolean")).getList();
        }
        return raw;
    }

    // ================================================================
    // Additional Node methods
    // ================================================================

    public static RuntimeList unique_key(RuntimeArray args, int ctx) {
        // Returns a unique integer for node identity (like libxml2's pointer address).
        // Uses Java's identity hash code as a proxy.
        Node n = getNode(args.get(0));
        return new RuntimeScalar(System.identityHashCode(n)).getList();
    }

    public static RuntimeList nodeBaseURI(RuntimeArray args, int ctx) {
        // JDK DOM does not track xml:base; return document URI.
        // When no URI was set (parse_string), libxml2 returns "unknown-0" — match that behaviour.
        Node n = getNode(args.get(0));
        String base = null;
        if (n.getNodeType() == Node.DOCUMENT_NODE) {
            base = ((Document) n).getDocumentURI();
        } else {
            Document doc = n.getOwnerDocument();
            if (doc != null) base = doc.getDocumentURI();
        }
        if (base == null) base = "unknown-0";
        return new RuntimeScalar(base).getList();
    }

    public static RuntimeList nodeSetBaseURI(RuntimeArray args, int ctx) {
        // Store as documentURI on the owning document (best effort)
        Node n = getNode(args.get(0));
        String uri = args.size() > 1 ? args.get(1).toString() : null;
        Document doc = (n.getNodeType() == Node.DOCUMENT_NODE) ? (Document) n : n.getOwnerDocument();
        if (doc != null && uri != null) doc.setDocumentURI(uri);
        return scalarUndef.getList();
    }

    public static RuntimeList nodeAddSibling(RuntimeArray args, int ctx) {
        Node node    = getNode(args.get(0));
        Node sibling = getNode(args.get(1));
        Node parent  = node.getParentNode();
        if (parent != null) {
            Node next = node.getNextSibling();
            parent.insertBefore(sibling, next);
        }
        return wrapNode(sibling).getList();
    }

    // ================================================================
    // Document compression (stub — JDK does not support libxml2 gzip)
    // ================================================================

    public static RuntimeList docCompression(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-1).getList();  // -1 = no compression
    }

    public static RuntimeList docSetCompression(RuntimeArray args, int ctx) {
        return scalarUndef.getList();  // no-op
    }

    // ================================================================
    // Element extra methods
    // ================================================================

    public static RuntimeList elemRemoveAttributeNode(RuntimeArray args, int ctx) {
        Element el = (Element) getNode(args.get(0));
        Attr    attr = (Attr) getNode(args.get(1));
        try {
            return wrapNode(el.removeAttributeNode(attr)).getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    // ================================================================
    // Text / Comment constructors
    // ================================================================

    public static RuntimeList textNew(RuntimeArray args, int ctx) {
        String content = args.size() > 1 ? args.get(1).toString() : "";
        return wrapNode(getScratchDoc().createTextNode(content)).getList();
    }

    public static RuntimeList commentNew(RuntimeArray args, int ctx) {
        String content = args.size() > 1 ? args.get(1).toString() : "";
        return wrapNode(getScratchDoc().createComment(content)).getList();
    }

    // ================================================================
    // CharacterData replaceDataString / replaceDataRegEx
    // ================================================================

    /** replaceDataString($old, $new, $flag) — plain string substitution */
    public static RuntimeList charReplaceDataString(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String data = n.getNodeValue(); if (data == null) data = "";
        String oldStr = args.size() > 1 ? args.get(1).toString() : "";
        String newStr = args.size() > 2 ? args.get(2).toString() : "";
        // flag arg 3 (1 = regex interpretation of oldStr) — for plain string, always literal
        n.setNodeValue(data.replace(oldStr, newStr));
        return scalarUndef.getList();
    }

    /** replaceDataRegEx($pattern, $replacement, $flags) */
    public static RuntimeList charReplaceDataRegEx(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        String data    = n.getNodeValue(); if (data == null) data = "";
        String pattern = args.size() > 1 ? args.get(1).toString() : "";
        String repl    = args.size() > 2 ? args.get(2).toString() : "";
        String flags   = args.size() > 3 ? args.get(3).toString() : "";
        // Convert Perl replacement string ($1 → $1 is also Java's back-ref syntax)
        int jflags = 0;
        if (flags.contains("i")) jflags |= java.util.regex.Pattern.CASE_INSENSITIVE;
        try {
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(pattern, jflags);
            String result = flags.contains("g")
                ? pat.matcher(data).replaceAll(repl)
                : pat.matcher(data).replaceFirst(repl);
            n.setNodeValue(result);
        } catch (Exception e) {
            // Bad regex — leave data unchanged
        }
        return scalarUndef.getList();
    }
}

