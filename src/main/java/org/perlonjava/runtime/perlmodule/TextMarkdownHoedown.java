package org.perlonjava.runtime.perlmodule;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java replacement for Text::Markdown::Hoedown's native Hoedown binding. */
public class TextMarkdownHoedown extends PerlModuleBase {
    public static final String XS_VERSION = "1.03";

    private static final String MARKDOWN = "Text::Markdown::Hoedown::Markdown";
    private static final String HTML = "Text::Markdown::Hoedown::Renderer::HTML";
    private static final String HTML_TOC = "Text::Markdown::Hoedown::Renderer::HTMLTOC";
    private static final String CALLBACK = "Text::Markdown::Hoedown::Renderer::Callback";
    private static final String KIND = "_hoedown_kind";
    private static final String FLAGS = "_hoedown_flags";
    private static final String NESTING = "_hoedown_nesting";
    private static final String RENDERER = "_hoedown_renderer";

    private static final int EXT_TABLES = 1;
    private static final int EXT_AUTOLINK = 1 << 3;
    private static final int EXT_STRIKETHROUGH = 1 << 4;
    private static final int HTML_SKIP = 1;
    private static final int HTML_ESCAPE = 1 << 1;
    private static final int HTML_HARD_WRAP = 1 << 2;
    private static final int HTML_XHTML = 1 << 3;

    private static final String[] CONSTANTS = {
            "HOEDOWN_EXT_TABLES", "HOEDOWN_EXT_FENCED_CODE", "HOEDOWN_EXT_FOOTNOTES",
            "HOEDOWN_EXT_AUTOLINK", "HOEDOWN_EXT_STRIKETHROUGH", "HOEDOWN_EXT_UNDERLINE",
            "HOEDOWN_EXT_HIGHLIGHT", "HOEDOWN_EXT_QUOTE", "HOEDOWN_EXT_SUPERSCRIPT",
            "HOEDOWN_EXT_MATH", "HOEDOWN_EXT_NO_INTRA_EMPHASIS", "HOEDOWN_EXT_SPACE_HEADERS",
            "HOEDOWN_EXT_MATH_EXPLICIT", "HOEDOWN_EXT_DISABLE_INDENTED_CODE",
            "HOEDOWN_HTML_SKIP_HTML", "HOEDOWN_HTML_ESCAPE", "HOEDOWN_HTML_HARD_WRAP",
            "HOEDOWN_HTML_USE_XHTML"
    };

    public TextMarkdownHoedown() { super("Text::Markdown::Hoedown", false); }

    public static void initialize() {
        TextMarkdownHoedown module = new TextMarkdownHoedown();
        try {
            module.registerMethodInPackage(MARKDOWN, "new", "markdownNew");
            module.registerMethodInPackage(MARKDOWN, "render", "render");
            module.registerMethodInPackage(HTML, "new", "htmlNew");
            module.registerMethodInPackage(HTML, "DESTROY", "destroy");
            module.registerMethodInPackage(HTML_TOC, "new", "tocNew");
            module.registerMethodInPackage(HTML_TOC, "DESTROY", "destroy");
            module.registerMethodInPackage(CALLBACK, "new", "callbackNew");
            module.registerMethodInPackage(CALLBACK, "DESTROY", "destroy");
            module.registerConstants();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        module.defineExport("EXPORT", CONSTANTS);
    }

    private void registerConstants() throws NoSuchMethodException {
        registerMethod("HOEDOWN_EXT_TABLES", "extTables", null);
        registerMethod("HOEDOWN_EXT_FENCED_CODE", "extFencedCode", null);
        registerMethod("HOEDOWN_EXT_FOOTNOTES", "extFootnotes", null);
        registerMethod("HOEDOWN_EXT_AUTOLINK", "extAutolink", null);
        registerMethod("HOEDOWN_EXT_STRIKETHROUGH", "extStrikethrough", null);
        registerMethod("HOEDOWN_EXT_UNDERLINE", "extUnderline", null);
        registerMethod("HOEDOWN_EXT_HIGHLIGHT", "extHighlight", null);
        registerMethod("HOEDOWN_EXT_QUOTE", "extQuote", null);
        registerMethod("HOEDOWN_EXT_SUPERSCRIPT", "extSuperscript", null);
        registerMethod("HOEDOWN_EXT_MATH", "extMath", null);
        registerMethod("HOEDOWN_EXT_NO_INTRA_EMPHASIS", "extNoIntraEmphasis", null);
        registerMethod("HOEDOWN_EXT_SPACE_HEADERS", "extSpaceHeaders", null);
        registerMethod("HOEDOWN_EXT_MATH_EXPLICIT", "extMathExplicit", null);
        registerMethod("HOEDOWN_EXT_DISABLE_INDENTED_CODE", "extDisableIndentedCode", null);
        registerMethod("HOEDOWN_HTML_SKIP_HTML", "htmlSkip", null);
        registerMethod("HOEDOWN_HTML_ESCAPE", "htmlEscape", null);
        registerMethod("HOEDOWN_HTML_HARD_WRAP", "htmlHardWrap", null);
        registerMethod("HOEDOWN_HTML_USE_XHTML", "htmlXhtml", null);
    }

    public static RuntimeList markdownNew(RuntimeArray args, int ctx) {
        RuntimeHash state = new RuntimeHash();
        state.put(FLAGS, new RuntimeScalar(args.size() > 1 ? args.get(1).getInt() : 0));
        state.put(NESTING, new RuntimeScalar(args.size() > 2 ? args.get(2).getInt() : 16));
        if (args.size() > 3) state.put(RENDERER, args.get(3));
        return bless(state, MARKDOWN);
    }

    public static RuntimeList htmlNew(RuntimeArray args, int ctx) {
        RuntimeHash state = renderer("html", args.size() > 1 ? args.get(1).getInt() : 0,
                args.size() > 2 ? args.get(2).getInt() : 99);
        return bless(state, HTML);
    }

    public static RuntimeList tocNew(RuntimeArray args, int ctx) {
        RuntimeHash state = renderer("toc", 0, args.size() > 1 ? args.get(1).getInt() : 6);
        return bless(state, HTML_TOC);
    }

    public static RuntimeList callbackNew(RuntimeArray args, int ctx) {
        return bless(renderer("callback", 0, 0), CALLBACK);
    }

    public static RuntimeList destroy(RuntimeArray args, int ctx) { return new RuntimeList(); }

    public static RuntimeList render(RuntimeArray args, int ctx) {
        RuntimeHash markdown = args.get(0).hashDeref();
        RuntimeHash renderer = markdown.get(RENDERER).hashDeref();
        String source = args.size() > 1 ? args.get(1).toString() : "";
        int extensions = markdown.get(FLAGS).getInt();
        String kind = renderer.get(KIND).toString();
        String output = kind.equals("toc")
                ? renderToc(source, extensions, renderer.get(NESTING).getInt())
                : kind.equals("callback")
                    ? renderCallbacks(source, extensions, renderer)
                    : renderHtml(source, extensions, renderer.get(FLAGS).getInt(), renderer.get(NESTING).getInt());
        return new RuntimeScalar(output).getList();
    }

    private static RuntimeHash renderer(String kind, int flags, int nesting) {
        RuntimeHash state = new RuntimeHash();
        state.put(KIND, new RuntimeScalar(kind));
        state.put(FLAGS, new RuntimeScalar(flags));
        state.put(NESTING, new RuntimeScalar(nesting));
        return state;
    }

    private static RuntimeList bless(RuntimeHash state, String className) {
        return ReferenceOperators.bless(state.createReferenceWithTrackedElements(),
                new RuntimeScalar(className)).getList();
    }

    private static List<Extension> extensions(int flags) {
        List<Extension> result = new ArrayList<>();
        if ((flags & EXT_TABLES) != 0) result.add(TablesExtension.create());
        if ((flags & EXT_AUTOLINK) != 0) result.add(AutolinkExtension.create());
        if ((flags & EXT_STRIKETHROUGH) != 0) result.add(StrikethroughExtension.create());
        return result;
    }

    private static Parser parser(int flags) {
        return Parser.builder().extensions(extensions(flags)).build();
    }

    private static String renderHtml(String source, int extensionFlags, int htmlFlags, int tocNesting) {
        List<Extension> extensions = extensions(extensionFlags);
        Node document = Parser.builder().extensions(extensions).build().parse(source);
        HtmlRenderer.Builder builder = HtmlRenderer.builder().extensions(extensions);
        if ((htmlFlags & HTML_ESCAPE) != 0) builder.escapeHtml(true);
        if ((htmlFlags & HTML_SKIP) != 0) builder.omitSingleParagraphP(false).sanitizeUrls(true);
        if ((htmlFlags & HTML_HARD_WRAP) != 0) {
            builder.softbreak((htmlFlags & HTML_XHTML) != 0 ? "<br />\n" : "<br>\n");
        }
        String html = builder.build().render(document);
        if ((htmlFlags & HTML_SKIP) != 0) {
            html = html.replaceAll("(?s)<[^>]+>", "");
        }
        if (tocNesting > 0) {
            Pattern heading = Pattern.compile("<h([1-6])>(.*?)</h\\1>", Pattern.DOTALL);
            Matcher matcher = heading.matcher(html);
            StringBuffer out = new StringBuffer();
            int index = 0;
            while (matcher.find()) {
                int level = Integer.parseInt(matcher.group(1));
                String id = level <= tocNesting ? " id=\"toc_" + index++ + "\"" : "";
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        "<h" + level + id + ">" + matcher.group(2) + "</h" + level + ">"));
            }
            matcher.appendTail(out);
            html = out.toString();
        }
        return html.replaceAll("(</h[1-6]>\\n)(?=<h[1-6](?: |>|$))", "$1\n");
    }

    private static String renderToc(String source, int flags, int maxLevel) {
        Node document = parser(flags).parse(source);
        List<HeadingInfo> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override public void visit(Heading heading) {
                if (heading.getLevel() <= maxLevel) {
                    StringBuilder text = new StringBuilder();
                    heading.accept(new AbstractVisitor() {
                        @Override public void visit(Text node) { text.append(node.getLiteral()); }
                    });
                    headings.add(new HeadingInfo(heading.getLevel(), text.toString(), headings.size()));
                }
            }
        });
        if (headings.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int level = 0;
        boolean first = true;
        for (HeadingInfo heading : headings) {
            if (!first && heading.level <= level) {
                out.append("</li>\n");
                while (level > heading.level) {
                    out.append("</ul>\n</li>\n");
                    level--;
                }
            }
            while (level < heading.level) { out.append("<ul>\n"); level++; }
            out.append("<li>\n<a href=\"#toc_").append(heading.index).append("\">")
                    .append(escapeHtml(heading.text)).append("</a>\n");
            first = false;
        }
        out.append("</li>\n");
        while (level > 1) { out.append("</ul>\n</li>\n"); level--; }
        out.append("</ul>\n");
        return out.toString();
    }

    private static String renderCallbacks(String source, int flags, RuntimeHash callbacks) {
        Map<String, LinkTarget> references = new LinkedHashMap<>();
        Pattern definition = Pattern.compile("(?m)^\\s*\\[([^]]+)]\\s*:\\s*(\\S+)(?:\\s+\\\"([^\\\"]*)\\\")?\\s*$");
        Matcher definitions = definition.matcher(source);
        StringBuffer markdown = new StringBuffer();
        while (definitions.find()) {
            references.put(definitions.group(1).toLowerCase(),
                    new LinkTarget(definitions.group(2), definitions.group(3)));
            definitions.appendReplacement(markdown, "");
        }
        definitions.appendTail(markdown);

        StringBuilder out = new StringBuilder(call(callbacks, "doc_header"));
        StringBuilder paragraph = new StringBuilder();
        Pattern heading = Pattern.compile("^(#{1,6})\\s+(.*)$");
        for (String line : markdown.toString().split("\\n", -1)) {
            Matcher headingMatch = heading.matcher(line);
            if (headingMatch.matches()) {
                flushParagraph(out, paragraph, flags, callbacks, references);
                out.append(call(callbacks, "header",
                        inline(headingMatch.group(2), flags, callbacks, references),
                        Integer.toString(headingMatch.group(1).length())));
            } else if (line.isEmpty()) {
                flushParagraph(out, paragraph, flags, callbacks, references);
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(out, paragraph, flags, callbacks, references);
        out.append(call(callbacks, "doc_footer"));
        return out.toString();
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph, int flags,
                                       RuntimeHash callbacks, Map<String, LinkTarget> references) {
        if (paragraph.isEmpty()) return;
        out.append(call(callbacks, "paragraph",
                inline(paragraph.toString(), flags, callbacks, references)));
        paragraph.setLength(0);
    }

    private static String inline(String text, int flags, RuntimeHash callbacks,
                                 Map<String, LinkTarget> references) {
        StringBuilder out = new StringBuilder();
        int offset = 0;
        while (offset < text.length()) {
            String rest = text.substring(offset);
            Matcher image = Pattern.compile("^!\\[([^]]*)]\\((\\S+?)(?:\\s+\\\"([^\\\"]*)\\\")?\\)").matcher(rest);
            Matcher link = Pattern.compile("^\\[([^]]+)]\\((\\S+?)(?:\\s+\\\"([^\\\"]*)\\\")?\\)").matcher(rest);
            Matcher reference = Pattern.compile("^\\[([^]]+)]\\s*\\[([^]]+)]").matcher(rest);
            Matcher code = Pattern.compile("^`([^`]*)`").matcher(rest);
            Matcher underline = Pattern.compile("^_([^_]+)_").matcher(rest);
            Matcher rawHtml = Pattern.compile("^<[^>]+>").matcher(rest);
            Matcher autolink = Pattern.compile("^https?://[^\\s<]+", Pattern.CASE_INSENSITIVE).matcher(rest);
            if (image.find()) {
                out.append(call(callbacks, "image", image.group(2), image.group(3), image.group(1)));
                offset += image.end();
            } else if (link.find()) {
                out.append(call(callbacks, "link",
                        inline(link.group(1), flags, callbacks, references), link.group(2), link.group(3)));
                offset += link.end();
            } else if (reference.find() && references.containsKey(reference.group(2).toLowerCase())) {
                LinkTarget target = references.get(reference.group(2).toLowerCase());
                out.append(call(callbacks, "link",
                        inline(reference.group(1), flags, callbacks, references), target.url, target.title));
                offset += reference.end();
            } else if (code.find()) {
                out.append(call(callbacks, "codespan", code.group(1)));
                offset += code.end();
            } else if ((flags & (1 << 5)) != 0 && underline.find()) {
                out.append(call(callbacks, "underline",
                        inline(underline.group(1), flags, callbacks, references)));
                offset += underline.end();
            } else if (rawHtml.find()) {
                out.append(call(callbacks, "raw_html", rawHtml.group()));
                offset += rawHtml.end();
            } else if (autolink.find()) {
                out.append(call(callbacks, "autolink", autolink.group()));
                offset += autolink.end();
            } else if (rest.charAt(0) == '&') {
                out.append(call(callbacks, "entity", "&"));
                offset++;
            } else {
                int end = offset + 1;
                while (end < text.length() && "![]`_<&h".indexOf(text.charAt(end)) < 0) end++;
                out.append(call(callbacks, "normal_text", text.substring(offset, end)));
                offset = end;
            }
        }
        return out.toString();
    }

    private static String call(RuntimeHash callbacks, String name, String... values) {
        RuntimeScalar callback = callbacks.elements.get(name);
        if (callback == null || callback.type == RuntimeScalarType.UNDEF) {
            return values.length == 0 || values[0] == null ? "" : values[0];
        }
        RuntimeArray args = new RuntimeArray();
        for (String value : values) args.push(value == null ? new RuntimeScalar() : new RuntimeScalar(value));
        return RuntimeCode.apply(callback, args, RuntimeContextType.SCALAR).getFirst().toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record HeadingInfo(int level, String text, int index) {}
    private record LinkTarget(String url, String title) {}

    private static RuntimeList constant(int value) { return new RuntimeScalar(value).getList(); }
    public static RuntimeList extTables(RuntimeArray a,int c){return constant(1);}
    public static RuntimeList extFencedCode(RuntimeArray a,int c){return constant(1<<1);}
    public static RuntimeList extFootnotes(RuntimeArray a,int c){return constant(1<<2);}
    public static RuntimeList extAutolink(RuntimeArray a,int c){return constant(1<<3);}
    public static RuntimeList extStrikethrough(RuntimeArray a,int c){return constant(1<<4);}
    public static RuntimeList extUnderline(RuntimeArray a,int c){return constant(1<<5);}
    public static RuntimeList extHighlight(RuntimeArray a,int c){return constant(1<<6);}
    public static RuntimeList extQuote(RuntimeArray a,int c){return constant(1<<7);}
    public static RuntimeList extSuperscript(RuntimeArray a,int c){return constant(1<<8);}
    public static RuntimeList extMath(RuntimeArray a,int c){return constant(1<<9);}
    public static RuntimeList extNoIntraEmphasis(RuntimeArray a,int c){return constant(1<<11);}
    public static RuntimeList extSpaceHeaders(RuntimeArray a,int c){return constant(1<<12);}
    public static RuntimeList extMathExplicit(RuntimeArray a,int c){return constant(1<<13);}
    public static RuntimeList extDisableIndentedCode(RuntimeArray a,int c){return constant(1<<14);}
    public static RuntimeList htmlSkip(RuntimeArray a,int c){return constant(1);}
    public static RuntimeList htmlEscape(RuntimeArray a,int c){return constant(1<<1);}
    public static RuntimeList htmlHardWrap(RuntimeArray a,int c){return constant(1<<2);}
    public static RuntimeList htmlXhtml(RuntimeArray a,int c){return constant(1<<3);}
}
