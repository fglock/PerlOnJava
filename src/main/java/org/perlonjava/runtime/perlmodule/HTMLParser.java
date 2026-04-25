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
        pstate.put("_in_cdata", scalarFalse);

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
     * Supports three callback types:
     * - String: method name to call on $self
     * - Code ref: subroutine reference to call directly
     * - Array ref: accumulator for PullParser/TokeParser (push event data)
     *
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

        // Parse argspec to determine what arguments to pass
        RuntimeScalar argspecSv = handlers.get(eventName + "_argspec");
        String argspec = (argspecSv != null && argspecSv.getDefinedBoolean()) ?
                argspecSv.toString() : "";

        if (cb.type == RuntimeScalarType.ARRAYREFERENCE) {
            // Array ref accumulator - used by PullParser/TokeParser
            // Build event data per argspec and push as array ref onto accumulator
            RuntimeArray accum = (RuntimeArray) cb.value;
            RuntimeArray eventData = buildEventDataFromArgspec(argspec, eventName, eventArgs, self, false, pstate);
            RuntimeArray.push(accum, eventData.createReference());
        } else if (cb.type == RuntimeScalarType.STRING || cb.type == RuntimeScalarType.BYTE_STRING) {
            // Method name - call as $self->method(...)
            String methodName = cb.toString();
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, self);
            // Build args from argspec if available, otherwise pass raw eventArgs
            // skipSelf=true: "self" in argspec specifies the invocant for method dispatch
            // but should NOT be duplicated in the method arguments
            if (!argspec.isEmpty()) {
                RuntimeArray eventData = buildEventDataFromArgspec(argspec, eventName, eventArgs, self, true, pstate);
                for (int idx = 0; idx < eventData.size(); idx++) {
                    RuntimeArray.push(callArgs, eventData.get(idx));
                }
            } else {
                for (RuntimeScalar arg : eventArgs) {
                    RuntimeArray.push(callArgs, arg);
                }
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
            RuntimeArray callArgs = new RuntimeArray();
            if (!argspec.isEmpty()) {
                RuntimeArray eventData = buildEventDataFromArgspec(argspec, eventName, eventArgs, self, false, pstate);
                for (int idx = 0; idx < eventData.size(); idx++) {
                    RuntimeArray.push(callArgs, eventData.get(idx));
                }
            } else {
                for (RuntimeScalar arg : eventArgs) {
                    RuntimeArray.push(callArgs, arg);
                }
            }
            RuntimeCode.apply(cb, callArgs, RuntimeContextType.VOID);
        }
    }

    /**
     * Build event data array from an argspec string.
     * Argspec is a comma-separated list of tokens that specify what data to include.
     *
     * Supported argspec tokens:
     * - Quoted literals: 'S', 'E', 'T', 'C', 'D', 'PI' etc.
     * - tagname: the tag name
     * - attr: hash ref of attributes (for start events)
     * - attrseq: array ref of attribute names in order (for start events)
     * - text: original HTML text
     * - dtext: decoded text (entities decoded)
     * - is_cdata: boolean - is this CDATA?
     * - self: the parser object
     * - event: the event name
     * - tag: same as tagname (alias)
     * - offset: byte offset in document
     * - length: length of original text
     * - offset_end: end offset
     * - line: line number
     * - column: column number
     * - token0: first token (for PI)
     * - skipped_text: text skipped by handler
     *
     * For start events, eventArgs = [tagname, attr_ref, attrseq_ref, origtext]
     * For end events, eventArgs = [tagname, origtext]
     * For text events, eventArgs = [text]
     * For comment events, eventArgs = [comment]
     * For declaration events, eventArgs = [decl_text]
     * For process events, eventArgs = [pi_text]
     */
    private static RuntimeArray buildEventDataFromArgspec(String argspec, String eventName, RuntimeScalar[] eventArgs, RuntimeScalar self, boolean skipSelf, RuntimeHash pstate) {
        RuntimeArray result = new RuntimeArray();
        if (argspec.isEmpty()) {
            // No argspec - pass raw event args
            for (RuntimeScalar arg : eventArgs) {
                RuntimeArray.push(result, arg);
            }
            return result;
        }

        // Parse comma-separated argspec tokens
        String[] tokens = argspec.split(",");
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) continue;

            // Check for quoted literal: 'X' or "X"
            if ((token.startsWith("'") && token.endsWith("'")) ||
                    (token.startsWith("\"") && token.endsWith("\""))) {
                String literal = token.substring(1, token.length() - 1);
                RuntimeArray.push(result, new RuntimeScalar(literal));
                continue;
            }

            switch (token) {
                case "tagname":
                case "tag":
                    // First arg for start/end events is tagname
                    if (eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[0]);
                    } else {
                        RuntimeArray.push(result, scalarUndef);
                    }
                    break;

                case "attr":
                    // Second arg for start events is attr hash ref
                    if ("start".equals(eventName) && eventArgs.length > 1) {
                        RuntimeArray.push(result, eventArgs[1]);
                    } else {
                        // Return empty hash ref for non-start events
                        RuntimeArray.push(result, new RuntimeHash().createReference());
                    }
                    break;

                case "attrseq":
                    // Third arg for start events is attrseq array ref
                    if ("start".equals(eventName) && eventArgs.length > 2) {
                        RuntimeArray.push(result, eventArgs[2]);
                    } else {
                        RuntimeArray.push(result, new RuntimeArray().createReference());
                    }
                    break;

                case "text":
                    // Original text: last arg for start/end, first arg for text
                    if ("text".equals(eventName) && eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[0]);
                    } else if ("start".equals(eventName) && eventArgs.length > 3) {
                        RuntimeArray.push(result, eventArgs[3]);
                    } else if ("end".equals(eventName) && eventArgs.length > 1) {
                        RuntimeArray.push(result, eventArgs[1]);
                    } else if ("comment".equals(eventName) && eventArgs.length > 0) {
                        RuntimeArray.push(result, new RuntimeScalar("<!--" + eventArgs[0].toString() + "-->"));
                    } else if ("declaration".equals(eventName) && eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[0]);
                    } else if ("process".equals(eventName) && eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[0]);
                    } else if (eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[eventArgs.length - 1]);
                    } else {
                        RuntimeArray.push(result, new RuntimeScalar(""));
                    }
                    break;

                case "dtext":
                    // Decoded text (entity-decoded) - for text events
                    if ("text".equals(eventName) && eventArgs.length > 0) {
                        // Decode entities in the text
                        String rawText = eventArgs[0].toString();
                        RuntimeHash entity2char = GlobalVariable.getGlobalHash("HTML::Entities::entity2char");
                        String decoded = decodeEntitiesString(rawText, entity2char, false);
                        RuntimeArray.push(result, new RuntimeScalar(decoded));
                    } else if (eventArgs.length > 0) {
                        RuntimeArray.push(result, eventArgs[eventArgs.length - 1]);
                    } else {
                        RuntimeArray.push(result, new RuntimeScalar(""));
                    }
                    break;

                case "is_cdata":
                    // Boolean: is this CDATA section?
                    // Check the _in_cdata flag set by marked section parsing
                    RuntimeScalar inCdata = pstate.get("_in_cdata");
                    RuntimeArray.push(result, (inCdata != null && inCdata.getBoolean()) ? scalarTrue : scalarFalse);
                    break;

                case "self":
                    // For method callbacks, "self" is already the invocant
                    // and should not be duplicated in the args
                    if (!skipSelf) {
                        RuntimeArray.push(result, self);
                    }
                    break;

                case "event":
                    RuntimeArray.push(result, new RuntimeScalar(eventName));
                    break;

                case "offset":
                case "offset_end":
                    // Offset tracking not implemented yet
                    RuntimeArray.push(result, new RuntimeScalar(0));
                    break;

                case "length":
                    // Length of original text
                    if (eventArgs.length > 0) {
                        String lastArg;
                        if ("start".equals(eventName) && eventArgs.length > 3) {
                            lastArg = eventArgs[3].toString();
                        } else if ("end".equals(eventName) && eventArgs.length > 1) {
                            lastArg = eventArgs[1].toString();
                        } else {
                            lastArg = eventArgs[0].toString();
                        }
                        RuntimeArray.push(result, new RuntimeScalar(lastArg.length()));
                    } else {
                        RuntimeArray.push(result, new RuntimeScalar(0));
                    }
                    break;

                case "line":
                case "column":
                    // Line/column tracking not implemented yet
                    RuntimeArray.push(result, new RuntimeScalar(0));
                    break;

                case "token0":
                    // First token for process instructions
                    if ("process".equals(eventName) && eventArgs.length > 0) {
                        String piText = eventArgs[0].toString();
                        // Extract first token from <?token ...?>
                        if (piText.startsWith("<?")) {
                            piText = piText.substring(2);
                            if (piText.endsWith("?>")) {
                                piText = piText.substring(0, piText.length() - 2);
                            }
                            String[] parts = piText.trim().split("\\s+", 2);
                            RuntimeArray.push(result, new RuntimeScalar(parts[0]));
                        } else {
                            RuntimeArray.push(result, new RuntimeScalar(""));
                        }
                    } else {
                        // Fall back to tokens[0] for non-PI events
                        RuntimeArray tokensArr = buildTokensArray(eventName, eventArgs);
                        if (tokensArr.size() > 0) {
                            RuntimeArray.push(result, tokensArr.get(0));
                        } else {
                            RuntimeArray.push(result, new RuntimeScalar(""));
                        }
                    }
                    break;

                case "tokens":
                    // Array reference of all tokens for this event.
                    //   start       => [tagname, attr1, val1, attr2, val2, ...]
                    //   end         => [tagname]
                    //   text/dtext  => [text]
                    //   comment     => [comment_body]
                    //   declaration => [declaration_body]
                    //   process     => [pi_body]
                    RuntimeArray.push(result,
                            buildTokensArray(eventName, eventArgs).createReference());
                    break;

                case "tokenpos":
                    // Array reference of [start, end] byte-offset pairs
                    // matching `tokens`. We don't track byte offsets yet, so
                    // return a same-length arrayref of [0, 0] pairs. This is
                    // good enough for callers that just iterate; downstream
                    // modules treating tokenpos as authoritative will need
                    // proper offset tracking (currently a TODO at the
                    // `offset`/`offset_end` cases).
                    {
                        RuntimeArray pos = new RuntimeArray();
                        RuntimeArray tokensArr = buildTokensArray(eventName, eventArgs);
                        for (int i = 0; i < tokensArr.size(); i++) {
                            RuntimeArray pair = new RuntimeArray();
                            RuntimeArray.push(pair, new RuntimeScalar(0));
                            RuntimeArray.push(pair, new RuntimeScalar(0));
                            RuntimeArray.push(pos, pair.createReference());
                        }
                        RuntimeArray.push(result, pos.createReference());
                    }
                    break;

                case "skipped_text":
                    RuntimeArray.push(result, new RuntimeScalar(""));
                    break;

                default:
                    // tokenN where N is a non-negative integer => tokens[N]
                    if (token.length() > 5 && token.startsWith("token")
                            && token.substring(5).chars().allMatch(Character::isDigit)) {
                        int idx;
                        try {
                            idx = Integer.parseInt(token.substring(5));
                        } catch (NumberFormatException e) {
                            idx = -1;
                        }
                        RuntimeArray tokensArr = buildTokensArray(eventName, eventArgs);
                        if (idx >= 0 && idx < tokensArr.size()) {
                            RuntimeArray.push(result, tokensArr.get(idx));
                        } else {
                            RuntimeArray.push(result, new RuntimeScalar(""));
                        }
                    } else {
                        // Unknown argspec token - pass empty string
                        RuntimeArray.push(result, new RuntimeScalar(""));
                    }
                    break;
            }
        }

        return result;
    }

    /**
     * Build the `tokens` array for a given event, per HTML::Parser semantics.
     * See `case "tokens":` above for the per-event shape.
     *
     * @param eventName the event name (start, end, text, comment, ...)
     * @param eventArgs the internal event-arg tuple as passed to fireEvent
     * @return a flat RuntimeArray of token scalars (NOT yet a reference)
     */
    private static RuntimeArray buildTokensArray(String eventName, RuntimeScalar[] eventArgs) {
        RuntimeArray tokens = new RuntimeArray();
        if (eventArgs == null || eventArgs.length == 0) {
            return tokens;
        }
        switch (eventName) {
            case "start":
                // eventArgs = [tagname, attr_hashref, attrseq_arrayref, original_text]
                RuntimeArray.push(tokens, eventArgs[0]);
                if (eventArgs.length > 2) {
                    RuntimeScalar attrHashRef = eventArgs[1];
                    RuntimeScalar attrSeqRef = eventArgs[2];
                    RuntimeHash attrHash = attrHashRef.hashDeref();
                    RuntimeArray attrSeq = attrSeqRef.arrayDeref();
                    int n = attrSeq.size();
                    for (int i = 0; i < n; i++) {
                        RuntimeScalar key = attrSeq.get(i);
                        String keyStr = key.toString();
                        RuntimeArray.push(tokens, key);
                        RuntimeArray.push(tokens, attrHash.get(keyStr));
                    }
                }
                break;
            case "end":
            case "text":
            case "dtext":
            case "comment":
            case "declaration":
            case "process":
            case "default":
                RuntimeArray.push(tokens, eventArgs[0]);
                break;
            default:
                // Unknown event: best-effort, push the first arg.
                RuntimeArray.push(tokens, eventArgs[0]);
                break;
        }
        return tokens;
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

                // If we're at end of input, buffer the '<' for next parse() call
                if (i >= len) {
                    pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                    return;
                }

                if (i < len && html.charAt(i) == '/') {
                    // End tag
                    i++;
                    int nameStart = i;
                    while (i < len && html.charAt(i) != '>' && !Character.isWhitespace(html.charAt(i))) {
                        i++;
                    }
                    String tagName = html.substring(nameStart, i).toLowerCase();
                    while (i < len && html.charAt(i) != '>') i++;
                    if (i >= len) {
                        // Incomplete end tag - buffer for next parse() call
                        pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                        return;
                    }
                    if (i < len) i++; // skip '>'

                    fireEvent(self, selfHash, pstate, "end",
                            new RuntimeScalar(tagName),
                            new RuntimeScalar(html.substring(tagStart, i)));
                    textStart = i;
                } else if (i < len && html.charAt(i) == '!') {
                    // Comment, marked section, or declaration
                    i++;

                    // Check for marked sections: <![KEYWORD[...]]>
                    boolean markedSections = pstate.get("marked_sections").getBoolean()
                            || pstate.get("xml_mode").getBoolean();
                    if (i < len && html.charAt(i) == '[') {
                        if (markedSections) {
                            i++; // skip '['
                            // Extract keyword (CDATA, INCLUDE, IGNORE, etc.)
                            int kwStart = i;
                            while (i < len && html.charAt(i) != '[' && html.charAt(i) != ']') i++;
                            String keyword = html.substring(kwStart, i).trim().toUpperCase();

                            if (i < len && html.charAt(i) == '[') {
                                i++; // skip second '['
                                int contentStart = i;
                                int endIdx = html.indexOf("]]>", i);

                                if (endIdx < 0) {
                                    // Unterminated marked section - buffer
                                    pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                                    return;
                                }

                                String content = html.substring(contentStart, endIdx);
                                i = endIdx + 3; // skip ]]>

                                switch (keyword) {
                                    case "CDATA":
                                        // Emit as text with is_cdata=true
                                        pstate.put("_in_cdata", scalarTrue);
                                        fireEvent(self, selfHash, pstate, "text",
                                                new RuntimeScalar(content));
                                        pstate.put("_in_cdata", scalarFalse);
                                        break;
                                    case "IGNORE":
                                        // Skip content entirely
                                        break;
                                    case "INCLUDE":
                                    default:
                                        // Recursively parse content as HTML
                                        // Save and restore textStart since we recurse
                                        RuntimeScalar savedBuf = pstate.get("_buf");
                                        pstate.put("_buf", new RuntimeScalar(""));
                                        parseHtml(self, selfHash, pstate, content);
                                        pstate.put("_buf", savedBuf);
                                        break;
                                }
                            } else {
                                // Malformed <![...] without second [ - treat as declaration
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
                        } else {
                            // marked_sections disabled - treat as bogus comment (text up to >)
                            int endIdx = html.indexOf('>', i);
                            if (endIdx >= 0) {
                                String comment = html.substring(i, endIdx);
                                i = endIdx + 1;
                                fireEvent(self, selfHash, pstate, "comment",
                                        new RuntimeScalar(comment));
                            } else {
                                pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                                return;
                            }
                        }
                    } else if (i + 1 < len && html.charAt(i) == '-' && html.charAt(i + 1) == '-') {
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
                        // In non-XML mode, treat / as boolean attribute (matches Perl HTML::Parser)
                        boolean xmlMode = pstate.get("xml_mode").getBoolean();
                        if (!xmlMode) {
                            attrs.put("/", new RuntimeScalar("/"));
                            RuntimeArray.push(attrSeq, new RuntimeScalar("/"));
                        }
                        i++;
                    }

                    // Check if tag is complete (found closing >)
                    if (i < len && html.charAt(i) == '>') {
                        i++;
                    } else if (i >= len) {
                        // Incomplete tag - buffer for next parse() call
                        pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                        return;
                    }

                    String origText = html.substring(tagStart, i);

                    fireEvent(self, selfHash, pstate, "start",
                            new RuntimeScalar(tagName),
                            attrs.createReference(),
                            attrSeq.createReference(),
                            new RuntimeScalar(origText));

                    // In XML mode, self-closing tags emit an end event
                    // In non-XML mode, self-closing / is just a boolean attribute
                    if (selfClosing && pstate.get("xml_mode").getBoolean()) {
                        fireEvent(self, selfHash, pstate, "end",
                                new RuntimeScalar(tagName),
                                new RuntimeScalar("</" + tagName + ">"));
                    }

                    // Raw text elements: <script>, <style>, <xmp>, <listing>, <plaintext>
                    // Content inside these elements is not parsed for tags
                    if (!selfClosing && (tagName.equals("script") || tagName.equals("style")
                            || tagName.equals("xmp") || tagName.equals("listing")
                            || tagName.equals("plaintext") || tagName.equals("textarea")
                            || tagName.equals("title"))) {
                        String endTag = "</" + tagName;
                        // For script content, only marked_sections (not xml_mode) enables CDATA-skipping.
                        // xml_mode alone doesn't change how script closing tags interact with CDATA sections.
                        boolean msEnabled = pstate.get("marked_sections").getBoolean();
                        int endIdx;
                        if (msEnabled) {
                            // With marked_sections, skip over <![CDATA[...]]> when looking for end tag
                            endIdx = findEndTagSkippingCdata(html, endTag, i);
                        } else {
                            endIdx = findCaseInsensitive(html, endTag, i);
                        }
                        if (endIdx >= 0) {
                            // Emit raw content as text
                            if (endIdx > i) {
                                fireEvent(self, selfHash, pstate, "text",
                                        new RuntimeScalar(html.substring(i, endIdx)));
                            }
                            // Parse and emit the end tag
                            int endTagEnd = html.indexOf('>', endIdx);
                            if (endTagEnd >= 0) {
                                endTagEnd++;
                                fireEvent(self, selfHash, pstate, "end",
                                        new RuntimeScalar(tagName),
                                        new RuntimeScalar(html.substring(endIdx, endTagEnd)));
                                i = endTagEnd;
                            } else {
                                // Incomplete end tag - buffer for next parse()
                                pstate.put("_buf", new RuntimeScalar(html.substring(endIdx)));
                                return;
                            }
                        } else {
                            // No closing tag found - buffer everything for next parse()
                            pstate.put("_buf", new RuntimeScalar(html.substring(tagStart)));
                            // Re-emit the start tag on next parse when we have the full content
                            return;
                        }
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

    /**
     * Case-insensitive search for a substring in a string, starting at fromIndex.
     * Used to find closing tags like </script> regardless of case.
     */
    private static int findCaseInsensitive(String haystack, String needle, int fromIndex) {
        int needleLen = needle.length();
        int limit = haystack.length() - needleLen;
        for (int i = fromIndex; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needleLen)) {
                // Make sure the next char after the tag name is > or whitespace or /
                int afterName = i + needleLen;
                if (afterName >= haystack.length() || haystack.charAt(afterName) == '>'
                        || Character.isWhitespace(haystack.charAt(afterName))
                        || haystack.charAt(afterName) == '/') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Find end tag for raw text elements (like </script>) while skipping CDATA sections.
     * When marked_sections is enabled, </script> inside <![CDATA[...]]> is not a real end tag.
     */
    private static int findEndTagSkippingCdata(String haystack, String endTag, int fromIndex) {
        int pos = fromIndex;
        int len = haystack.length();
        while (pos < len) {
            // Look for <![CDATA[ marker
            int cdataStart = haystack.indexOf("<![CDATA[", pos);
            // Look for end tag
            int tagIdx = findCaseInsensitive(haystack, endTag, pos);

            if (tagIdx < 0) {
                // No end tag found at all
                return -1;
            }

            if (cdataStart >= 0 && cdataStart < tagIdx) {
                // CDATA section starts before the end tag - skip past ]]>
                int cdataEnd = haystack.indexOf("]]>", cdataStart + 9);
                if (cdataEnd >= 0) {
                    pos = cdataEnd + 3;
                    continue;
                } else {
                    // Unterminated CDATA section - no end tag found
                    return -1;
                }
            }

            // End tag is not inside a CDATA section
            return tagIdx;
        }
        return -1;
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
