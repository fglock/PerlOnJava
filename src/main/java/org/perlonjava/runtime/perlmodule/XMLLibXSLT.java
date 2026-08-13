package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;

/** Java XS provider for the core XML::LibXSLT transformation surface. */
public class XMLLibXSLT extends PerlModuleBase {
    public static final String XS_VERSION = "2.003000";
    private static final String STATE_KEY = "_xslt_state";
    private static final String RESULT_KEY = "_xslt_result";

    private record StylesheetState(Templates templates) {}
    private record TextResult(String value) {}

    public XMLLibXSLT() {
        super("XML::LibXSLT", false);
    }

    public static void initialize() {
        XMLLibXSLT module = new XMLLibXSLT();
        try {
            module.registerMethod("INIT_THREAD_SUPPORT", "noop", null);
            module.registerMethod("HAVE_EXSLT", "falseValue", null);
            module.registerMethod("LIBXSLT_DOTTED_VERSION", "dottedVersion", null);
            module.registerMethod("LIBXSLT_VERSION", "numericVersion", null);
            module.registerMethod("LIBXSLT_RUNTIME_VERSION", "numericVersion", null);
            module.registerMethod("_parse_stylesheet", "parseStylesheet", null);
            module.registerMethod("_parse_stylesheet_file", "parseStylesheetFile", null);
            module.registerMethodInPackage("XML::LibXSLT::Stylesheet", "transform", "transform");
            module.registerMethodInPackage("XML::LibXSLT::Stylesheet", "_output_string", "outputString");
            module.registerMethodInPackage("XML::LibXSLT::Stylesheet", "output_method", "outputMethod");
            module.registerMethodInPackage("XML::LibXSLT::Stylesheet", "output_encoding", "outputEncoding");
            module.registerMethodInPackage("XML::LibXSLT::Stylesheet", "media_type", "mediaType");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeScalar blessedState(String key, Object state, String packageName) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(key, new RuntimeScalar(state));
        return ReferenceOperators.bless(
                hash.createReferenceWithTrackedElements(), new RuntimeScalar(packageName));
    }

    private static Object state(RuntimeScalar self, String key) {
        RuntimeScalar value = self.hashDerefRaw().get(key);
        if (value == null || value.value == null) {
            throw new RuntimeException("Invalid XML::LibXSLT object");
        }
        return value.value;
    }

    public static RuntimeList noop(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList falseValue(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList dottedVersion(RuntimeArray args, int ctx) {
        return new RuntimeScalar("JAXP").getList();
    }

    public static RuntimeList numericVersion(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList parseStylesheet(RuntimeArray args, int ctx) {
        try {
            Node node = XMLLibXML.getNode(args.get(1).scalar());
            Templates templates = TransformerFactory.newInstance().newTemplates(new DOMSource(node));
            return blessedState(STATE_KEY, new StylesheetState(templates),
                    "XML::LibXSLT::Stylesheet").getList();
        } catch (Exception e) {
            throw new RuntimeException("XML::LibXSLT stylesheet parse failed: " + e.getMessage(), e);
        }
    }

    public static RuntimeList parseStylesheetFile(RuntimeArray args, int ctx) {
        try {
            Templates templates = TransformerFactory.newInstance()
                    .newTemplates(new StreamSource(args.get(1).toString()));
            return blessedState(STATE_KEY, new StylesheetState(templates),
                    "XML::LibXSLT::Stylesheet").getList();
        } catch (Exception e) {
            throw new RuntimeException("XML::LibXSLT stylesheet parse failed: " + e.getMessage(), e);
        }
    }

    private static Transformer transformer(RuntimeArray args) throws Exception {
        StylesheetState stylesheet = (StylesheetState) state(args.get(0).scalar(), STATE_KEY);
        Transformer transformer = stylesheet.templates().newTransformer();
        for (int i = 2; i + 1 < args.size(); i += 2) {
            String value = args.get(i + 1).toString();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            transformer.setParameter(args.get(i).toString(), value);
        }
        return transformer;
    }

    public static RuntimeList transform(RuntimeArray args, int ctx) {
        try {
            Transformer transformer = transformer(args);
            Node input = XMLLibXML.getNode(args.get(1).scalar());
            String method = transformer.getOutputProperty(OutputKeys.METHOD);
            if ("text".equalsIgnoreCase(method)) {
                StringWriter writer = new StringWriter();
                transformer.transform(new DOMSource(input), new StreamResult(writer));
                return blessedState(RESULT_KEY, new TextResult(writer.toString()),
                        "XML::LibXSLT::Result").getList();
            }
            DOMResult result = new DOMResult();
            transformer.transform(new DOMSource(input), result);
            Node output = result.getNode();
            if (output instanceof Document document) return XMLLibXML.wrapNode(document).getList();
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            document.appendChild(document.importNode(output, true));
            return XMLLibXML.wrapNode(document).getList();
        } catch (Exception e) {
            throw new RuntimeException("XML::LibXSLT transform failed: " + e.getMessage(), e);
        }
    }

    public static RuntimeList outputString(RuntimeArray args, int ctx) {
        try {
            RuntimeScalar result = args.get(1).scalar();
            try {
                TextResult text = (TextResult) state(result, RESULT_KEY);
                return new RuntimeScalar(text.value()).getList();
            } catch (Exception ignored) {
                StringWriter writer = new StringWriter();
                StylesheetState stylesheet = (StylesheetState) state(args.get(0).scalar(), STATE_KEY);
                Transformer serializer = stylesheet.templates().newTransformer();
                serializer.transform(new DOMSource(XMLLibXML.getNode(result)), new StreamResult(writer));
                return new RuntimeScalar(writer.toString()).getList();
            }
        } catch (Exception e) {
            throw new RuntimeException("XML::LibXSLT output failed: " + e.getMessage(), e);
        }
    }

    private static String outputProperty(RuntimeScalar self, String name) {
        try {
            StylesheetState stylesheet = (StylesheetState) state(self, STATE_KEY);
            String value = stylesheet.templates().getOutputProperties().getProperty(name);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    public static RuntimeList outputMethod(RuntimeArray args, int ctx) {
        return new RuntimeScalar(outputProperty(args.get(0).scalar(), OutputKeys.METHOD)).getList();
    }

    public static RuntimeList outputEncoding(RuntimeArray args, int ctx) {
        return new RuntimeScalar(outputProperty(args.get(0).scalar(), OutputKeys.ENCODING)).getList();
    }

    public static RuntimeList mediaType(RuntimeArray args, int ctx) {
        return new RuntimeScalar(outputProperty(args.get(0).scalar(), OutputKeys.MEDIA_TYPE)).getList();
    }
}
