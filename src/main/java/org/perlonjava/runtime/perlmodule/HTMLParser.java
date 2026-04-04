package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import org.perlonjava.runtime.mro.InheritanceResolver;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

import java.nio.charset.StandardCharsets;

/**
 * Java XS implementation of HTML::Parser and HTML::Entities.
 * <p>
 * Mirrors the original Parser.xs which contains both PACKAGE = HTML::Parser
 * and PACKAGE = HTML::Entities in a single file, loaded via
 * XSLoader::load('HTML::Parser').
 * <p>
 * Phase 1: Full HTML::Entities support (decode_entities, _decode_entities)
 * plus HTML::Parser stubs for construction and configuration.
 */
public class HTMLParser extends PerlModuleBase {

    public static final String XS_VERSION = "3.83";

    public HTMLParser() {
        super("HTML::Parser", false);
    }

    public static void initialize() {
        HTMLParser module = new HTMLParser();

        try {
            // ============================================================
            // PACKAGE = HTML::Parser
            // ============================================================
            module.registerMethod("_alloc_pstate", null);
            module.registerMethod("parse", null);
            module.registerMethod("eof", "parserEof", null);

            // 13 boolean attribute accessors (aliased in XS via strict_comment)
            module.registerMethod("strict_comment", null);
            module.registerMethod("strict_names", null);
            module.registerMethod("xml_mode", null);
            module.registerMethod("unbroken_text", null);
            module.registerMethod("marked_sections", null);
            module.registerMethod("attr_encoded", null);
            module.registerMethod("case_sensitive", null);
            module.registerMethod("strict_end", null);
            module.registerMethod("closing_plaintext", null);
            module.registerMethod("utf8_mode", null);
            module.registerMethod("empty_element_tags", null);
            module.registerMethod("xml_pic", null);
            module.registerMethod("backquote", null);

            module.registerMethod("boolean_attribute_value", null);
            module.registerMethod("handler", null);
            module.registerMethod("report_tags", "tagListAccessor", null);
            module.registerMethod("ignore_tags", "tagListAccessor", null);
            module.registerMethod("ignore_elements", "tagListAccessor", null);

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing HTMLParser method: " + e.getMessage());
        }

        // ============================================================
        // PACKAGE = HTML::Entities
        // Cross-package registration: these functions go into the
        // HTML::Entities:: namespace but are loaded by HTML::Parser's XS.
        // ============================================================
        try {
            java.lang.invoke.MethodHandle mh;
            RuntimeCode code;

            mh = RuntimeCode.lookup.findStatic(HTMLParser.class, "decode_entities", RuntimeCode.methodType);
            code = new RuntimeCode(mh, null, null);
            code.isStatic = true;
            GlobalVariable.getGlobalCodeRef("HTML::Entities::decode_entities").set(new RuntimeScalar(code));

            mh = RuntimeCode.lookup.findStatic(HTMLParser.class, "_decode_entities", RuntimeCode.methodType);
            code = new RuntimeCode(mh, null, null);
            code.isStatic = true;
            GlobalVariable.getGlobalCodeRef("HTML::Entities::_decode_entities").set(new RuntimeScalar(code));

            mh = RuntimeCode.lookup.findStatic(HTMLParser.class, "UNICODE_SUPPORT", RuntimeCode.methodType);
            code = new RuntimeCode(mh, null, null);
            code.isStatic = true;
            GlobalVariable.getGlobalCodeRef("HTML::Entities::UNICODE_SUPPORT").set(new RuntimeScalar(code));

            mh = RuntimeCode.lookup.findStatic(HTMLParser.class, "_probably_utf8_chunk", RuntimeCode.methodType);
            code = new RuntimeCode(mh, null, null);
            code.isStatic = true;
            GlobalVariable.getGlobalCodeRef("HTML::Entities::_probably_utf8_chunk").set(new RuntimeScalar(code));

        } catch (NoSuchMethodException | IllegalAccessException e) {
            System.err.println("Warning: Missing HTMLEntities method: " + e.getMessage());
        }
    }

    // ================================================================
    // HTML::Parser methods
    // ================================================================

    /**
     * _alloc_pstate($self)
     * Allocates parser state and stores it in $self->{_hparser_xs_state}.
     * We use a RuntimeHash to hold the parser configuration.
     */
    public static RuntimeList _alloc_pstate(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();

        // Create parser state as a hash holding boolean flags, handlers, etc.
        RuntimeHash pstate = new RuntimeHash();

        // Initialize boolean flags to false
        String[] boolFlags = {
                "strict_comment", "strict_names", "xml_mode", "unbroken_text",
                "marked_sections", "attr_encoded", "case_sensitive", "strict_end",
                "closing_plaintext", "utf8_mode", "empty_element_tags", "xml_pic",
                "backquote"
        };
        for (String flag : boolFlags) {
            pstate.put(flag, scalarFalse);
        }

        // Initialize handler slots
        String[] events = {
                "declaration", "comment", "start", "end", "text",
                "process", "start_document", "end_document", "default"
        };
        RuntimeHash handlers = new RuntimeHash();
        for (String event : events) {
            handlers.put(event + "_cb", scalarUndef);
            handlers.put(event + "_argspec", scalarUndef);
        }
        pstate.put("_handlers", handlers.createReference());

        // State tracking
        pstate.put("_parsing", scalarFalse);
        pstate.put("_eof", scalarFalse);
        pstate.put("_buf", new RuntimeScalar(""));
        pstate.put("_bool_attr_val", scalarUndef);

        // Store in self
        selfHash.put("_hparser_xs_state", pstate.createReference());

        return new RuntimeList();
    }

    /**
     * parse($self, $chunk)
     * Feeds HTML to the parser. Phase 1: basic event-driven parsing.
     */
    public static RuntimeList parse(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();
        RuntimeHash pstate = getPstate(selfHash);

        if (pstate.get("_parsing").getBoolean()) {
            throw new RuntimeException("Parse loop not allowed");
        }

        pstate.put("_parsing", scalarTrue);

        try {
            if (args.size() > 1) {
                RuntimeScalar chunk = args.get(1);
                if (chunk.getDefinedBoolean()) {
                    String chunkStr = chunk.toString();

                    // When utf8_mode is set and the input is a BYTE_STRING, try to
                    // decode UTF-8 byte sequences to characters. If decoding fails
                    // (e.g., the bytes are Latin-1, not UTF-8), keep the original
                    // string unchanged - each byte maps to the corresponding Unicode
                    // code point, which preserves Latin-1 characters like ø (0xF8).
                    // This matches Perl 5's XS parser behavior where character values
                    // are preserved regardless of utf8_mode.
                    if (pstate.get("utf8_mode").getBoolean()
                            && chunk.type == RuntimeScalarType.BYTE_STRING) {
                        byte[] bytes = chunkStr.getBytes(StandardCharsets.ISO_8859_1);
                        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                        try {
                            chunkStr = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                        } catch (java.nio.charset.CharacterCodingException e) {
                            // Not valid UTF-8; keep original string (Latin-1 identity mapping)
                        }
                    }

                    String html = pstate.get("_buf").toString() + chunkStr;
                    pstate.put("_buf", new RuntimeScalar(""));
                    parseHtml(self, selfHash, pstate, html);
                }
            }
        } finally {
            pstate.put("_parsing", scalarFalse);
        }

        if (pstate.get("_eof").getBoolean()) {
            pstate.put("_eof", scalarFalse);
            return scalarUndef.getList();
        }
        return self.getList();
    }

    /**
     * eof($self)
     * Signals end-of-document, flushes buffered text.
     */
    public static RuntimeList parserEof(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();
        RuntimeHash pstate = getPstate(selfHash);

        if (pstate.get("_parsing").getBoolean()) {
            pstate.put("_eof", scalarTrue);
        } else {
            pstate.put("_parsing", scalarTrue);
            try {
                // Flush any remaining buffered text
                String remaining = pstate.get("_buf").toString();
                if (!remaining.isEmpty()) {
                    pstate.put("_buf", new RuntimeScalar(""));
                    parseHtml(self, selfHash, pstate, remaining);
                }
                // Fire end_document event
                fireEvent(self, selfHash, pstate, "end_document");
            } finally {
                pstate.put("_parsing", scalarFalse);
            }
        }
        return self.getList();
    }

    // 13 boolean attribute getter/setters - each delegates to booleanAccessorHelper
    public static RuntimeList strict_comment(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "strict_comment"); }
    public static RuntimeList strict_names(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "strict_names"); }
    public static RuntimeList xml_mode(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "xml_mode"); }
    public static RuntimeList unbroken_text(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "unbroken_text"); }
    public static RuntimeList marked_sections(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "marked_sections"); }
    public static RuntimeList attr_encoded(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "attr_encoded"); }
    public static RuntimeList case_sensitive(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "case_sensitive"); }
    public static RuntimeList strict_end(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "strict_end"); }
    public static RuntimeList closing_plaintext(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "closing_plaintext"); }
    public static RuntimeList utf8_mode(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "utf8_mode"); }
    public static RuntimeList empty_element_tags(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "empty_element_tags"); }
    public static RuntimeList xml_pic(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "xml_pic"); }
    public static RuntimeList backquote(RuntimeArray args, int ctx) { return booleanAccessorHelper(args, "backquote"); }

    private static RuntimeList booleanAccessorHelper(RuntimeArray args, String attrName) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();
        RuntimeHash pstate = getPstate(selfHash);

        RuntimeScalar old = pstate.get(attrName);
        RuntimeScalar retval = (old != null && old.getBoolean()) ? scalarTrue : scalarFalse;
        if (args.size() > 1) {
            pstate.put(attrName, args.get(1).getBoolean() ? scalarTrue : scalarFalse);
        }
        return retval.getList();
    }

    /**
     * boolean_attribute_value($pstate, [$new_value])
     */
    public static RuntimeList boolean_attribute_value(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();
        RuntimeHash pstate = getPstate(selfHash);

        RuntimeScalar old = pstate.get("_bool_attr_val");
        if (args.size() > 1) {
            pstate.put("_bool_attr_val", args.get(1));
        }
        return old.getList();
    }

    /**
     * handler($pstate, $eventname, [$callback, $argspec])
     */
    public static RuntimeList handler(RuntimeArray args, int ctx) {
        RuntimeScalar self = args.get(0);
        RuntimeHash selfHash = self.hashDeref();
        RuntimeHash pstate = getPstate(selfHash);

        if (args.size() < 2) {
            throw new RuntimeException("Usage: $p->handler(event => cb, argspec)");
        }

        String eventName = args.get(1).toString();
        RuntimeHash handlers = pstate.get("_handlers").hashDeref();

        // Return old handler
        RuntimeScalar oldCb = handlers.get(eventName + "_cb");

        // Update handler if new callback provided
        if (args.size() > 2) {
            RuntimeScalar newCb = args.get(2);
            handlers.put(eventName + "_cb", newCb);
        }
        if (args.size() > 3) {
            RuntimeScalar argspec = args.get(3);
            handlers.put(eventName + "_argspec", argspec);
        }

        return (oldCb != null) ? oldCb.getList() : scalarUndef.getList();
    }

    /**
     * Tag list accessor (report_tags, ignore_tags, ignore_elements).
     */
    public static RuntimeList tagListAccessor(RuntimeArray args, int ctx) {
        // Phase 1 stub - tag filtering not yet implemented
        return new RuntimeList();
    }

    // ================================================================
    // HTML::Entities methods (PACKAGE = HTML::Entities in Parser.xs)
    // ================================================================

    /**
     * decode_entities(...)
     * <p>
     * In void context: decodes entities in-place in the arguments.
     * In scalar context with multiple args: only processes first argument, returns copy.
     * In list context: returns decoded copies of all arguments.
     */
    public static RuntimeList decode_entities(RuntimeArray args, int ctx) {
        RuntimeHash entity2char = GlobalVariable.getGlobalHash("HTML::Entities::entity2char");

        int items = args.size();
        if (ctx == RuntimeContextType.SCALAR && items > 1) {
            items = 1;
        }

        if (ctx == RuntimeContextType.VOID) {
            // Void context: modify in-place
            for (int i = 0; i < items; i++) {
                RuntimeScalar sv = args.get(i);
                String decoded = decodeEntitiesString(sv.toString(), entity2char, false);
                sv.set(decoded);
            }
            return new RuntimeList();
        } else {
            // Scalar/list context: return decoded copies
            RuntimeList result = new RuntimeList();
            for (int i = 0; i < items; i++) {
                String decoded = decodeEntitiesString(args.get(i).toString(), entity2char, false);
                result.add(new RuntimeScalar(decoded));
            }
            return result;
        }
    }

    /**
     * _decode_entities($string, \%entity2char, $expand_prefix)
     * In-place decode with explicit entity hash and optional prefix expansion.
     */
    public static RuntimeList _decode_entities(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new RuntimeException("Usage: _decode_entities(string, entity2char_hash, [expand_prefix])");
        }

        RuntimeScalar stringSv = args.get(0);
        RuntimeScalar entitiesSv = args.get(1);
        boolean expandPrefix = args.size() > 2 && args.get(2).getBoolean();

        RuntimeHash entityHash = null;
        if (entitiesSv.getDefinedBoolean()) {
            if (RuntimeScalarType.isReference(entitiesSv)) {
                try {
                    entityHash = entitiesSv.hashDeref();
                } catch (Exception e) {
                    // Not a hash reference
                }
            }
            if (entityHash == null) {
                throw new RuntimeException("2nd argument must be hash reference");
            }
        }

        String decoded = decodeEntitiesString(stringSv.toString(), entityHash, expandPrefix);
        stringSv.set(decoded);

        return new RuntimeList();
    }

    /**
     * UNICODE_SUPPORT() - always returns 1
     */
    public static RuntimeList UNICODE_SUPPORT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    /**
     * _probably_utf8_chunk($string) - checks if string looks like valid UTF-8
     */
    public static RuntimeList _probably_utf8_chunk(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }
        String s = args.get(0).toString();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return scalarTrue.getList();
            }
        }
        return scalarFalse.getList();
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    /**
     * Retrieve the parser state hash from $self->{_hparser_xs_state}.
     */
    private static RuntimeHash getPstate(RuntimeHash selfHash) {
        RuntimeScalar ref = selfHash.get("_hparser_xs_state");
        if (ref == null || !ref.getDefinedBoolean()) {
            throw new RuntimeException("HTML::Parser not initialized (missing _hparser_xs_state)");
        }
        return ref.hashDeref();
    }

    /**
     * Fire a parser event by calling the registered handler.
     * @param self the original blessed parser object (for method dispatch)
     * @param selfHash the dereferenced hash of the parser
     * @param pstate the parser state hash
     * @param eventName the event type (start, end, text, etc.)
     * @param eventArgs the event-specific arguments
     */
    private static void fireEvent(RuntimeScalar self, RuntimeHash selfHash, RuntimeHash pstate, String eventName, RuntimeScalar... eventArgs) {
        RuntimeHash handlers = pstate.get("_handlers").hashDeref();
        RuntimeScalar cb = handlers.get(eventName + "_cb");

        if (cb == null || !cb.getDefinedBoolean()) {
            return;
        }

        RuntimeArray callArgs = new RuntimeArray();

        // Parse argspec to determine what arguments to pass
        RuntimeScalar argspecSv = handlers.get(eventName + "_argspec");
        String argspec = (argspecSv != null && argspecSv.getDefinedBoolean()) ?
                argspecSv.toString() : "";

        if (cb.type == RuntimeScalarType.STRING || cb.type == RuntimeScalarType.BYTE_STRING) {
            // Method name - call as $self->method(...)
            // Use the original blessed self for correct method dispatch
            String methodName = cb.toString();
            RuntimeArray.push(callArgs, self);
            for (RuntimeScalar arg : eventArgs) {
                RuntimeArray.push(callArgs, arg);
            }
            // Look up method in the object's class hierarchy using the blessed class
            int blessId = RuntimeScalarType.blessedId(self);
            String className = (blessId != 0) ?
                    NameNormalizer.getBlessStr(blessId) : "HTML::Parser";
            RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
                    methodName, className, null, 0);
            if (method != null) {
                RuntimeCode.apply(method, callArgs, RuntimeContextType.VOID);
            }
        } else if (cb.type == RuntimeScalarType.REFERENCE || cb.type == RuntimeScalarType.CODE) {
            // Code reference - call directly
            for (RuntimeScalar arg : eventArgs) {
                RuntimeArray.push(callArgs, arg);
            }
            RuntimeCode.apply(cb, callArgs, RuntimeContextType.VOID);
        }
    }

    /**
     * Basic HTML parser - fires text, start, end events.
     * This is a simplified version; Phase 2 will port the full hparser.c logic.
     */
    private static void parseHtml(RuntimeScalar self, RuntimeHash selfHash, RuntimeHash pstate, String html) {
        int len = html.length();
        int i = 0;
        int textStart = 0;

        while (i < len) {
            if (html.charAt(i) == '<') {
                // Flush pending text
                if (i > textStart) {
                    fireEvent(self, selfHash, pstate, "text",
                            new RuntimeScalar(html.substring(textStart, i)));
                }

                int tagStart = i;
                i++; // skip '<'

                if (i < len && html.charAt(i) == '/') {
                    // End tag
                    i++;
                    int nameStart = i;
                    while (i < len && html.charAt(i) != '>' && !Character.isWhitespace(html.charAt(i))) {
                        i++;
                    }
                    String tagName = html.substring(nameStart, i).toLowerCase();
                    while (i < len && html.charAt(i) != '>') i++;
                    if (i < len) i++; // skip '>'

                    fireEvent(self, selfHash, pstate, "end",
                            new RuntimeScalar(tagName),
                            new RuntimeScalar(html.substring(tagStart, i)));
                    textStart = i;
                } else if (i < len && html.charAt(i) == '!') {
                    // Comment or declaration
                    i++;
                    if (i + 1 < len && html.charAt(i) == '-' && html.charAt(i + 1) == '-') {
                        // Comment
                        i += 2;
                        int commentStart = i;
                        int endIdx = html.indexOf("-->", i);
                        if (endIdx >= 0) {
                            String comment = html.substring(commentStart, endIdx);
                            i = endIdx + 3;
                            fireEvent(self, selfHash, pstate, "comment",
                                    new RuntimeScalar(comment));
                        } else {
                            // Unterminated comment - buffer it
                            pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                            return;
                        }
                    } else {
                        // Declaration
                        int endIdx = html.indexOf('>', i);
                        if (endIdx >= 0) {
                            String decl = html.substring(tagStart, endIdx + 1);
                            i = endIdx + 1;
                            fireEvent(self, selfHash, pstate, "declaration",
                                    new RuntimeScalar(decl));
                        } else {
                            pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                            return;
                        }
                    }
                    textStart = i;
                } else if (i < len && html.charAt(i) == '?') {
                    // Processing instruction
                    int endIdx = html.indexOf("?>", i);
                    if (endIdx >= 0) {
                        String pi = html.substring(tagStart, endIdx + 2);
                        i = endIdx + 2;
                        fireEvent(self, selfHash, pstate, "process",
                                new RuntimeScalar(pi));
                    } else {
                        pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                        return;
                    }
                    textStart = i;
                } else {
                    // Start tag
                    int nameStart = i;
                    while (i < len && html.charAt(i) != '>' && html.charAt(i) != '/'
                            && !Character.isWhitespace(html.charAt(i))) {
                        i++;
                    }
                    String tagName = html.substring(nameStart, i).toLowerCase();

                    // Parse attributes
                    RuntimeHash attrs = new RuntimeHash();
                    RuntimeArray attrSeq = new RuntimeArray();

                    while (i < len && html.charAt(i) != '>' && html.charAt(i) != '/') {
                        // Skip whitespace
                        while (i < len && Character.isWhitespace(html.charAt(i))) i++;
                        if (i >= len || html.charAt(i) == '>' || html.charAt(i) == '/') break;

                        // Attribute name
                        int attrNameStart = i;
                        while (i < len && html.charAt(i) != '=' && html.charAt(i) != '>'
                                && html.charAt(i) != '/' && !Character.isWhitespace(html.charAt(i))) {
                            i++;
                        }
                        String attrName = html.substring(attrNameStart, i).toLowerCase();
                        RuntimeArray.push(attrSeq, new RuntimeScalar(attrName));

                        // Skip whitespace
                        while (i < len && Character.isWhitespace(html.charAt(i))) i++;

                        String attrValue = attrName; // boolean attribute default
                        if (i < len && html.charAt(i) == '=') {
                            i++; // skip '='
                            while (i < len && Character.isWhitespace(html.charAt(i))) i++;

                            if (i < len) {
                                if (html.charAt(i) == '"' || html.charAt(i) == '\'') {
                                    char quote = html.charAt(i);
                                    i++;
                                    int valStart = i;
                                    while (i < len && html.charAt(i) != quote) i++;
                                    attrValue = html.substring(valStart, i);
                                    if (i < len) i++; // skip closing quote
                                } else {
                                    int valStart = i;
                                    while (i < len && html.charAt(i) != '>'
                                            && !Character.isWhitespace(html.charAt(i))) {
                                        i++;
                                    }
                                    attrValue = html.substring(valStart, i);
                                }
                            }
                        }
                        attrs.put(attrName, new RuntimeScalar(attrValue));
                    }

                    // Handle self-closing />
                    boolean selfClosing = false;
                    if (i < len && html.charAt(i) == '/') {
                        selfClosing = true;
                        i++;
                    }
                    if (i < len && html.charAt(i) == '>') i++;

                    String origText = html.substring(tagStart, i);

                    fireEvent(self, selfHash, pstate, "start",
                            new RuntimeScalar(tagName),
                            attrs.createReference(),
                            attrSeq.createReference(),
                            new RuntimeScalar(origText));

                    if (selfClosing) {
                        fireEvent(self, selfHash, pstate, "end",
                                new RuntimeScalar(tagName),
                                new RuntimeScalar("</" + tagName + ">"));
                    }
                    textStart = i;
                }
            } else {
                i++;
            }
        }

        // Flush remaining text
        if (textStart < len) {
            fireEvent(self, selfHash, pstate, "text",
                    new RuntimeScalar(html.substring(textStart)));
        }
    }

    // ================================================================
    // Entity decoding core (ported from util.c decode_entities)
    // ================================================================

    /**
     * Core entity decoding algorithm, ported from util.c decode_entities().
     * Handles numeric entities (decimal/hex), named entities, surrogate pairs,
     * and prefix expansion mode.
     */
    private static String decodeEntitiesString(String input, RuntimeHash entity2char, boolean expandPrefix) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder(input.length());
        int len = input.length();
        int i = 0;
        int highSurrogate = 0;

        while (i < len) {
            char ch = input.charAt(i);
            if (ch != '&') {
                result.append(ch);
                i++;
                continue;
            }

            int entStart = i;
            i++; // skip '&'

            if (i >= len) {
                result.append('&');
                continue;
            }

            String replacement = null;

            if (input.charAt(i) == '#') {
                // Numeric entity
                i++; // skip '#'
                long num = 0;
                boolean ok = false;

                if (i < len && (input.charAt(i) == 'x' || input.charAt(i) == 'X')) {
                    // Hex: &#xHH;
                    i++;
                    while (i < len) {
                        int digit = hexDigit(input.charAt(i));
                        if (digit < 0) break;
                        num = (num << 4) | digit;
                        if (num > 0x10FFFF) { ok = false; break; }
                        i++;
                        ok = true;
                    }
                } else {
                    // Decimal: &#NNN;
                    while (i < len && input.charAt(i) >= '0' && input.charAt(i) <= '9') {
                        num = num * 10 + (input.charAt(i) - '0');
                        if (num > 0x10FFFF) { ok = false; break; }
                        i++;
                        ok = true;
                    }
                }

                if (num > 0 && ok) {
                    // Handle surrogates (matching C code logic)
                    if ((num & 0xFFFFFC00L) == 0xDC00L) {
                        // Low surrogate
                        if (highSurrogate != 0) {
                            // Combine with previous high surrogate
                            num = ((long) (highSurrogate - 0xD800) << 10) +
                                    (num - 0xDC00) + 0x10000;
                            highSurrogate = 0;
                            // Remove previously appended U+FFFD
                            if (result.length() > 0 && result.charAt(result.length() - 1) == '\uFFFD') {
                                result.setLength(result.length() - 1);
                            }
                        } else {
                            num = 0xFFFD;
                        }
                    } else if ((num & 0xFFFFFC00L) == 0xD800L) {
                        // High surrogate
                        highSurrogate = (int) num;
                        num = 0xFFFD;
                    } else {
                        highSurrogate = 0;
                        if (num == 0xFFFE || num == 0xFFFF) {
                            // Illegal
                            ok = false;
                        } else if ((num >= 0xFDD0 && num <= 0xFDEF) ||
                                ((num & 0xFFFE) == 0xFFFE) ||
                                num > 0x10FFFF) {
                            num = 0xFFFD;
                        }
                    }

                    if (ok && num > 0) {
                        replacement = new String(Character.toChars((int) num));
                    }
                }

                if (replacement == null) {
                    highSurrogate = 0;
                }
            } else {
                // Named entity
                int nameStart = i;
                while (i < len && isAlnum(input.charAt(i))) {
                    i++;
                }

                if (i > nameStart && entity2char != null) {
                    String entityName = input.substring(nameStart, i);

                    // Try exact match without semicolon
                    RuntimeScalar val = entity2char.get(entityName);
                    if (val != null && val.getDefinedBoolean()) {
                        replacement = val.toString();
                    }

                    // Try with semicolon appended (for entities keyed with trailing ";")
                    if (replacement == null && i < len && input.charAt(i) == ';') {
                        val = entity2char.get(entityName + ";");
                        if (val != null && val.getDefinedBoolean()) {
                            replacement = val.toString();
                        }
                    }

                    // Prefix expansion mode
                    if (replacement == null && expandPrefix) {
                        for (int end = i - 1; end > nameStart; end--) {
                            String prefix = input.substring(nameStart, end);
                            val = entity2char.get(prefix);
                            if (val != null && val.getDefinedBoolean()) {
                                replacement = val.toString();
                                i = end;
                                break;
                            }
                        }
                    }
                }
                highSurrogate = 0;
            }

            if (replacement != null) {
                // Consume trailing semicolon if present
                if (i < len && input.charAt(i) == ';') {
                    i++;
                }
                result.append(replacement);

                if (i >= len || input.charAt(i) != '&') {
                    highSurrogate = 0;
                }
            } else {
                // No match - output original text
                result.append(input, entStart, i);
            }
        }

        return result.toString();
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '_';
    }
}
