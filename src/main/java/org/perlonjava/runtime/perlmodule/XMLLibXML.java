package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.*;

import java.io.*;
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
        boolean keepBlanks  = true;
        boolean recover     = false;
    }

    static class XPathContextState {
        Node contextNode;
        final Map<String, String> namespaces = new LinkedHashMap<>();
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
            module.registerMethod("LIBXML_RUNTIME_VERSION", null);
            module.registerMethod("LIBXML_VERSION",       null);
            module.registerMethod("INIT_THREAD_SUPPORT",  null);
            module.registerMethod("DISABLE_THREAD_SUPPORT", null);
            module.registerMethod("encodeToUTF8",         null);
            module.registerMethod("decodeFromUTF8",       null);

            // Node methods
            String nodePkg = "XML::LibXML::Node";
            String[][] nodeMethods = {
                {"nodeName"},   {"nodeValue"},   {"nodeType"},
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
                {"localname"}, {"prefix"}, {"namespaceURI"},
                {"nodePath"}, {"line_number"},
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
                {"getAttributeNode"},  {"setAttributeNode"},
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
            };
            for (String[] m : elemMethods) {
                module.registerMethodInPackage(elemPkg, m[0], m.length > 1 ? m[1] : m[0]);
            }
            // Element constructor: XML::LibXML::Element->new($name)
            module.registerMethodInPackage(elemPkg, "new", "elemNew");

            // Attr methods
            module.registerMethodInPackage("XML::LibXML::Attr", "name",         "attrName");
            module.registerMethodInPackage("XML::LibXML::Attr", "value",        "attrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "getValue",     "attrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "setValue",     "setAttrValue");
            module.registerMethodInPackage("XML::LibXML::Attr", "ownerElement", "attrOwnerElement");
            module.registerMethodInPackage("XML::LibXML::Attr", "isId",         "attrIsId");

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
        }
        return opts;
    }

    private static DocumentBuilder newBuilder(ParserOptions opts) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            if (!opts.keepBlanks) f.setIgnoringElementContentWhitespace(true);
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
        return _parse_string(args, ctx); // Tier B stub
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

    public static RuntimeList attributes(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        NamedNodeMap attrs = n.getAttributes();
        if (attrs == null) {
            // Non-element nodes (Text, Comment, etc.) have no attributes.
            // Return undef in scalar context, empty list in list context.
            return ctx == RuntimeContextType.LIST ? new RuntimeList() : scalarUndef.getList();
        }
        RuntimeArray arr = new RuntimeArray();
        for (int i = 0; i < attrs.getLength(); i++) RuntimeArray.push(arr, wrapNode(attrs.item(i)));
        return ReferenceOperators.bless(arr.createReference(),
            new RuntimeScalar("XML::LibXML::NamedNodeMap")).getList();
    }

    public static RuntimeList cloneNode(RuntimeArray args, int ctx) {
        Node n = getNode(args.get(0));
        boolean deep = args.size() < 2 || args.get(1).getBoolean();
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
        return wrapNode(child).getList();
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
        parent.insertBefore(newChild, refChild);
        return wrapNode(newChild).getList();
    }

    public static RuntimeList insertAfter(RuntimeArray args, int ctx) {
        Node parent   = getNode(args.get(0));
        Node newChild = getNode(args.get(1));
        Node refChild = (args.size() > 2 && args.get(2).getDefinedBoolean()) ? getNode(args.get(2)) : null;
        Node nextRef  = (refChild != null) ? refChild.getNextSibling() : null;
        parent.insertBefore(newChild, nextRef);
        return wrapNode(newChild).getList();
    }

    public static RuntimeList removeChild(RuntimeArray args, int ctx) {
        Node parent = getNode(args.get(0));
        Node child  = getNode(args.get(1));
        parent.removeChild(child);
        return wrapNode(child).getList();
    }

    public static RuntimeList replaceChild(RuntimeArray args, int ctx) {
        Node parent   = getNode(args.get(0));
        Node newChild = getNode(args.get(1));
        Node oldChild = getNode(args.get(2));
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
        return (n.hasChildNodes() ? scalarTrue : scalarFalse).getList();
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
        boolean act = args.size() < 4 || args.get(3).getBoolean();
        if (n instanceof Element && pfx != null && ns != null && act) {
            ((Element) n).setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:" + pfx, ns);
        }
        return scalarTrue.getList();
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
        List<RuntimeScalar> nodes = evaluateXPathToNodeList(node, expr, null);
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
        String version  = args.size() > 1 ? args.get(1).toString() : "1.0";
        String encoding = args.size() > 2 ? args.get(2).toString() : null;
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
        String prefix = args.size() > 1 ? args.get(1).toString() : "";
        String uri = el.lookupNamespaceURI(prefix.isEmpty() ? null : prefix);
        return (uri != null ? new RuntimeScalar(uri) : scalarUndef).getList();
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
        List<RuntimeScalar> nodes = evaluateXPathToNodeList(contextNode, expr, state.namespaces);
        RuntimeList result = new RuntimeList();
        for (RuntimeScalar n : nodes) result.add(n);
        return result;
    }

    public static RuntimeList xpcFind(RuntimeArray args, int ctx) {
        XPathContextState state = getXpcState(args.get(0));
        String expr             = args.get(1).toString();
        boolean existsOnly      = args.size() > 2 && args.get(2).getBoolean();
        return evaluateXPath(state.contextNode, expr, state.namespaces, existsOnly);
    }

    public static RuntimeList xpcFreeNodePool(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    public static RuntimeList xpcRegisterFunctionNS(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList xpcRegisterVarLookupFunc(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    // ================================================================
    // XML::LibXML::Common  encode/decode
    // ================================================================

    public static RuntimeList encodeToUTF8(RuntimeArray args, int ctx) {
        // encodeToUTF8($encoding, $string) — on JVM strings are already Unicode
        String str = args.size() > 1 ? args.get(1).toString() : args.get(0).toString();
        return new RuntimeScalar(str).getList();
    }

    public static RuntimeList decodeFromUTF8(RuntimeArray args, int ctx) {
        String str = args.size() > 1 ? args.get(1).toString() : args.get(0).toString();
        return new RuntimeScalar(str).getList();
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

    private static List<RuntimeScalar> evaluateXPathToNodeList(
            Node contextNode, String expr, Map<String, String> namespaces) {
        List<RuntimeScalar> results = new ArrayList<>();
        if (contextNode == null) return results;
        try {
            XPath xp = XPATH_FACTORY.newXPath();
            Map<String, String> ns = namespaces != null ? namespaces : collectDocumentNamespaces(contextNode);
            if (!ns.isEmpty())
                xp.setNamespaceContext(new SimpleNamespaceContext(ns));
            NodeList nl = (NodeList) xp.evaluate(expr, contextNode, XPathConstants.NODESET);
            for (int i = 0; i < nl.getLength(); i++) results.add(wrapNode(nl.item(i)));
        } catch (XPathExpressionException e) {
            throw new RuntimeException("XPath error in findnodes('" + expr + "'): " + e.getMessage(), e);
        }
        return results;
    }

    private static RuntimeList evaluateXPath(Node contextNode, String expr,
            Map<String, String> namespaces, boolean existsOnly) {
        if (contextNode == null) {
            RuntimeList r = new RuntimeList();
            r.add(new RuntimeScalar("XML::LibXML::NodeList"));
            return r;
        }
        XPath xp = XPATH_FACTORY.newXPath();
        Map<String, String> ns = namespaces != null ? namespaces : collectDocumentNamespaces(contextNode);
        if (!ns.isEmpty())
            xp.setNamespaceContext(new SimpleNamespaceContext(ns));

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
        } catch (XPathExpressionException ignored) {}

        // Try NUMBER — catches numeric literals and math expressions
        try {
            Double num = (Double) xp.evaluate(expr, contextNode, XPathConstants.NUMBER);
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
        } catch (XPathExpressionException ignored2) {}

        // Try STRING
        try {
            String str = (String) xp.evaluate(expr, contextNode, XPathConstants.STRING);
            if (str != null && !str.isEmpty()) {
                if (existsOnly) return scalarTrue.getList();
                RuntimeList r = new RuntimeList();
                r.add(new RuntimeScalar("XML::LibXML::Literal"));
                r.add(new RuntimeScalar(str));
                return r;
            }
        } catch (XPathExpressionException ignored) {}

        // Try BOOLEAN
        try {
            Boolean bool = (Boolean) xp.evaluate(expr, contextNode, XPathConstants.BOOLEAN);
            if (existsOnly) return new RuntimeScalar(bool ? 1 : 0).getList();
            RuntimeList r = new RuntimeList();
            r.add(new RuntimeScalar("XML::LibXML::Boolean"));
            r.add(new RuntimeScalar(bool ? 1 : 0));
            return r;
        } catch (XPathExpressionException ignored) {}

        // Fallback: empty NodeList (expression returned no nodes, no string, no bool)
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

