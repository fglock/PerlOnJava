package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.Readline;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java XS implementation of XML::Parser::Expat.
 * <p>
 * Provides the XS functions called by the Perl Expat.pm shim.
 * Uses JDK's built-in SAX parser (javax.xml.parsers.SAXParser) as the backend.
 */
public class XMLParserExpat extends PerlModuleBase {

    public static final String XS_VERSION = "2.56";

    // Namespace separator character (same as expat's NSDELIM = 0xFC)
    private static final char NS_SEP = '\u00FC';

    // Keys for storing Java objects in the Perl hash
    private static final String PARSER_KEY = "_xml_parser_state";

    public XMLParserExpat() {
        super("XML::Parser::Expat", false);
    }

    public static void initialize() {
        XMLParserExpat module = new XMLParserExpat();

        try {
            // Core parser lifecycle
            module.registerMethod("ParserCreate", null);
            module.registerMethod("ParserRelease", null);
            module.registerMethod("ParserFree", null);

            // Parsing methods
            module.registerMethod("ParseString", null);
            module.registerMethod("ParseStream", null);
            module.registerMethod("ParsePartial", null);
            module.registerMethod("ParseDone", null);

            // Handler setters - each returns the old handler
            module.registerMethod("SetStartElementHandler", null);
            module.registerMethod("SetEndElementHandler", null);
            module.registerMethod("SetCharacterDataHandler", null);
            module.registerMethod("SetProcessingInstructionHandler", null);
            module.registerMethod("SetCommentHandler", null);
            module.registerMethod("SetStartCdataHandler", null);
            module.registerMethod("SetEndCdataHandler", null);
            module.registerMethod("SetDefaultHandler", null);
            module.registerMethod("SetUnparsedEntityDeclHandler", null);
            module.registerMethod("SetNotationDeclHandler", null);
            module.registerMethod("SetExternalEntityRefHandler", null);
            module.registerMethod("SetExtEntFinishHandler", null);
            module.registerMethod("SetEntityDeclHandler", null);
            module.registerMethod("SetElementDeclHandler", null);
            module.registerMethod("SetAttListDeclHandler", null);
            module.registerMethod("SetDoctypeHandler", null);
            module.registerMethod("SetEndDoctypeHandler", null);
            module.registerMethod("SetXMLDeclHandler", null);

            // Position/info methods
            module.registerMethod("GetCurrentLineNumber", null);
            module.registerMethod("GetCurrentColumnNumber", null);
            module.registerMethod("GetCurrentByteIndex", null);
            module.registerMethod("GetCurrentByteCount", null);
            module.registerMethod("GetSpecifiedAttributeCount", null);
            module.registerMethod("ElementIndex", null);

            // Base URI
            module.registerMethod("SetBase", null);
            module.registerMethod("GetBase", null);

            // String access
            module.registerMethod("RecognizedString", null);
            module.registerMethod("OriginalString", null);
            module.registerMethod("DefaultCurrent", null);

            // Context/position
            module.registerMethod("PositionContext", null);
            module.registerMethod("UnsetAllHandlers", null);
            module.registerMethod("SkipUntil", null);

            // Encoding
            module.registerMethod("LoadEncoding", null);
            module.registerMethod("FreeEncoding", null);

            // Version info
            module.registerMethod("ExpatVersion", null);
            module.registerMethod("ExpatVersionInfo", null);

            // Error
            module.registerMethod("ErrorString", null);

            // Namespace helper
            module.registerMethod("GenerateNSName", null);

            // Security stubs
            module.registerMethod("SetBillionLaughsAttackProtectionMaximumAmplification", null);
            module.registerMethod("SetBillionLaughsAttackProtectionActivationThreshold", null);
            module.registerMethod("SetAllocTrackerMaximumAmplification", null);
            module.registerMethod("SetAllocTrackerActivationThreshold", null);
            module.registerMethod("SetReparseDeferralEnabled", null);

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing XMLParserExpat method: " + e.getMessage());
        }
    }

    // ================================================================
    // Internal parser state stored as a Java object in the Perl hash
    // ================================================================

    static class ParserState {
        // Handler coderefs stored as RuntimeScalar
        RuntimeScalar startHandler;
        RuntimeScalar endHandler;
        RuntimeScalar charHandler;
        RuntimeScalar procHandler;
        RuntimeScalar commentHandler;
        RuntimeScalar startCdataHandler;
        RuntimeScalar endCdataHandler;
        RuntimeScalar defaultHandler;
        RuntimeScalar unparsedHandler;
        RuntimeScalar notationHandler;
        RuntimeScalar externEntHandler;
        RuntimeScalar externEntFinHandler;
        RuntimeScalar entityDeclHandler;
        RuntimeScalar elementDeclHandler;
        RuntimeScalar attlistDeclHandler;
        RuntimeScalar doctypeHandler;
        RuntimeScalar endDoctypeHandler;
        RuntimeScalar xmlDeclHandler;

        // The Perl self object (Expat hash ref)
        RuntimeScalar selfRef;

        // Position tracking
        int currentLine = 0;
        int currentColumn = 0;
        long currentByteIndex = -1;
        int currentByteCount = 0;
        int specifiedAttributeCount = 0;
        int elementIndex = 0;
        int elementIndexCounter = 0; // monotonically increasing counter
        java.util.Deque<Integer> elementIndexStack = new java.util.ArrayDeque<>();

        // Base URI
        String base;

        // Last recognized/original string for reconstructing
        String recognizedString = "";
        String originalString = "";

        // Entity expansion tracking for original_string
        String currentEntityName = null;

        // Track if the current element was self-closing
        boolean lastWasSelfClosing = false;

        // Skip until element index
        int skipUntilIndex = -1;

        // Partial parsing state
        StringBuilder partialBuffer;
        boolean partialMode = false;
        boolean partialIsByteString = false;

        // Namespace mode
        boolean namespaces = false;

        // NoExpand mode
        boolean noExpand = false;

        // Error message
        String errorMessage = "";

        // SAX Locator for position tracking
        Locator locator;

        // Byte tracking - tracks byte offsets based on input
        long bytesProcessed = 0;

        // The raw input bytes for byte position tracking
        byte[] inputBytes;
        int inputScanPos = 0;  // how far we've scanned

        // Base URI from InputSource for un-resolving SAX systemIds
        String parseBaseUri;

        // Protocol encoding (e.g. "ISO-8859-1") from ParserCreate
        String protocolEncoding;
    }

    // ================================================================
    // Parser lifecycle
    // ================================================================

    /**
     * ParserCreate(self_sv, enc_sv, namespaces) - Create parser state
     * Called from Expat.pm: $args{Parser} = ParserCreate($self, $enc, $ns)
     */
    public static RuntimeList ParserCreate(RuntimeArray args, int ctx) {
        RuntimeScalar selfRef = args.get(0);
        String encoding = args.size() > 1 ? args.get(1).toString() : null;
        boolean namespaces = args.size() > 2 && args.get(2).getBoolean();

        ParserState state = new ParserState();
        state.selfRef = selfRef;
        state.namespaces = namespaces;
        state.protocolEncoding = (encoding != null && !encoding.isEmpty()) ? encoding : null;

        // Store the state as a Java object in the Perl hash
        RuntimeScalar stateScalar = new RuntimeScalar(state);
        return stateScalar.getList();
    }

    /**
     * ParserRelease(parser) - Break circular references
     */
    public static RuntimeList ParserRelease(RuntimeArray args, int ctx) {
        // No-op on JVM - GC handles circular refs
        return scalarUndef.getList();
    }

    /**
     * ParserFree(parser) - Free parser resources
     */
    public static RuntimeList ParserFree(RuntimeArray args, int ctx) {
        // No-op on JVM
        return scalarUndef.getList();
    }

    // ================================================================
    // Helper to get ParserState from the opaque parser handle
    // ================================================================

    private static ParserState getState(RuntimeScalar parser) {
        if (parser != null && parser.type == RuntimeScalarType.JAVAOBJECT
                && parser.value instanceof ParserState) {
            return (ParserState) parser.value;
        }
        throw new PerlCompilerException("Invalid parser object");
    }

    // ================================================================
    // Handler setter methods - each returns the old handler
    // ================================================================

    private static RuntimeScalar setHandler(RuntimeScalar parser, RuntimeScalar newHandler,
                                            java.util.function.Function<ParserState, RuntimeScalar> getter,
                                            java.util.function.BiConsumer<ParserState, RuntimeScalar> setter) {
        ParserState state = getState(parser);
        RuntimeScalar old = getter.apply(state);
        if (old == null) old = scalarUndef;

        if (newHandler != null && newHandler.type != RuntimeScalarType.UNDEF
                && newHandler.getBoolean()) {
            setter.accept(state, newHandler);
        } else {
            setter.accept(state, null);
        }
        return old;
    }

    public static RuntimeList SetStartElementHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.startHandler, (s, h) -> s.startHandler = h).getList();
    }

    public static RuntimeList SetEndElementHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.endHandler, (s, h) -> s.endHandler = h).getList();
    }

    public static RuntimeList SetCharacterDataHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.charHandler, (s, h) -> s.charHandler = h).getList();
    }

    public static RuntimeList SetProcessingInstructionHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.procHandler, (s, h) -> s.procHandler = h).getList();
    }

    public static RuntimeList SetCommentHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.commentHandler, (s, h) -> s.commentHandler = h).getList();
    }

    public static RuntimeList SetStartCdataHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.startCdataHandler, (s, h) -> s.startCdataHandler = h).getList();
    }

    public static RuntimeList SetEndCdataHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.endCdataHandler, (s, h) -> s.endCdataHandler = h).getList();
    }

    public static RuntimeList SetDefaultHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.defaultHandler, (s, h) -> s.defaultHandler = h).getList();
    }

    public static RuntimeList SetUnparsedEntityDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.unparsedHandler, (s, h) -> s.unparsedHandler = h).getList();
    }

    public static RuntimeList SetNotationDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.notationHandler, (s, h) -> s.notationHandler = h).getList();
    }

    public static RuntimeList SetExternalEntityRefHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.externEntHandler, (s, h) -> s.externEntHandler = h).getList();
    }

    public static RuntimeList SetExtEntFinishHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.externEntFinHandler, (s, h) -> s.externEntFinHandler = h).getList();
    }

    public static RuntimeList SetEntityDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.entityDeclHandler, (s, h) -> s.entityDeclHandler = h).getList();
    }

    public static RuntimeList SetElementDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.elementDeclHandler, (s, h) -> s.elementDeclHandler = h).getList();
    }

    public static RuntimeList SetAttListDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.attlistDeclHandler, (s, h) -> s.attlistDeclHandler = h).getList();
    }

    public static RuntimeList SetDoctypeHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.doctypeHandler, (s, h) -> s.doctypeHandler = h).getList();
    }

    public static RuntimeList SetEndDoctypeHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.endDoctypeHandler, (s, h) -> s.endDoctypeHandler = h).getList();
    }

    public static RuntimeList SetXMLDeclHandler(RuntimeArray args, int ctx) {
        return setHandler(args.get(0), args.size() > 1 ? args.get(1) : null,
                s -> s.xmlDeclHandler, (s, h) -> s.xmlDeclHandler = h).getList();
    }

    // ================================================================
    // Position / info methods
    // ================================================================

    public static RuntimeList GetCurrentLineNumber(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        if (state.locator != null) {
            return new RuntimeScalar(state.locator.getLineNumber()).getList();
        }
        return new RuntimeScalar(state.currentLine).getList();
    }

    public static RuntimeList GetCurrentColumnNumber(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        if (state.locator != null) {
            // SAX locator returns 1-based column AFTER the current token.
            // Expat returns 0-based column at the START of the current token.
            // Convert: (1-based position after) - 1 - tokenLength = 0-based start position
            int col = state.locator.getColumnNumber() - 1;
            if (state.recognizedString != null) {
                col -= state.recognizedString.length();
            }
            if (col < 0) col = 0;
            return new RuntimeScalar(col).getList();
        }
        return new RuntimeScalar(state.currentColumn).getList();
    }

    public static RuntimeList GetCurrentByteIndex(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.currentByteIndex).getList();
    }

    public static RuntimeList GetCurrentByteCount(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.currentByteCount).getList();
    }

    public static RuntimeList GetSpecifiedAttributeCount(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.specifiedAttributeCount).getList();
    }

    public static RuntimeList ElementIndex(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.elementIndex).getList();
    }

    // ================================================================
    // Base URI
    // ================================================================

    public static RuntimeList SetBase(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        if (args.size() > 1) {
            RuntimeScalar val = args.get(1);
            if (val.type == RuntimeScalarType.UNDEF) {
                state.base = null;
            } else {
                state.base = val.toString();
            }
        }
        return scalarUndef.getList();
    }

    public static RuntimeList GetBase(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        if (state.base != null) {
            return new RuntimeScalar(state.base).getList();
        }
        return scalarUndef.getList();
    }

    // ================================================================
    // String access methods
    // ================================================================

    public static RuntimeList RecognizedString(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.recognizedString).getList();
    }

    public static RuntimeList OriginalString(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        return new RuntimeScalar(state.originalString).getList();
    }

    public static RuntimeList DefaultCurrent(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        // Fire the default handler with the current recognized string
        if (state.defaultHandler != null && !state.recognizedString.isEmpty()) {
            fireCallback(state, state.defaultHandler, new RuntimeScalar(state.recognizedString));
        }
        return scalarUndef.getList();
    }

    public static RuntimeList PositionContext(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        int numLines = args.size() > 1 ? args.get(1).getInt() : 0;

        if (state.inputBytes == null || state.locator == null) {
            RuntimeArray result = new RuntimeArray();
            RuntimeArray.push(result, scalarUndef);
            RuntimeArray.push(result, scalarZero);
            return result.getList();
        }

        String input = new String(state.inputBytes, StandardCharsets.UTF_8);
        int currentLine = state.locator.getLineNumber(); // 1-based

        // Split input into lines
        String[] lines = input.split("\n", -1);
        int totalLines = lines.length;

        // Clamp to valid range
        int lineIdx = Math.max(0, Math.min(currentLine - 1, totalLines - 1));

        // Calculate range of lines to show
        int startLine = Math.max(0, lineIdx - numLines);
        int endLine = Math.min(totalLines - 1, lineIdx + numLines);

        // Build the context string and track where the current line ends
        StringBuilder sb = new StringBuilder();
        int linepos = 0;
        for (int i = startLine; i <= endLine; i++) {
            sb.append(lines[i]);
            if (i < endLine) {
                sb.append("\n");
            }
            if (i == lineIdx) {
                // linepos = position AFTER the current line (including \n)
                // This is where Expat.pm inserts the "===^" pointer
                linepos = sb.length();
            }
        }

        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar(sb.toString()));
        RuntimeArray.push(result, new RuntimeScalar(linepos));
        return result.getList();
    }

    // ================================================================
    // Handler control
    // ================================================================

    public static RuntimeList UnsetAllHandlers(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        state.startHandler = null;
        state.endHandler = null;
        state.charHandler = null;
        state.procHandler = null;
        state.commentHandler = null;
        state.startCdataHandler = null;
        state.endCdataHandler = null;
        state.defaultHandler = null;
        state.unparsedHandler = null;
        state.notationHandler = null;
        state.externEntHandler = null;
        state.externEntFinHandler = null;
        state.entityDeclHandler = null;
        state.elementDeclHandler = null;
        state.attlistDeclHandler = null;
        state.doctypeHandler = null;
        state.endDoctypeHandler = null;
        state.xmlDeclHandler = null;
        return scalarUndef.getList();
    }

    public static RuntimeList SkipUntil(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        if (args.size() > 1) {
            state.skipUntilIndex = args.get(1).getInt();
        }
        return scalarUndef.getList();
    }

    // ================================================================
    // Encoding stubs (Java handles encodings natively)
    // ================================================================

    public static RuntimeList LoadEncoding(RuntimeArray args, int ctx) {
        // No-op: Java handles encodings via java.nio.charset
        return scalarUndef.getList();
    }

    public static RuntimeList FreeEncoding(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    // ================================================================
    // Version info - emulate expat version format
    // ================================================================

    public static RuntimeList ExpatVersion(RuntimeArray args, int ctx) {
        return new RuntimeScalar("expat_2.6.4").getList();
    }

    public static RuntimeList ExpatVersionInfo(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar("major"));
        RuntimeArray.push(result, new RuntimeScalar(2));
        RuntimeArray.push(result, new RuntimeScalar("minor"));
        RuntimeArray.push(result, new RuntimeScalar(6));
        RuntimeArray.push(result, new RuntimeScalar("micro"));
        RuntimeArray.push(result, new RuntimeScalar(4));
        return result.getList();
    }

    // ================================================================
    // ErrorString - map error codes to descriptions
    // ================================================================

    private static final String[] ERROR_STRINGS = {
        "",                                     // 0 - XML_ERROR_NONE
        "out of memory",                        // 1 - XML_ERROR_NO_MEMORY
        "syntax error",                         // 2 - XML_ERROR_SYNTAX
        "no element found",                     // 3 - XML_ERROR_NO_ELEMENTS
        "not well-formed (invalid token)",      // 4 - XML_ERROR_INVALID_TOKEN
        "unclosed token",                       // 5 - XML_ERROR_UNCLOSED_TOKEN
        "partial character",                    // 6 - XML_ERROR_PARTIAL_CHAR
        "mismatched tag",                       // 7 - XML_ERROR_TAG_MISMATCH
        "duplicate attribute",                  // 8 - XML_ERROR_DUPLICATE_ATTRIBUTE
        "junk after document element",          // 9 - XML_ERROR_JUNK_AFTER_DOC_ELEMENT
        "illegal parameter entity reference",   // 10 - XML_ERROR_PARAM_ENTITY_REF
        "undefined entity",                     // 11 - XML_ERROR_UNDEFINED_ENTITY
        "recursive entity reference",           // 12 - XML_ERROR_RECURSIVE_ENTITY_REF
        "asynchronous entity",                  // 13 - XML_ERROR_ASYNC_ENTITY
        "reference to invalid character number",// 14 - XML_ERROR_BAD_CHAR_REF
        "reference to binary entity",           // 15 - XML_ERROR_BINARY_ENTITY_REF
        "reference to external entity in attribute", // 16
        "XML or text declaration not at start of entity", // 17
        "unknown encoding",                     // 18
        "encoding specified in XML declaration is incorrect", // 19
        "unclosed CDATA section",               // 20
        "error in processing external entity reference", // 21
        "not standalone",                       // 22
    };

    public static RuntimeList ErrorString(RuntimeArray args, int ctx) {
        if (args.size() > 0) {
            int code = args.get(0).getInt();
            if (code >= 0 && code < ERROR_STRINGS.length) {
                return new RuntimeScalar(ERROR_STRINGS[code]).getList();
            }
            return new RuntimeScalar("unknown error code " + code).getList();
        }
        return scalarUndef.getList();
    }

    // ================================================================
    // Namespace helper
    // ================================================================

    /**
     * GenerateNSName(name, namespace, table, list)
     * Creates a dualvar: string value = localname, integer value = namespace index.
     * This matches expat's behavior where int($name) gives the namespace index
     * and "$name" gives the local name.
     */
    public static RuntimeList GenerateNSName(RuntimeArray args, int ctx) {
        if (args.size() < 4) return args.get(0).getList();

        String name = args.get(0).toString();
        String ns = args.get(1).toString();
        RuntimeHash table = args.get(2).hashDeref();
        RuntimeArray list = args.get(3).arrayDeref();

        RuntimeScalar nsName = generateNSNameInternal(name, ns, table, list);
        return nsName.getList();
    }

    /**
     * Internal helper to generate namespace-qualified name as a dualvar.
     * Returns a dualvar: string value = localname, integer value = namespace index.
     * This replicates expat's gen_ns_name() which creates a dual PV/IV scalar.
     */
    private static RuntimeScalar generateNSNameInternal(String name, String ns,
                                                  RuntimeHash table, RuntimeArray list) {
        RuntimeScalar existing = table.get(ns);
        int nsIndex;
        if (existing == null || existing.type == RuntimeScalarType.UNDEF) {
            nsIndex = list.size();
            RuntimeArray.push(list, new RuntimeScalar(ns));
            table.put(ns, new RuntimeScalar(nsIndex));
        } else {
            nsIndex = existing.getInt();
        }
        // Create a dualvar: int = nsIndex, string = localname
        RuntimeScalar dualvar = new RuntimeScalar();
        dualvar.type = RuntimeScalarType.DUALVAR;
        dualvar.value = new DualVar(new RuntimeScalar(nsIndex), new RuntimeScalar(name));
        return dualvar;
    }

    // ================================================================
    // Security API stubs - return 1 to indicate success
    // ================================================================

    public static RuntimeList SetBillionLaughsAttackProtectionMaximumAmplification(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList SetBillionLaughsAttackProtectionActivationThreshold(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList SetAllocTrackerMaximumAmplification(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList SetAllocTrackerActivationThreshold(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList SetReparseDeferralEnabled(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    // ================================================================
    // Core parsing methods
    // ================================================================

    /**
     * ParseString(parser, string) - Parse a complete XML string
     */
    public static RuntimeList ParseString(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        RuntimeScalar xmlArg = args.get(1);
        String xmlString = xmlArg.toString();

        try {
            // Use ISO_8859_1 for BYTE_STRING to avoid double-encoding UTF-8 bytes.
            // BYTE_STRING chars are raw byte values (0-255); ISO_8859_1 preserves them as-is.
            // STRING (UTF-8 flagged) uses UTF_8 encoding as normal.
            byte[] xmlBytes = (xmlArg.type == RuntimeScalarType.BYTE_STRING)
                    ? xmlString.getBytes(StandardCharsets.ISO_8859_1)
                    : xmlString.getBytes(StandardCharsets.UTF_8);
            xmlBytes = convertEncoding(xmlBytes);
            state.bytesProcessed = 0;
            state.inputBytes = xmlBytes;
            state.inputScanPos = 0;
            doParse(state, new ByteArrayInputStream(xmlBytes));
            return scalarTrue.getList();
        } catch (PerlDieException e) {
            throw e;
        } catch (Exception e) {
            state.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
            // Set error in Perl's ErrorMessage field
            RuntimeHash selfHash = state.selfRef.hashDeref();
            selfHash.put("ErrorMessage", new RuntimeScalar(state.errorMessage));
            throw new PerlDieException(new RuntimeScalar(formatError(state, e)));
        }
    }

    /**
     * ParseStream(parser, ioref, delim) - Parse from IO handle
     */
    public static RuntimeList ParseStream(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        RuntimeScalar ioref = args.get(1);
        String delim = args.size() > 2 ? args.get(2).toString() : null;

        try {
            // Read the IO handle into a byte array
            RuntimeIO fh = RuntimeIO.getRuntimeIO(ioref);
            if (fh == null) {
                throw new PerlCompilerException("Not a filehandle");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            if (delim != null && !delim.isEmpty()) {
                // The Perl shim (Expat.pm) sets $/ to "\n$delim\n" before calling
                // ParseStream. So readline() will read everything up to (and including)
                // the delimiter, leaving the filehandle positioned right after it.
                RuntimeScalar record = Readline.readline(fh);
                if (record.type != RuntimeScalarType.UNDEF) {
                    String recordStr = record.toString();
                    // Strip the trailing "\n$delim\n" if present
                    String suffix = "\n" + delim + "\n";
                    if (recordStr.endsWith(suffix)) {
                        recordStr = recordStr.substring(0, recordStr.length() - suffix.length());
                    }
                    java.nio.charset.Charset cs = (record.type == RuntimeScalarType.BYTE_STRING)
                            ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
                    baos.write(recordStr.getBytes(cs));
                }
            } else {
                // No delimiter - read entire stream in chunks
                byte[] buffer = new byte[8192];
                while (true) {
                    RuntimeScalar result = fh.ioHandle.read(buffer.length);
                    if (result.type == RuntimeScalarType.UNDEF) {
                        break;
                    }
                    String chunk = result.toString();
                    if (chunk.isEmpty()) {
                        break;
                    }
                    java.nio.charset.Charset cs = (result.type == RuntimeScalarType.BYTE_STRING)
                            ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
                    baos.write(chunk.getBytes(cs));
                }
            }

            byte[] xmlBytes = baos.toByteArray();
            xmlBytes = convertEncoding(xmlBytes);
            state.bytesProcessed = 0;
            state.inputBytes = xmlBytes;
            state.inputScanPos = 0;
            doParse(state, new ByteArrayInputStream(xmlBytes));
            return scalarTrue.getList();
        } catch (PerlDieException e) {
            throw e;
        } catch (Exception e) {
            state.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
            RuntimeHash selfHash = state.selfRef.hashDeref();
            selfHash.put("ErrorMessage", new RuntimeScalar(state.errorMessage));
            throw new PerlDieException(new RuntimeScalar(formatError(state, e)));
        }
    }

    /**
     * ParsePartial(parser, string) - Feed a chunk for non-blocking parsing
     */
    public static RuntimeList ParsePartial(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));
        RuntimeScalar chunkArg = args.get(1);
        String chunk = chunkArg.toString();

        if (state.partialBuffer == null) {
            state.partialBuffer = new StringBuilder();
        }
        state.partialBuffer.append(chunk);
        state.partialMode = true;
        // Track if any chunk is BYTE_STRING for correct encoding in ParseDone
        if (chunkArg.type == RuntimeScalarType.BYTE_STRING) {
            state.partialIsByteString = true;
        }

        return scalarTrue.getList();
    }

    /**
     * ParseDone(parser) - Signal end of non-blocking parse
     */
    public static RuntimeList ParseDone(RuntimeArray args, int ctx) {
        ParserState state = getState(args.get(0));

        if (state.partialBuffer == null) {
            return scalarTrue.getList();
        }

        try {
            String xml = state.partialBuffer.toString();
            state.partialBuffer = null;
            state.partialMode = false;
            // Use ISO_8859_1 if any chunk was BYTE_STRING to avoid double-encoding
            byte[] xmlBytes = state.partialIsByteString
                    ? xml.getBytes(StandardCharsets.ISO_8859_1)
                    : xml.getBytes(StandardCharsets.UTF_8);
            xmlBytes = convertEncoding(xmlBytes);
            state.partialIsByteString = false;
            state.bytesProcessed = 0;
            state.inputBytes = xmlBytes;
            state.inputScanPos = 0;
            doParse(state, new ByteArrayInputStream(xmlBytes));
            return scalarTrue.getList();
        } catch (PerlDieException e) {
            throw e;
        } catch (Exception e) {
            state.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
            RuntimeHash selfHash = state.selfRef.hashDeref();
            selfHash.put("ErrorMessage", new RuntimeScalar(state.errorMessage));
            throw new PerlDieException(new RuntimeScalar(formatError(state, e)));
        }
    }

    // ================================================================
    // Encoding conversion utilities
    // ================================================================

    // Map of expat-specific encoding names to JDK charset names
    private static final Map<String, String> ENCODING_MAP = new HashMap<>();
    static {
        ENCODING_MAP.put("x-sjis-unicode", "Shift_JIS");
        ENCODING_MAP.put("x-euc-jp-unicode", "EUC-JP");
    }

    // Pattern to extract encoding from XML/text declarations
    private static final Pattern ENCODING_PATTERN = Pattern.compile(
            "<\\?xml[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    /**
     * Map an encoding name to a JDK-supported charset name.
     * Returns the mapped name if in ENCODING_MAP, otherwise returns the original.
     */
    private static String mapToJdkCharset(String encoding) {
        if (encoding == null) return null;
        String mapped = ENCODING_MAP.get(encoding.toLowerCase());
        return mapped != null ? mapped : encoding;
    }

    /**
     * Extract the encoding name from an XML/text declaration in raw bytes.
     * Scans the first 200 bytes (ASCII-safe) for <?xml ... encoding="..." ?>.
     */
    private static String extractDeclaredEncoding(byte[] input) {
        int len = Math.min(input.length, 200);
        String header = new String(input, 0, len, StandardCharsets.ISO_8859_1);
        Matcher m = ENCODING_PATTERN.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Convert encoding if the declared encoding is a custom name not supported by JDK.
     * Re-decodes the raw bytes using the correct charset and re-encodes as UTF-8,
     * updating the encoding declaration to match.
     * Returns original bytes if no conversion is needed.
     */
    private static byte[] convertEncoding(byte[] input) {
        String declared = extractDeclaredEncoding(input);
        if (declared == null) return input;

        String jdkCharset = ENCODING_MAP.get(declared.toLowerCase());
        if (jdkCharset == null) return input; // not a custom encoding, let SAX handle it

        try {
            // Decode with the correct charset, re-encode as UTF-8
            String content = new String(input, Charset.forName(jdkCharset));
            content = content.replaceFirst(
                    "encoding\\s*=\\s*[\"']" + Pattern.quote(declared) + "[\"']",
                    "encoding=\"UTF-8\"");
            return content.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return input; // fallback to original
        }
    }

    // ================================================================
    // SAX parsing engine
    // ================================================================

    private static void doParse(ParserState state, InputStream input) throws Exception {
        // Check if ParseParamEnt is enabled in the Perl self hash
        RuntimeHash selfHash = state.selfRef.hashDeref();
        RuntimeScalar parseParamEntSV = selfHash.get("ParseParamEnt");
        boolean parseParamEnt = (parseParamEntSV != null && parseParamEntSV.getBoolean());

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(state.namespaces);
        factory.setValidating(false);

        // Enable features for DTD handling
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", true);
        } catch (Exception ignored) {}
        // Only enable parameter entity processing when ParseParamEnt is set
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", parseParamEnt);
        } catch (Exception ignored) {}
        // Load external DTDs only when ParseParamEnt is set
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", parseParamEnt);
        } catch (Exception ignored) {}

        SAXParser saxParser = factory.newSAXParser();
        XMLReader reader = saxParser.getXMLReader();

        // Remove JDK security limits that restrict deep nesting and entity expansion
        try {
            reader.setProperty("http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit", 0);
        } catch (Exception ignored) {}
        try {
            reader.setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 0);
        } catch (Exception ignored) {}
        try {
            reader.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", 0);
        } catch (Exception ignored) {}

        ExpatSAXHandler handler = new ExpatSAXHandler(state);
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setDTDHandler(handler);

        // Set LexicalHandler for comments, CDATA, DOCTYPE
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        } catch (Exception ignored) {}

        // Set DeclHandler for entity/element/attlist declarations
        try {
            reader.setProperty("http://xml.org/sax/properties/declaration-handler", handler);
        } catch (Exception ignored) {}

        // Always set EntityResolver - when ExternEnt handler is set, it bridges
        // to Perl callbacks; otherwise, it returns empty content for unresolvable
        // entities (preventing parse errors from missing external DTDs/PEs)
        reader.setEntityResolver(handler);

        InputSource inputSource = new InputSource(input);
        // Set protocol encoding if specified, mapping custom names to JDK charsets
        if (state.protocolEncoding != null) {
            inputSource.setEncoding(mapToJdkCharset(state.protocolEncoding));
        }
        // Set systemId to the current working directory so SAX resolves relative URIs correctly.
        // This also allows unresolveSysId to strip this prefix and recover relative paths.
        String cwd = System.getProperty("user.dir");
        String baseUri = new java.io.File(cwd, "dummy").toURI().toString();
        baseUri = baseUri.substring(0, baseUri.lastIndexOf('/') + 1);
        inputSource.setSystemId(baseUri);
        // Store the base URI for un-resolution in callbacks
        state.parseBaseUri = baseUri;
        reader.parse(inputSource);
    }

    // ================================================================
    // SAX event handler that dispatches to Perl callbacks
    // ================================================================

    private static class ExpatSAXHandler extends DefaultHandler
            implements LexicalHandler, DeclHandler, EntityResolver {

        private final ParserState state;
        private boolean inCDATA = false;
        // Track if XMLDecl was detected
        private boolean xmlDeclFired = false;
        // Track if we've seen an element yet (for XMLDecl detection)
        private boolean documentStarted = false;

        ExpatSAXHandler(ParserState state) {
            this.state = state;
        }

        // ---- Locator for position tracking ----

        @Override
        public void setDocumentLocator(Locator locator) {
            state.locator = locator;
        }

        // ---- Document lifecycle ----

        @Override
        public void startDocument() throws SAXException {
            documentStarted = true;
            // Fire XMLDecl handler if set - we detect the xml declaration
            // by checking if the input starts with "<?xml"
            if (state.xmlDeclHandler != null && !xmlDeclFired) {
                xmlDeclFired = true;
                // Check if input has XML declaration
                if (state.inputBytes != null && state.inputBytes.length >= 5) {
                    String start = new String(state.inputBytes, 0,
                            Math.min(100, state.inputBytes.length), StandardCharsets.UTF_8);
                    if (start.startsWith("<?xml ") || start.startsWith("<?xml\t")) {
                        // Parse version, encoding, standalone from the declaration
                        String version = extractAttr(start, "version");
                        String encoding = extractAttr(start, "encoding");
                        String standalone = extractAttr(start, "standalone");
                        // Convert standalone from "yes"/"no" string to Perl boolean:
                        // undef if not present, 1 for "yes", "" (defined but false) for "no"
                        RuntimeScalar standaloneScalar;
                        if (standalone == null) {
                            standaloneScalar = scalarUndef;
                        } else if ("yes".equals(standalone)) {
                            standaloneScalar = new RuntimeScalar(1);
                        } else {
                            standaloneScalar = new RuntimeScalar("");
                        }
                        RuntimeArray callArgs = new RuntimeArray();
                        RuntimeArray.push(callArgs, state.selfRef);
                        RuntimeArray.push(callArgs, version != null ? new RuntimeScalar(version) : scalarUndef);
                        RuntimeArray.push(callArgs, encoding != null ? new RuntimeScalar(encoding) : scalarUndef);
                        RuntimeArray.push(callArgs, standaloneScalar);
                        try {
                            RuntimeCode.apply(state.xmlDeclHandler, callArgs, RuntimeContextType.VOID);
                        } catch (PerlDieException e) {
                            throw new SAXException(e);
                        }
                    }
                }
            }
        }

        /** Extract an attribute value from an XML declaration string */
        private static String extractAttr(String decl, String attr) {
            int pos = decl.indexOf(attr + "=");
            if (pos < 0) pos = decl.indexOf(attr + " =");
            if (pos < 0) return null;
            pos = decl.indexOf('=', pos) + 1;
            while (pos < decl.length() && decl.charAt(pos) == ' ') pos++;
            if (pos >= decl.length()) return null;
            char quote = decl.charAt(pos);
            if (quote != '"' && quote != '\'') return null;
            int end = decl.indexOf(quote, pos + 1);
            if (end < 0) return null;
            return decl.substring(pos + 1, end);
        }

        // ---- Namespace prefix mapping ----

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            if (!state.namespaces) return;

            RuntimeHash selfHash = state.selfRef.hashDeref();

            // Prefix Table: $self->{Prefix_Table}{$prefix} = [$uri_stack]
            String perlPrefix = (prefix == null || prefix.isEmpty()) ? "#default" : prefix;

            RuntimeScalar prefixTableRef = selfHash.get("Prefix_Table");
            if (prefixTableRef != null && prefixTableRef.type != RuntimeScalarType.UNDEF) {
                RuntimeHash prefixTable = prefixTableRef.hashDeref();
                RuntimeScalar stackRef = prefixTable.get(perlPrefix);
                if (stackRef != null && stackRef.type != RuntimeScalarType.UNDEF
                        && RuntimeScalarType.isReference(stackRef)) {
                    RuntimeArray stack = stackRef.arrayDeref();
                    RuntimeArray.push(stack, (uri != null) ? new RuntimeScalar(uri) : scalarUndef);
                } else {
                    RuntimeArray newStack = new RuntimeArray();
                    RuntimeArray.push(newStack, (uri != null) ? new RuntimeScalar(uri) : scalarUndef);
                    prefixTable.put(perlPrefix, newStack.createReference());
                }
            }

            // New_Prefixes: push @{$self->{New_Prefixes}}, $prefix
            RuntimeScalar newPrefRef = selfHash.get("New_Prefixes");
            if (newPrefRef != null && newPrefRef.type != RuntimeScalarType.UNDEF) {
                RuntimeArray newPrefixes = newPrefRef.arrayDeref();
                RuntimeArray.push(newPrefixes, new RuntimeScalar(perlPrefix));
            }
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            if (!state.namespaces) return;

            RuntimeHash selfHash = state.selfRef.hashDeref();
            String perlPrefix = (prefix == null || prefix.isEmpty()) ? "#default" : prefix;

            RuntimeScalar prefixTableRef = selfHash.get("Prefix_Table");
            if (prefixTableRef != null && prefixTableRef.type != RuntimeScalarType.UNDEF) {
                RuntimeHash prefixTable = prefixTableRef.hashDeref();
                RuntimeScalar stackRef = prefixTable.get(perlPrefix);
                if (stackRef != null && stackRef.type != RuntimeScalarType.UNDEF
                        && RuntimeScalarType.isReference(stackRef)) {
                    RuntimeArray stack = stackRef.arrayDeref();
                    if (stack.size() > 1) {
                        RuntimeArray.pop(stack);
                    } else {
                        prefixTable.delete(perlPrefix);
                    }
                }
            }
        }

        // ---- ContentHandler ----

        @Override
        public void startElement(String uri, String localName, String qName,
                                 org.xml.sax.Attributes attributes)
                throws SAXException {
            state.elementIndexCounter++;
            state.elementIndex = state.elementIndexCounter;
            state.elementIndexStack.push(state.elementIndex);

            // Determine element name (as RuntimeScalar, possibly dualvar for namespaces)
            RuntimeScalar elementNameScalar;
            if (state.namespaces) {
                if (uri != null && !uri.isEmpty()) {
                    elementNameScalar = generateNSNameForElement(localName, uri);
                } else {
                    String name = localName.isEmpty() ? qName : localName;
                    elementNameScalar = new RuntimeScalar(name);
                }
            } else {
                elementNameScalar = new RuntimeScalar(qName);
            }

            // Update Perl's Context array: push @{$self->{Context}}, $elementName
            RuntimeHash selfHash = state.selfRef.hashDeref();
            RuntimeScalar contextRef = selfHash.get("Context");
            if (contextRef != null && contextRef.type != RuntimeScalarType.UNDEF) {
                RuntimeArray context = contextRef.arrayDeref();
                RuntimeArray.push(context, elementNameScalar);
            }

            // Separate specified from defaulted attributes for specifiedAttributeCount
            List<Integer> specifiedIndices = new ArrayList<>();
            List<Integer> defaultedIndices = new ArrayList<>();
            if (attributes instanceof Attributes2) {
                Attributes2 attrs2 = (Attributes2) attributes;
                for (int i = 0; i < attributes.getLength(); i++) {
                    if (attrs2.isSpecified(i)) {
                        specifiedIndices.add(i);
                    } else {
                        defaultedIndices.add(i);
                    }
                }
            } else {
                for (int i = 0; i < attributes.getLength(); i++) {
                    specifiedIndices.add(i);
                }
            }
            // Track specified attribute count (number of attribute name+value pairs)
            state.specifiedAttributeCount = specifiedIndices.size() * 2;

            // Update recognized string for original_string() approximation
            StringBuilder sb = new StringBuilder("<");
            sb.append(qName);
            for (int i = 0; i < attributes.getLength(); i++) {
                sb.append(" ").append(attributes.getQName(i)).append("=\"")
                        .append(escapeXmlAttr(attributes.getValue(i))).append("\"");
            }
            // Detect self-closing tags (<foo/>) by scanning inputBytes.
            // SAX treats <foo/> and <foo></foo> identically, but for column
            // tracking we need to know the actual token length.
            boolean selfClosing = false;
            if (state.inputBytes != null && state.locator != null) {
                // Scan forward to find "<tagname" then check how the tag closes
                byte[] tagStart = ("<" + qName).getBytes(StandardCharsets.UTF_8);
                int searchFrom = state.inputScanPos;
                for (int pos = searchFrom; pos <= state.inputBytes.length - tagStart.length; pos++) {
                    // Look for "<tagname"
                    boolean match = true;
                    for (int j = 0; j < tagStart.length; j++) {
                        if (state.inputBytes[pos + j] != tagStart[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        // Found the start tag, now find its closing '>'
                        for (int endPos = pos + tagStart.length; endPos < state.inputBytes.length; endPos++) {
                            if (state.inputBytes[endPos] == '>') {
                                if (endPos > 0 && state.inputBytes[endPos - 1] == '/') {
                                    selfClosing = true;
                                }
                                state.inputScanPos = endPos + 1;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (selfClosing) {
                sb.append("/>");
                state.lastWasSelfClosing = true;
            } else {
                sb.append(">");
                state.lastWasSelfClosing = false;
            }
            state.recognizedString = sb.toString();
            state.originalString = state.recognizedString;
            updateBytePosition(state);

            // Skip if skip_until is active
            if (state.skipUntilIndex >= 0 && state.elementIndex < state.skipUntilIndex) {
                return;
            }

            if (state.startHandler != null) {
                // Build args: (expat, element, attr1, val1, attr2, val2, ...)
                // Specified attributes first, then defaulted (expat convention)
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, elementNameScalar);
                // Specified attributes first
                for (int idx : specifiedIndices) {
                    RuntimeArray.push(callArgs, makeAttrNameScalar(attributes, idx));
                    RuntimeArray.push(callArgs, new RuntimeScalar(attributes.getValue(idx)));
                }
                // Defaulted attributes after
                for (int idx : defaultedIndices) {
                    RuntimeArray.push(callArgs, makeAttrNameScalar(attributes, idx));
                    RuntimeArray.push(callArgs, new RuntimeScalar(attributes.getValue(idx)));
                }
                try {
                    RuntimeCode.apply(state.startHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.defaultHandler != null) {
                fireDefault(state, state.recognizedString);
            }

            // Clear New_Prefixes after start handler has been called
            if (state.namespaces) {
                RuntimeScalar newPrefRef = selfHash.get("New_Prefixes");
                if (newPrefRef != null && newPrefRef.type != RuntimeScalarType.UNDEF) {
                    RuntimeArray newPrefixes = newPrefRef.arrayDeref();
                    // Clear the array by setting its elements count to 0
                    while (newPrefixes.size() > 0) {
                        RuntimeArray.pop(newPrefixes);
                    }
                }
            }
        }

        /**
         * Generate a namespace-qualified name as a dualvar using $self's Namespace_Table/List
         */
        private RuntimeScalar generateNSNameForElement(String localName, String nsUri) {
            RuntimeHash selfHash = state.selfRef.hashDeref();
            RuntimeScalar nsTableRef = selfHash.get("Namespace_Table");
            RuntimeScalar nsListRef = selfHash.get("Namespace_List");
            if (nsTableRef == null || nsTableRef.type == RuntimeScalarType.UNDEF
                    || nsListRef == null || nsListRef.type == RuntimeScalarType.UNDEF) {
                return new RuntimeScalar(localName);
            }
            RuntimeHash nsTable = nsTableRef.hashDeref();
            RuntimeArray nsList = nsListRef.arrayDeref();
            return generateNSNameInternal(localName, nsUri, nsTable, nsList);
        }

        /**
         * Create a RuntimeScalar for an attribute name, handling namespace mode.
         */
        private RuntimeScalar makeAttrNameScalar(org.xml.sax.Attributes attributes, int index) {
            if (state.namespaces) {
                String attrUri = attributes.getURI(index);
                String attrLocal = attributes.getLocalName(index);
                if (attrUri != null && !attrUri.isEmpty()) {
                    return generateNSNameForElement(attrLocal, attrUri);
                } else {
                    String name = !attrLocal.isEmpty() ? attrLocal : attributes.getQName(index);
                    return new RuntimeScalar(name);
                }
            } else {
                return new RuntimeScalar(attributes.getQName(index));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            // Restore elementIndex to match the corresponding startElement
            if (!state.elementIndexStack.isEmpty()) {
                state.elementIndex = state.elementIndexStack.pop();
            }

            RuntimeScalar elementNameScalar;
            if (state.namespaces) {
                if (uri != null && !uri.isEmpty()) {
                    elementNameScalar = generateNSNameForElement(localName, uri);
                } else {
                    String name = localName.isEmpty() ? qName : localName;
                    elementNameScalar = new RuntimeScalar(name);
                }
            } else {
                elementNameScalar = new RuntimeScalar(qName);
            }

            // For self-closing tags (<foo/>), SAX fires endElement immediately after
            // startElement. For column calculation: libexpat returns column AFTER the
            // '>' for self-closing end handlers. Set recognizedString to empty so
            // GetCurrentColumnNumber doesn't subtract anything.
            if (state.lastWasSelfClosing) {
                state.recognizedString = "";
                state.originalString = "";
            } else {
                state.recognizedString = "</" + qName + ">";
                state.originalString = state.recognizedString;
            }
            // Always clear the flag after use
            state.lastWasSelfClosing = false;
            updateBytePosition(state);

            if (state.skipUntilIndex >= 0 && state.elementIndex < state.skipUntilIndex) {
                // Pop Context even when skipping
                RuntimeHash selfHash = state.selfRef.hashDeref();
                RuntimeScalar contextRef = selfHash.get("Context");
                if (contextRef != null && contextRef.type != RuntimeScalarType.UNDEF) {
                    RuntimeArray context = contextRef.arrayDeref();
                    if (context.size() > 0) {
                        RuntimeArray.pop(context);
                    }
                }
                return;
            }

            // Reset skip after matching element
            if (state.skipUntilIndex >= 0 && state.elementIndex >= state.skipUntilIndex) {
                state.skipUntilIndex = -1;
            }

            if (state.endHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, elementNameScalar);
                try {
                    RuntimeCode.apply(state.endHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.defaultHandler != null) {
                fireDefault(state, state.recognizedString);
            }

            // Pop Perl's Context array AFTER the end handler (matches libexpat behavior)
            RuntimeHash selfHash = state.selfRef.hashDeref();
            RuntimeScalar contextRef = selfHash.get("Context");
            if (contextRef != null && contextRef.type != RuntimeScalarType.UNDEF) {
                RuntimeArray context = contextRef.arrayDeref();
                if (context.size() > 0) {
                    RuntimeArray.pop(context);
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (state.skipUntilIndex >= 0) return;

            String text = new String(ch, start, length);
            state.recognizedString = text;
            // When inside an entity expansion, originalString should be the
            // unexpanded entity reference (e.g. "&draft.day;")
            if (state.currentEntityName != null) {
                state.originalString = "&" + state.currentEntityName + ";";
                state.currentEntityName = null; // consume - only first characters() gets it
            } else {
                state.originalString = text;
            }
            updateBytePosition(state);

            if (state.charHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(text));
                try {
                    RuntimeCode.apply(state.charHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.defaultHandler != null) {
                fireDefault(state, text);
            }
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            if (state.skipUntilIndex >= 0) return;

            state.recognizedString = "<?" + target + " " + data + "?>";
            state.originalString = state.recognizedString;
            updateBytePosition(state);

            if (state.procHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(target));
                RuntimeArray.push(callArgs, new RuntimeScalar(data != null ? data : ""));
                try {
                    RuntimeCode.apply(state.procHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.defaultHandler != null) {
                fireDefault(state, state.recognizedString);
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            characters(ch, start, length);
        }

        // ---- DTDHandler ----

        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId,
                                       String notationName) throws SAXException {
            if (state.unparsedHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, state.base != null ? new RuntimeScalar(state.base) : scalarUndef);
                String rawSysId = unresolveSysId(systemId, state);
                RuntimeArray.push(callArgs, new RuntimeScalar(rawSysId != null ? rawSysId : ""));
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                RuntimeArray.push(callArgs, new RuntimeScalar(notationName));
                try {
                    RuntimeCode.apply(state.unparsedHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.entityDeclHandler != null) {
                // Per Expat.pm docs: "If both [Entity and Unparsed handlers] are set,
                // then [Entity] handler will not be called for unparsed entities."
                // When only Entity handler is set, route unparsed entities through it.
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, scalarUndef); // val (undef for external entities)
                String rawSysId2 = unresolveSysId(systemId, state);
                RuntimeArray.push(callArgs, rawSysId2 != null ? new RuntimeScalar(rawSysId2) : scalarUndef);
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                RuntimeArray.push(callArgs, new RuntimeScalar(notationName)); // ndata
                RuntimeArray.push(callArgs, scalarZero); // is_param
                try {
                    RuntimeCode.apply(state.entityDeclHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void notationDecl(String name, String publicId, String systemId)
                throws SAXException {
            if (state.notationHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, state.base != null ? new RuntimeScalar(state.base) : scalarUndef);
                String rawNotSysId = unresolveSysId(systemId, state);
                RuntimeArray.push(callArgs, rawNotSysId != null ? new RuntimeScalar(rawNotSysId) : scalarUndef);
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                try {
                    RuntimeCode.apply(state.notationHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        // ---- LexicalHandler ----

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            if (state.skipUntilIndex >= 0) return;

            String text = new String(ch, start, length);
            state.recognizedString = "<!--" + text + "-->";
            state.originalString = state.recognizedString;
            updateBytePosition(state);

            if (state.commentHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(text));
                try {
                    RuntimeCode.apply(state.commentHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            } else if (state.defaultHandler != null) {
                fireDefault(state, state.recognizedString);
            }
        }

        @Override
        public void startCDATA() throws SAXException {
            inCDATA = true;
            if (state.skipUntilIndex >= 0) return;

            if (state.startCdataHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                try {
                    RuntimeCode.apply(state.startCdataHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void endCDATA() throws SAXException {
            inCDATA = false;
            if (state.skipUntilIndex >= 0) return;

            if (state.endCdataHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                try {
                    RuntimeCode.apply(state.endCdataHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            if (state.doctypeHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, systemId != null ? new RuntimeScalar(systemId) : scalarUndef);
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                RuntimeArray.push(callArgs, scalarTrue); // internal subset
                try {
                    RuntimeCode.apply(state.doctypeHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void endDTD() throws SAXException {
            if (state.endDoctypeHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                try {
                    RuntimeCode.apply(state.endDoctypeHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void startEntity(String name) throws SAXException {
            // Track entity name so characters() can set originalString correctly.
            // JDK SAX fires: startEntity → endEntity → characters,
            // so we use a "pending" approach: set the name here, consume in characters().
            if (!name.startsWith("[")) { // Skip internal SAX entities like [dtd]
                state.currentEntityName = name;
            }
        }

        @Override
        public void endEntity(String name) throws SAXException {
            // Don't clear here - characters() hasn't fired yet (JDK ordering)
        }

        // ---- DeclHandler ----

        @Override
        public void internalEntityDecl(String name, String value) throws SAXException {
            if (state.entityDeclHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, new RuntimeScalar(value)); // value
                RuntimeArray.push(callArgs, scalarUndef); // sysid
                RuntimeArray.push(callArgs, scalarUndef); // pubid
                RuntimeArray.push(callArgs, scalarUndef); // notation
                RuntimeArray.push(callArgs, new RuntimeScalar(name.startsWith("%") ? 1 : 0)); // is_param
                try {
                    RuntimeCode.apply(state.entityDeclHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void externalEntityDecl(String name, String publicId, String systemId)
                throws SAXException {
            if (state.entityDeclHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, scalarUndef); // value (external entities have no inline value)
                String rawExtSysId = unresolveSysId(systemId, state);
                RuntimeArray.push(callArgs, rawExtSysId != null ? new RuntimeScalar(rawExtSysId) : scalarUndef);
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                RuntimeArray.push(callArgs, scalarUndef); // notation
                RuntimeArray.push(callArgs, new RuntimeScalar(name.startsWith("%") ? 1 : 0)); // is_param
                try {
                    RuntimeCode.apply(state.entityDeclHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        @Override
        public void elementDecl(String name, String model) throws SAXException {
            if (state.elementDeclHandler != null) {
                RuntimeScalar modelRef = parseContentModel(model);

                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(name));
                RuntimeArray.push(callArgs, modelRef);
                try {
                    RuntimeCode.apply(state.elementDeclHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        /**
         * Parse a DTD content model string into a blessed ContentModel hash.
         * Handles EMPTY, ANY, (#PCDATA), and nested (a,b|c) with quantifiers.
         */
        private RuntimeScalar parseContentModel(String model) {
            model = model.trim();
            return parseModelExpr(model, 0, model.length());
        }

        private RuntimeScalar parseModelExpr(String model, int start, int end) {
            String s = model.substring(start, end).trim();

            // EMPTY
            if (s.equals("EMPTY")) {
                return makeContentModel(1, null, null, null); // Type 1 = EMPTY
            }
            // ANY
            if (s.equals("ANY")) {
                return makeContentModel(2, null, null, null); // Type 2 = ANY
            }

            // Check for quantifier at the end
            String quant = null;
            if (s.endsWith("*") || s.endsWith("+") || s.endsWith("?")) {
                quant = s.substring(s.length() - 1);
                s = s.substring(0, s.length() - 1).trim();
            }

            // Parenthesized group
            if (s.startsWith("(") && s.endsWith(")")) {
                String inner = s.substring(1, s.length() - 1).trim();

                // (#PCDATA...) = MIXED
                if (inner.startsWith("#PCDATA")) {
                    return makeContentModel(3, null, quant, parseMixedChildren(inner));
                }

                // Find the separator: ',' for SEQ, '|' for CHOICE
                List<String> parts = splitModelGroup(inner);
                if (parts.size() == 1 && !inner.contains(",") && !inner.contains("|")) {
                    // Single child, check if it's a name with quantifier
                    return parseModelExpr(inner, 0, inner.length());
                }

                boolean isChoice = inner.contains("|") && !inner.contains(",");
                int type = isChoice ? 5 : 6; // 5=CHOICE, 6=SEQ

                List<RuntimeScalar> children = new ArrayList<>();
                for (String part : parts) {
                    children.add(parseModelExpr(part.trim(), 0, part.trim().length()));
                }
                return makeContentModel(type, null, quant, children);
            }

            // Simple NAME (possibly with quantifier)
            if (quant != null) {
                return makeContentModel(4, s, quant, null); // Type 4 = NAME
            }
            // Check for trailing quantifier on name
            if (s.endsWith("*") || s.endsWith("+") || s.endsWith("?")) {
                quant = s.substring(s.length() - 1);
                s = s.substring(0, s.length() - 1).trim();
            }
            return makeContentModel(4, s, quant, null); // Type 4 = NAME
        }

        private List<RuntimeScalar> parseMixedChildren(String inner) {
            // (#PCDATA|foo|bar) - split on | and skip #PCDATA
            List<RuntimeScalar> children = new ArrayList<>();
            String[] parts = inner.split("\\|");
            for (String part : parts) {
                part = part.trim();
                if (!part.equals("#PCDATA")) {
                    children.add(makeContentModel(4, part, null, null));
                }
            }
            return children;
        }

        /**
         * Split a model group respecting nested parentheses.
         * E.g. "(a,(b|c)),d" → ["(a,(b|c))", "d"]
         */
        private List<String> splitModelGroup(String group) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            int start = 0;
            char sep = group.contains(",") ? ',' : '|';
            for (int i = 0; i < group.length(); i++) {
                char c = group.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == sep && depth == 0) {
                    parts.add(group.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(group.substring(start));
            return parts;
        }

        private RuntimeScalar makeContentModel(int type, String tag, String quant,
                                                List<RuntimeScalar> children) {
            RuntimeHash model = new RuntimeHash();
            model.put("Type", new RuntimeScalar(type));
            model.put("Tag", tag != null ? new RuntimeScalar(tag) : scalarUndef);
            model.put("Quant", quant != null ? new RuntimeScalar(quant) : scalarUndef);
            if (children != null && !children.isEmpty()) {
                RuntimeArray childArray = new RuntimeArray();
                for (RuntimeScalar child : children) {
                    RuntimeArray.push(childArray, child);
                }
                model.put("Children", childArray.createReference());
            }
            RuntimeScalar ref = model.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("XML::Parser::ContentModel"));
            return ref;
        }

        @Override
        public void attributeDecl(String eName, String aName, String type, String mode,
                                  String value) throws SAXException {
            if (state.attlistDeclHandler != null) {
                // Fix type format: SAX reports "NOTATION (x|y|z)" with space,
                // expat reports "NOTATION(x|y|z)" without space
                String fixedType = type;
                if (fixedType != null && fixedType.startsWith("NOTATION ")) {
                    fixedType = "NOTATION" + fixedType.substring(8);
                }

                // Compute default parameter per Perl API:
                // "#REQUIRED", "#IMPLIED", or "'quoted_value'" (with quotes)
                String defaultStr;
                if ("#REQUIRED".equals(mode)) {
                    defaultStr = "#REQUIRED";
                } else if ("#IMPLIED".equals(mode)) {
                    defaultStr = "#IMPLIED";
                } else if (value != null) {
                    defaultStr = "'" + value + "'";
                } else {
                    defaultStr = null;
                }

                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, new RuntimeScalar(eName));
                RuntimeArray.push(callArgs, new RuntimeScalar(aName));
                RuntimeArray.push(callArgs, new RuntimeScalar(fixedType));
                RuntimeArray.push(callArgs, defaultStr != null ? new RuntimeScalar(defaultStr) : scalarUndef);
                RuntimeArray.push(callArgs, new RuntimeScalar("#FIXED".equals(mode) ? 1 : 0));
                try {
                    RuntimeCode.apply(state.attlistDeclHandler, callArgs, RuntimeContextType.VOID);
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                }
            }
        }

        // ---- EntityResolver ----

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            if (state.externEntHandler != null) {
                RuntimeArray callArgs = new RuntimeArray();
                RuntimeArray.push(callArgs, state.selfRef);
                RuntimeArray.push(callArgs, state.base != null ? new RuntimeScalar(state.base) : scalarUndef);
                String rawResSysId = unresolveSysId(systemId, state);
                RuntimeArray.push(callArgs, rawResSysId != null ? new RuntimeScalar(rawResSysId) : scalarUndef);
                RuntimeArray.push(callArgs, publicId != null ? new RuntimeScalar(publicId) : scalarUndef);
                try {
                    RuntimeList result = RuntimeCode.apply(state.externEntHandler, callArgs,
                            RuntimeContextType.SCALAR);
                    RuntimeScalar retVal = result.getFirst();

                    if (retVal.type == RuntimeScalarType.UNDEF) {
                        // Handler returned undef - entity could not be resolved
                        return null;
                    }

                    // Handler returned a string (entity content) or filehandle
                    if (RuntimeScalarType.isReference(retVal) || retVal.type == RuntimeScalarType.GLOB) {
                        // Filehandle - read content as bytes for proper encoding handling
                        RuntimeIO fh = RuntimeIO.getRuntimeIO(retVal);
                        if (fh != null) {
                            ByteArrayOutputStream entBaos = new ByteArrayOutputStream();
                            while (true) {
                                RuntimeScalar line = fh.ioHandle.read(8192);
                                if (line.type == RuntimeScalarType.UNDEF) break;
                                String s = line.toString();
                                if (s.isEmpty()) break;
                                java.nio.charset.Charset cs = (line.type == RuntimeScalarType.BYTE_STRING)
                                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
                                entBaos.write(s.getBytes(cs));
                            }
                            // Call ExternEntFin if set
                            if (state.externEntFinHandler != null) {
                                RuntimeArray finArgs = new RuntimeArray();
                                RuntimeArray.push(finArgs, state.selfRef);
                                RuntimeCode.apply(state.externEntFinHandler, finArgs, RuntimeContextType.VOID);
                            }
                            byte[] rawBytes = convertEncoding(entBaos.toByteArray());
                            InputSource is = new InputSource(new ByteArrayInputStream(rawBytes));
                            // Preserve systemId so SAX can resolve relative references within this entity
                            if (systemId != null) {
                                is.setSystemId(systemId);
                            }
                            return is;
                        }
                    }

                    // String content
                    String content = retVal.toString();
                    if (!content.isEmpty()) {
                        // Call ExternEntFin if set
                        if (state.externEntFinHandler != null) {
                            RuntimeArray finArgs = new RuntimeArray();
                            RuntimeArray.push(finArgs, state.selfRef);
                            RuntimeCode.apply(state.externEntFinHandler, finArgs, RuntimeContextType.VOID);
                        }
                        // Convert to bytes for encoding handling (string may contain raw byte values)
                        java.nio.charset.Charset cs = (retVal.type == RuntimeScalarType.BYTE_STRING)
                                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
                        byte[] rawBytes = convertEncoding(content.getBytes(cs));
                        InputSource is = new InputSource(new ByteArrayInputStream(rawBytes));
                        if (systemId != null) {
                            is.setSystemId(systemId);
                        }
                        return is;
                    }
                } catch (PerlDieException e) {
                    throw new SAXException(e);
                } catch (IOException e) {
                    throw new SAXException(e);
                }
            }
            // Return empty input source to avoid network access
            return new InputSource(new StringReader(""));
        }

        // ---- ErrorHandler ----

        @Override
        public void warning(SAXParseException e) throws SAXException {
            // Ignore warnings
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            state.errorMessage = formatSAXError(e);
            // Also set ErrorMessage in Perl hash for expat compatibility
            RuntimeHash selfHash = state.selfRef.hashDeref();
            selfHash.put("ErrorMessage", new RuntimeScalar(state.errorMessage));
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            state.errorMessage = formatSAXError(e);
            // Also set ErrorMessage in Perl hash for expat compatibility
            RuntimeHash selfHash = state.selfRef.hashDeref();
            selfHash.put("ErrorMessage", new RuntimeScalar(state.errorMessage));
            throw e;
        }

        private String formatSAXError(SAXParseException e) {
            return "not well-formed (invalid token) at line " + e.getLineNumber()
                    + ", column " + e.getColumnNumber();
        }
    }

    // ================================================================
    // Utility methods
    // ================================================================

    /**
     * Fire a Perl callback with the expat self + additional args
     */
    private static void fireCallback(ParserState state, RuntimeScalar handler, RuntimeScalar... extraArgs) {
        RuntimeArray callArgs = new RuntimeArray();
        RuntimeArray.push(callArgs, state.selfRef);
        for (RuntimeScalar arg : extraArgs) {
            RuntimeArray.push(callArgs, arg);
        }
        RuntimeCode.apply(handler, callArgs, RuntimeContextType.VOID);
    }

    /**
     * Fire the Default handler with a string
     */
    private static void fireDefault(ParserState state, String text) {
        if (state.defaultHandler != null) {
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, state.selfRef);
            RuntimeArray.push(callArgs, new RuntimeScalar(text));
            try {
                RuntimeCode.apply(state.defaultHandler, callArgs, RuntimeContextType.VOID);
            } catch (PerlDieException e) {
                // Wrap in SAXException if we're in a SAX context
                throw e;
            }
        }
    }

    /**
     * Update approximate byte position by accumulating byte lengths of recognized tokens.
     */
    private static void updateBytePosition(ParserState state) {
        if (state.recognizedString != null) {
            int byteLen = state.recognizedString.getBytes(StandardCharsets.UTF_8).length;
            state.currentByteIndex = state.bytesProcessed;
            state.currentByteCount = byteLen;
            state.bytesProcessed += byteLen;
        }
    }

    /**
     * Escape special characters in XML attribute values
     */
    private static String escapeXmlAttr(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;");
    }

    /**
     * Un-resolve a systemId that SAX has resolved to an absolute URI.
     * SAX resolves relative systemIds (like "logo.gif") to absolute URIs
     * (like "file:///path/to/logo.gif"), but expat passes the raw string.
     * This strips the base URI prefix to recover the original relative path.
     */
    private static String unresolveSysId(String systemId, ParserState state) {
        if (systemId == null) return null;
        // Try to strip the parse base URI that we set on the InputSource
        if (state.parseBaseUri != null && systemId.startsWith(state.parseBaseUri)) {
            return systemId.substring(state.parseBaseUri.length());
        }
        // If state has an explicit base, try to make systemId relative to it
        if (state.base != null) {
            String base = state.base;
            // Ensure base ends with /
            if (!base.endsWith("/")) {
                int lastSlash = base.lastIndexOf('/');
                if (lastSlash >= 0) {
                    base = base.substring(0, lastSlash + 1);
                }
            }
            if (systemId.startsWith(base)) {
                return systemId.substring(base.length());
            }
        }
        // Try to strip file:// + CWD prefix to recover relative or absolute file paths
        if (systemId.startsWith("file:")) {
            try {
                String cwd = System.getProperty("user.dir");
                String filePath;
                if (systemId.startsWith("file:///")) {
                    filePath = systemId.substring(7); // file:///path -> /path
                } else if (systemId.startsWith("file://")) {
                    filePath = systemId.substring(7); // file://path -> path  
                } else if (systemId.startsWith("file:/")) {
                    filePath = systemId.substring(5); // file:/path -> /path
                } else {
                    filePath = systemId.substring(5); // file:path -> path
                }
                if (cwd != null) {
                    String cwdWithSlash = cwd.endsWith("/") ? cwd : cwd + "/";
                    if (filePath.startsWith(cwdWithSlash)) {
                        return filePath.substring(cwdWithSlash.length());
                    }
                }
                return filePath;
            } catch (Exception ignored) {}
        }
        return systemId;
    }

    /**
     * Format an error with line/column info, matching expat error format.
     * SAX error messages are wrapped with "not well-formed (invalid token)"
     * prefix and a hint about common escaping issues, matching libexpat behavior.
     */
    private static String formatError(ParserState state, Exception e) {
        // Unwrap SAXException wrapping PerlDieException
        if (e instanceof SAXException) {
            Exception nested = ((SAXException) e).getException();
            if (nested instanceof PerlDieException) {
                throw (PerlDieException) nested;
            }
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        // For SAXParseExceptions (XML parse errors), format like expat
        if (e instanceof org.xml.sax.SAXParseException) {
            org.xml.sax.SAXParseException spe = (org.xml.sax.SAXParseException) e;
            StringBuilder sb = new StringBuilder();
            sb.append("not well-formed (invalid token)");
            sb.append("\nat line ").append(spe.getLineNumber());
            sb.append(", column ").append(spe.getColumnNumber());
            sb.append("\n(Hint: \"not well-formed\" often indicates unescaped '<', '>' or '&'");
            sb.append(" in content \u2014 use &lt; &gt; or &amp; instead)\n");
            return sb.toString();
        }
        if (state.locator != null) {
            msg += "\nat line " + state.locator.getLineNumber()
                    + ", column " + state.locator.getColumnNumber();
        }
        return msg;
    }
}
