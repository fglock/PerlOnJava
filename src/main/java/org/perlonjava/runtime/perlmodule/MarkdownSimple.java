package org.perlonjava.runtime.perlmodule;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.List;

/** Java backend for Markdown::Simple, delegated to commonmark-java. */
public class MarkdownSimple extends PerlModuleBase {
    public static final String XS_VERSION = "0.20";
    private static final List<Extension> GFM = List.of(
            TablesExtension.create(), StrikethroughExtension.create(),
            TaskListItemsExtension.create(), AutolinkExtension.create());

    public MarkdownSimple() { super("Markdown::Simple", false); }

    public static void initialize() {
        MarkdownSimple module = new MarkdownSimple();
        try {
            module.registerMethod("new", "construct", null);
            module.registerMethod("render", null);
            module.registerMethod("markdown_to_html", null);
            module.registerMethod("strip_markdown", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList construct(RuntimeArray args, int ctx) {
        RuntimeHash options = new RuntimeHash();
        if (args.size() > 1 && args.get(1).type == RuntimeScalarType.HASHREFERENCE) {
            options.setFromList(args.get(1).hashDeref().getList());
        }
        return ReferenceOperators.bless(options.createReferenceWithTrackedElements(),
                new RuntimeScalar("Markdown::Simple")).getList();
    }

    public static RuntimeList render(RuntimeArray args, int ctx) {
        return new RuntimeScalar(toHtml(args.get(1).toString(), args.get(0).hashDeref())).getList();
    }

    public static RuntimeList markdown_to_html(RuntimeArray args, int ctx) {
        RuntimeHash options = args.size() > 1 && args.get(1).type == RuntimeScalarType.HASHREFERENCE
                ? args.get(1).hashDeref() : new RuntimeHash();
        return new RuntimeScalar(toHtml(args.get(0).toString(), options)).getList();
    }

    public static RuntimeList strip_markdown(RuntimeArray args, int ctx) {
        Node document = parser(new RuntimeHash()).parse(args.get(0).toString());
        StringBuilder text = new StringBuilder();
        document.accept(new org.commonmark.node.AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Text node) { text.append(node.getLiteral()); }
            @Override public void visit(org.commonmark.node.Code node) { text.append(node.getLiteral()); }
            @Override public void visit(org.commonmark.node.SoftLineBreak node) { text.append('\n'); }
            @Override public void visit(org.commonmark.node.HardLineBreak node) { text.append('\n'); }
        });
        return new RuntimeScalar(text.toString()).getList();
    }

    private static Parser parser(RuntimeHash options) {
        return Parser.builder().extensions(gfm(options) ? GFM : List.of()).build();
    }

    private static String toHtml(String markdown, RuntimeHash options) {
        List<Extension> extensions = gfm(options) ? GFM : List.of();
        Node document = Parser.builder().extensions(extensions).build().parse(markdown);
        HtmlRenderer.Builder renderer = HtmlRenderer.builder().extensions(extensions);
        boolean highlight = !options.exists("highlight").getBoolean()
                || options.get("highlight").getBoolean();
        if (highlight) renderer.nodeRendererFactory(HighlightRenderer::new);
        if (options.exists("hard_breaks").getBoolean() && options.get("hard_breaks").getBoolean()) {
            renderer.softbreak("<br />\n");
        }
        return renderer.build().render(document);
    }

    private static boolean gfm(RuntimeHash options) {
        return !options.exists("gfm").getBoolean() || options.get("gfm").getBoolean();
    }

    private static final class HighlightRenderer implements org.commonmark.renderer.NodeRenderer {
        private final HtmlNodeRendererContext context;
        HighlightRenderer(HtmlNodeRendererContext context) { this.context = context; }
        public java.util.Set<Class<? extends Node>> getNodeTypes() {
            return java.util.Set.of(org.commonmark.node.FencedCodeBlock.class);
        }
        public void render(Node node) {
            org.commonmark.node.FencedCodeBlock block = (org.commonmark.node.FencedCodeBlock) node;
            String lang = block.getInfo() == null ? "" : block.getInfo().trim().split("\\s+")[0];
            var out = context.getWriter();
            out.raw("<pre><code" + (lang.isEmpty() ? "" : " class=\"language-" + lang + "\"") + ">");
            out.raw(highlight(block.getLiteral(), lang));
            out.raw("</code></pre>\n");
        }
        private String highlight(String source, String lang) {
            String escaped = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (lang.isEmpty()) return escaped;
            java.util.regex.Pattern token = java.util.regex.Pattern.compile(
                    lang.equals("perl")
                            ? "(\\$[A-Za-z_]\\w*)|\\b(my|our|sub|use|return|if|else|for|while|undef|null|\\d+)\\b"
                            : "\\b(int|return|const|null|let|var|function|if|else|for|while|\\d+)\\b");
            java.util.regex.Matcher m = token.matcher(escaped); StringBuffer b = new StringBuffer();
            while (m.find()) {
                String value=m.group(); String cls=value.startsWith("$")?"esh-v":value.matches("\\d+")?"esh-n":"esh-k";
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement("<span class=\""+cls+"\">"+value+"</span>"));
            }
            m.appendTail(b); return b.toString();
        }
    }
}
