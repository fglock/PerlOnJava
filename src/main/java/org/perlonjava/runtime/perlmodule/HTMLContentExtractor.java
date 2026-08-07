package org.perlonjava.runtime.perlmodule;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin Perl API adapter for HTML::Content::Extractor, backed by jsoup's HTML5 parser. */
public class HTMLContentExtractor extends PerlModuleBase {
    public static final String XS_VERSION = "0.17";
    private static final String CLASS = "HTML::Content::Extractor";

    public HTMLContentExtractor() { super(CLASS, false); }

    public static void initialize() {
        HTMLContentExtractor module = new HTMLContentExtractor();
        try {
            for (String method : new String[]{
                    "analyze", "get_main_text", "get_main_text_with_elements", "get_raw_text",
                    "get_main_images", "build_tree", "get_tree", "get_tree_by_element_id",
                    "get_element_by_name_in_child", "get_element_by_name_in_level", "get_element_by_name",
                    "get_stat_by_element_id", "get_tag_info_by_name", "get_child", "get_parent",
                    "get_curr_element", "get_prev_element", "get_next_element_curr_level",
                    "get_prev_element_curr_level", "set_position", "check_html_with_all_text",
                    "set_tag_ai", "set_tag_type", "set_tag_extra", "set_tag_family", "set_tag_option",
                    "set_tag_priority", "DESTROY"
            }) module.registerMethod(method, null);
            module.registerMethod("new", "new_", null);
            for (String constant : CONSTANTS.keySet()) module.registerMethod(constant, null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + CLASS, e);
        }
    }

    private static final Map<String, Integer> CONSTANTS = Map.ofEntries(
            Map.entry("AI_NULL", 0), Map.entry("AI_TEXT", 1), Map.entry("AI_LINK", 2), Map.entry("AI_IMG", 3),
            Map.entry("TYPE_TAG_NORMAL", 10), Map.entry("TYPE_TAG_BLOCK", 21), Map.entry("TYPE_TAG_INLINE", 32),
            Map.entry("TYPE_TAG_SIMPLE", 43), Map.entry("TYPE_TAG_SIMPLE_TREE", 54), Map.entry("TYPE_TAG_ONE", 65),
            Map.entry("TYPE_TAG_TEXT", 76), Map.entry("TYPE_TAG_SYS", 87), Map.entry("DEFAULT_TAG_ID", 0),
            Map.entry("EXTRA_TAG_CLOSE_IF_BLOCK", 1), Map.entry("EXTRA_TAG_CLOSE_IF_SELF", 2),
            Map.entry("EXTRA_TAG_CLOSE_IF_SELF_FAMILY", 3), Map.entry("EXTRA_TAG_CLOSE_NOW", 4),
            Map.entry("EXTRA_TAG_SIMPLE", 5), Map.entry("EXTRA_TAG_SIMPLE_TREE", 6),
            Map.entry("EXTRA_TAG_CLOSE_PRIORITY", 7), Map.entry("EXTRA_TAG_CLOSE_FAMILY_LIST", 8),
            Map.entry("EXTRA_TAG_CLOSE_PRIORITY_FAMILY", 9), Map.entry("FAMILY_H", 1),
            Map.entry("FAMILY_TABLE", 2), Map.entry("FAMILY_LIST", 3), Map.entry("FAMILY_RUBY", 4),
            Map.entry("FAMILY_SELECT", 5), Map.entry("FAMILY_HTML", 6), Map.entry("OPTION_NULL", 100),
            Map.entry("OPTION_CLEAN_TAGS", 101), Map.entry("OPTION_CLEAN_TAGS_SAVE", 102)
    );

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash state = new RuntimeHash();
        state.put("_html", new RuntimeScalar(""));
        state.put("_position", new RuntimeScalar(2));
        return ReferenceOperators.bless(state.createReference(), new RuntimeScalar(CLASS)).getList();
    }

    public static RuntimeList analyze(RuntimeArray args, int ctx) {
        state(args).put("_html", args.size() > 1 ? args.get(1) : new RuntimeScalar(""));
        state(args).put("_position", new RuntimeScalar(2));
        return new RuntimeList();
    }

    public static RuntimeList build_tree(RuntimeArray args, int ctx) {
        analyze(args, ctx);
        return scalar(1);
    }

    public static RuntimeList get_main_text(RuntimeArray args, int ctx) {
        Element selected = selectedElement(document(args));
        return scalar(selected == null ? "" : cleanText(textWithoutLinks(selected)));
    }

    public static RuntimeList get_raw_text(RuntimeArray args, int ctx) {
        Element selected = selectedElement(document(args));
        return scalar(selected == null ? "" : selected.outerHtml());
    }

    public static RuntimeList get_main_text_with_elements(RuntimeArray args, int ctx) {
        Element selected = selectedElement(document(args));
        if (selected == null) return scalar("");
        Set<String> keep = new HashSet<>();
        if (args.size() > 2 && args.get(2).getDefinedBoolean())
            for (RuntimeScalar value : args.get(2).arrayDeref().elements)
                keep.add(value.toString().toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        for (Node child : selected.childNodes()) renderSelected(child, keep, out);
        return scalar(cleanText(out.toString()));
    }

    public static RuntimeList get_main_images(RuntimeArray args, int ctx) {
        Document doc = document(args);
        Element selected = selectedElement(doc);
        RuntimeArray result = new RuntimeArray();
        if (selected != null) {
            List<Entry> flat = flatTree(doc);
            for (Entry entry : flat)
                if (entry.node instanceof Element image && image.normalName().equals("img") && isWithin(image, selected))
                    result.push(elementHash(entry).createReference());
        }
        return result.createReference().getList();
    }

    public static RuntimeList get_tree(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();
        for (Entry entry : flatTree(document(args))) result.push(elementHash(entry).createReference());
        return result.createReference().getList();
    }

    public static RuntimeList get_tree_by_element_id(RuntimeArray args, int ctx) {
        List<Entry> flat = flatTree(document(args));
        RuntimeArray result = new RuntimeArray();
        int id = args.size() > 1 ? args.get(1).getInt() : -1;
        if (id >= 0 && id < flat.size()) {
            Node root = flat.get(id).node;
            for (Entry entry : flat) if (entry.node != root && isWithin(entry.node, root))
                result.push(elementHash(entry).createReference());
        }
        return result.createReference().getList();
    }

    public static RuntimeList get_element_by_name(RuntimeArray args, int ctx) { return findByName(args, false, false); }
    public static RuntimeList get_element_by_name_in_child(RuntimeArray args, int ctx) { return findByName(args, true, false); }
    public static RuntimeList get_element_by_name_in_level(RuntimeArray args, int ctx) { return findByName(args, false, true); }

    private static RuntimeList findByName(RuntimeArray args, boolean childOnly, boolean sameLevel) {
        List<Entry> flat = flatTree(document(args));
        String name = args.size() > 1 ? args.get(1).toString().toLowerCase(Locale.ROOT) : "";
        int offset = args.size() > 2 ? args.get(2).getInt() : 0;
        int pos = state(args).get("_position").getInt();
        Entry current = pos >= 0 && pos < flat.size() ? flat.get(pos) : null;
        for (Entry entry : flat) {
            if (!entry.name.equals(name)) continue;
            if (childOnly && (current == null || entry.node.parentNode() != current.node)) continue;
            if (sameLevel && (current == null || entry.level != current.level)) continue;
            if (offset-- == 0) return elementHash(entry).createReference().getList();
        }
        return scalarUndef();
    }

    public static RuntimeList get_curr_element(RuntimeArray args, int ctx) { return navigation(args, 0, false); }
    public static RuntimeList get_parent(RuntimeArray args, int ctx) { return navigation(args, 0, true); }
    public static RuntimeList get_prev_element(RuntimeArray args, int ctx) { return navigation(args, -1, false); }
    public static RuntimeList get_next_element_curr_level(RuntimeArray args, int ctx) { return navigation(args, 1, false); }
    public static RuntimeList get_prev_element_curr_level(RuntimeArray args, int ctx) { return navigation(args, -1, false); }

    public static RuntimeList get_child(RuntimeArray args, int ctx) {
        List<Entry> flat = flatTree(document(args));
        int pos = state(args).get("_position").getInt();
        int wanted = args.size() > 1 ? args.get(1).getInt() : 0;
        if (pos >= 0 && pos < flat.size()) for (Entry entry : flat)
            if (entry.node.parentNode() == flat.get(pos).node && wanted-- == 0)
                return elementHash(entry).createReference().getList();
        return scalarUndef();
    }

    private static RuntimeList navigation(RuntimeArray args, int delta, boolean parent) {
        List<Entry> flat = flatTree(document(args));
        int pos = state(args).get("_position").getInt();
        Entry entry = pos >= 0 && pos < flat.size() ? flat.get(pos) : null;
        if (parent && entry != null) entry = find(flat, entry.node.parentNode());
        else if (delta != 0) entry = pos + delta >= 0 && pos + delta < flat.size() ? flat.get(pos + delta) : null;
        return entry == null ? scalarUndef() : elementHash(entry).createReference().getList();
    }

    public static RuntimeList set_position(RuntimeArray args, int ctx) {
        if (args.size() < 2 || !args.get(1).getDefinedBoolean()) return scalarUndef();
        int id = args.get(1).hashDeref().get("id").getInt();
        List<Entry> flat = flatTree(document(args));
        if (id < 0 || id >= flat.size()) return scalarUndef();
        state(args).put("_position", new RuntimeScalar(id));
        return elementHash(flat.get(id)).createReference().getList();
    }

    public static RuntimeList get_stat_by_element_id(RuntimeArray args, int ctx) {
        RuntimeHash result = new RuntimeHash();
        for (String key : new String[]{"count", "all", "words", "AI_TEXT", "AI_LINK", "AI_IMG", "all_AI_LINK", "all_AI_IMG"})
            result.put(key, new RuntimeScalar(0));
        return result.createReference().getList();
    }

    public static RuntimeList get_tag_info_by_name(RuntimeArray args, int ctx) {
        RuntimeHash result = new RuntimeHash();
        for (String key : new String[]{"priority", "type", "extra", "ai", "family", "option"})
            result.put(key, new RuntimeScalar(0));
        return result.createReference().getList();
    }

    public static RuntimeList check_html_with_all_text(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();
        Document doc = document(args);
        Element selected = selectedElement(doc);
        if (selected != null) {
            RuntimeHash item = new RuntimeHash();
            item.put("text", new RuntimeScalar(cleanText(textWithoutLinks(selected))));
            Entry entry = find(flatTree(doc), selected);
            item.put("element", entry == null ? new RuntimeScalar() : elementHash(entry).createReference());
            result.push(item.createReference());
        }
        return result.createReference().getList();
    }

    public static RuntimeList set_tag_ai(RuntimeArray a,int c){return scalar(1);} public static RuntimeList set_tag_type(RuntimeArray a,int c){return scalar(1);}
    public static RuntimeList set_tag_extra(RuntimeArray a,int c){return scalar(1);} public static RuntimeList set_tag_family(RuntimeArray a,int c){return scalar(1);}
    public static RuntimeList set_tag_option(RuntimeArray a,int c){return scalar(1);} public static RuntimeList set_tag_priority(RuntimeArray a,int c){return scalar(1);}
    public static RuntimeList DESTROY(RuntimeArray a,int c){return new RuntimeList();}

    public static RuntimeList AI_NULL(RuntimeArray a,int c){return constant("AI_NULL");} public static RuntimeList AI_TEXT(RuntimeArray a,int c){return constant("AI_TEXT");}
    public static RuntimeList AI_LINK(RuntimeArray a,int c){return constant("AI_LINK");} public static RuntimeList AI_IMG(RuntimeArray a,int c){return constant("AI_IMG");}
    public static RuntimeList TYPE_TAG_NORMAL(RuntimeArray a,int c){return constant("TYPE_TAG_NORMAL");} public static RuntimeList TYPE_TAG_BLOCK(RuntimeArray a,int c){return constant("TYPE_TAG_BLOCK");}
    public static RuntimeList TYPE_TAG_INLINE(RuntimeArray a,int c){return constant("TYPE_TAG_INLINE");} public static RuntimeList TYPE_TAG_SIMPLE(RuntimeArray a,int c){return constant("TYPE_TAG_SIMPLE");}
    public static RuntimeList TYPE_TAG_SIMPLE_TREE(RuntimeArray a,int c){return constant("TYPE_TAG_SIMPLE_TREE");} public static RuntimeList TYPE_TAG_ONE(RuntimeArray a,int c){return constant("TYPE_TAG_ONE");}
    public static RuntimeList TYPE_TAG_TEXT(RuntimeArray a,int c){return constant("TYPE_TAG_TEXT");} public static RuntimeList TYPE_TAG_SYS(RuntimeArray a,int c){return constant("TYPE_TAG_SYS");}
    public static RuntimeList DEFAULT_TAG_ID(RuntimeArray a,int c){return constant("DEFAULT_TAG_ID");} public static RuntimeList EXTRA_TAG_CLOSE_IF_BLOCK(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_IF_BLOCK");}
    public static RuntimeList EXTRA_TAG_CLOSE_IF_SELF(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_IF_SELF");} public static RuntimeList EXTRA_TAG_CLOSE_IF_SELF_FAMILY(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_IF_SELF_FAMILY");}
    public static RuntimeList EXTRA_TAG_CLOSE_NOW(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_NOW");} public static RuntimeList EXTRA_TAG_SIMPLE(RuntimeArray a,int c){return constant("EXTRA_TAG_SIMPLE");}
    public static RuntimeList EXTRA_TAG_SIMPLE_TREE(RuntimeArray a,int c){return constant("EXTRA_TAG_SIMPLE_TREE");} public static RuntimeList EXTRA_TAG_CLOSE_PRIORITY(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_PRIORITY");}
    public static RuntimeList EXTRA_TAG_CLOSE_FAMILY_LIST(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_FAMILY_LIST");} public static RuntimeList EXTRA_TAG_CLOSE_PRIORITY_FAMILY(RuntimeArray a,int c){return constant("EXTRA_TAG_CLOSE_PRIORITY_FAMILY");}
    public static RuntimeList FAMILY_H(RuntimeArray a,int c){return constant("FAMILY_H");} public static RuntimeList FAMILY_TABLE(RuntimeArray a,int c){return constant("FAMILY_TABLE");}
    public static RuntimeList FAMILY_LIST(RuntimeArray a,int c){return constant("FAMILY_LIST");} public static RuntimeList FAMILY_RUBY(RuntimeArray a,int c){return constant("FAMILY_RUBY");}
    public static RuntimeList FAMILY_SELECT(RuntimeArray a,int c){return constant("FAMILY_SELECT");} public static RuntimeList FAMILY_HTML(RuntimeArray a,int c){return constant("FAMILY_HTML");}
    public static RuntimeList OPTION_NULL(RuntimeArray a,int c){return constant("OPTION_NULL");} public static RuntimeList OPTION_CLEAN_TAGS(RuntimeArray a,int c){return constant("OPTION_CLEAN_TAGS");}
    public static RuntimeList OPTION_CLEAN_TAGS_SAVE(RuntimeArray a,int c){return constant("OPTION_CLEAN_TAGS_SAVE");}

    private static RuntimeHash state(RuntimeArray args) { return args.get(0).hashDeref(); }
    private static RuntimeList constant(String name) { return scalar(CONSTANTS.get(name)); }
    private static RuntimeList scalar(Object value) { return new RuntimeScalar(value).getList(); }
    private static RuntimeList scalarUndef() { return new RuntimeScalar().getList(); }

    private static Document document(RuntimeArray args) {
        Document doc = Jsoup.parse(closeRepeatedAnchors(state(args).get("_html").toString()));
        doc.outputSettings().prettyPrint(false);
        return doc;
    }

    private static List<Entry> flatTree(Document doc) {
        List<Entry> out = new ArrayList<>();
        Element html = doc.selectFirst("html");
        Element head = doc.head();
        Element body = doc.body();
        add(out, html, 0, false);
        add(out, head, 1, false);
        add(out, body, 2, false);
        for (Node child : body.childNodes()) flatten(child, 3, out);
        return normalizeLegacyTableText(out);
    }

    private static void flatten(Node node, int level, List<Entry> out) {
        if (node instanceof TextNode text && text.getWholeText().isEmpty()) return;
        if (node instanceof Element element && Set.of("select", "p").contains(element.normalName())
                && element.parent() != null && element.parent().normalName().equals("option")) level--;
        if (node instanceof Element element && element.normalName().equals("table")
                && element.parent() != null && element.parent().normalName().equals("p")) level--;
        add(out, node, level, node instanceof TextNode);
        for (Node child : node.childNodes()) flatten(child, level + 1, out);
    }

    private static final Pattern ANCHOR_TAG = Pattern.compile("(?i)</?a(?:\\s[^>]*)?>");

    /** The 2013 parser closes an open anchor when another anchor starts. */
    private static String closeRepeatedAnchors(String html) {
        Matcher matcher = ANCHOR_TAG.matcher(html);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        boolean open = false;
        while (matcher.find()) {
            out.append(html, copied, matcher.start());
            String tag = matcher.group();
            if (tag.regionMatches(true, 0, "</", 0, 2)) {
                out.append(tag);
                open = false;
            } else if (open) {
                out.append("</a>").append(tag).append("</a>");
                open = false;
            } else {
                out.append(tag);
                open = true;
            }
            copied = matcher.end();
        }
        return out.append(html, copied, html.length()).toString();
    }

    private static void add(List<Entry> out, Node node, int level, boolean text) {
        out.add(new Entry(node, text ? " " : ((Element) node).normalName(), level, out.size(), text));
    }

    /** Reproduce the old extractor's table-jail placement of invalid text nodes. */
    private static List<Entry> normalizeLegacyTableText(List<Entry> entries) {
        for (Entry original : List.copyOf(entries)) {
            if (!(original.node instanceof TextNode text)) continue;
            Element parent = text.parent();
            if (parent == null) continue;
            String parentName = parent.normalName();

            if (parentName.equals("body") && text.getWholeText().isBlank()) {
                entries.remove(original);
                continue;
            }
            if (Set.of("td", "th").contains(parentName) && text.getWholeText().isBlank()
                    && hasEarlierTableSibling(text)) {
                entries.remove(original);
                continue;
            }
            if (parentName.equals("p") && text.getWholeText().isBlank() && hasEarlierTableSibling(text)) {
                entries.remove(original);
                continue;
            }

            Element table = parentName.equals("table") ? parent : nearest(parent, "table");
            if (table == null || (!parentName.equals("table")
                    && !Set.of("thead", "tbody", "tfoot", "tr").contains(parentName))) continue;

            entries.remove(original);
            Element cell = nearest(table.parent(), "td", "th");
            int insert;
            int level;
            if (cell != null && !parentName.equals("table")) {
                Entry cellEntry = find(entries, cell);
                insert = entries.indexOf(cellEntry) + 1;
                while (insert < entries.size() && entries.get(insert).text
                        && entries.get(insert).node.parentNode() == cell) insert++;
                level = cellEntry.level + 1;
            } else {
                Entry tableEntry = find(entries, table);
                insert = parentName.equals("table") ? entries.indexOf(tableEntry) : 3;
                level = tableEntry.level;
            }
            entries.add(insert, new Entry(text, " ", level, 0, true));
        }

        List<Entry> normalized = new ArrayList<>(entries.size());
        for (Entry entry : entries)
            normalized.add(new Entry(entry.node, entry.name, entry.level, normalized.size(), entry.text));
        return normalized;
    }

    private static boolean hasEarlierTableSibling(Node node) {
        for (Node sibling = node.previousSibling(); sibling != null; sibling = sibling.previousSibling())
            if (sibling instanceof Element element && element.normalName().equals("table")) return true;
        return false;
    }

    private static Element nearest(Node node, String... names) {
        Set<String> wanted = Set.of(names);
        for (Node current = node; current != null; current = current.parentNode())
            if (current instanceof Element element && wanted.contains(element.normalName())) return element;
        return null;
    }

    private static Entry find(List<Entry> entries, Node node) {
        for (Entry entry : entries) if (entry.node == node) return entry;
        return null;
    }

    private static Element selectedElement(Document doc) {
        Element best = null;
        int score = -1;
        for (Element element : doc.select("article,main,section,div,p,body,td")) {
            int candidate = cleanText(textWithoutLinks(element)).length();
            if (candidate >= score) { best = element; score = candidate; }
        }
        return best == null ? doc.body() : best;
    }

    private static String textWithoutLinks(Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        if (node instanceof Element element && Set.of("a", "script", "style", "form", "nav").contains(element.normalName())) return "";
        StringBuilder out = new StringBuilder();
        for (Node child : node.childNodes()) out.append(textWithoutLinks(child));
        return out.toString();
    }

    private static void renderSelected(Node node, Set<String> keep, StringBuilder out) {
        if (node instanceof TextNode text) { out.append(text.getWholeText()); return; }
        Element element = (Element) node;
        boolean retained = keep.contains(element.normalName());
        if (retained) out.append(element.outerHtml(), 0, element.outerHtml().indexOf('>') + 1);
        for (Node child : node.childNodes()) renderSelected(child, keep, out);
        if (retained && !element.tag().isSelfClosing()) out.append("</").append(element.normalName()).append('>');
    }

    private static String cleanText(String text) { return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    private static boolean isWithin(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.parentNode()) if (current == ancestor) return true;
        return false;
    }

    private static RuntimeHash elementHash(Entry entry) {
        RuntimeHash hash = new RuntimeHash();
        hash.put("id", new RuntimeScalar(entry.id)); hash.put("name", new RuntimeScalar(entry.name));
        hash.put("tag_id", new RuntimeScalar(entry.text ? 0 : 1)); hash.put("level", new RuntimeScalar(entry.level));
        for (String key : new String[]{"start", "stop", "bstart", "bstop"}) hash.put(key, new RuntimeScalar(-1));
        if (entry.text) hash.put("prop", new RuntimeScalar());
        else {
            RuntimeHash attrs = new RuntimeHash();
            for (Attribute attr : ((Element) entry.node).attributes()) attrs.put(attr.getKey(), new RuntimeScalar(attr.getValue()));
            hash.put("prop", attrs.createReference());
        }
        return hash;
    }

    private record Entry(Node node, String name, int level, int id, boolean text) {}
}
