package org.perlonjava.parser.sublanguage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic AST node for sublanguage parsing.
 * 
 * This single class can represent any sublanguage structure using:
 * - nodeType: The type of node (e.g., "pack_format", "regex_literal", "sprintf_spec")
 * - value: The primary value (e.g., format character, literal text, conversion spec)
 * - annotations: Additional properties (e.g., count, modifiers, flags)
 * - children: Child nodes for hierarchical structures
 * 
 * Examples:
 * 
 * Pack template "l<4":
 *   SublanguageASTNode(type="pack_template", children=[
 *     SublanguageASTNode(type="pack_format", value="l", annotations={endian="little", count=4})
 *   ])
 * 
 * Regex "a+":
 *   SublanguageASTNode(type="regex_sequence", children=[
 *     SublanguageASTNode(type="regex_literal", value="a"),
 *     SublanguageASTNode(type="regex_quantifier", value="+", annotations={min=1, max=-1})
 *   ])
 * 
 * Sprintf "%5.2f":
 *   SublanguageASTNode(type="sprintf_format", children=[
 *     SublanguageASTNode(type="sprintf_spec", value="f", annotations={width=5, precision=2})
 *   ])
 */
public class SublanguageASTNode implements SublanguageAST {
    
    private final String nodeType;
    private final String value;
    private final SublanguageToken sourceToken;
    private final List<SublanguageASTNode> children;
    private final Map<String, Object> annotations;
    
    /**
     * Create a new AST node.
     * 
     * @param nodeType The type of this node (e.g., "pack_format", "regex_literal")
     * @param value The primary value of this node (can be null)
     * @param sourceToken The source token this node was created from
     */
    public SublanguageASTNode(String nodeType, String value, SublanguageToken sourceToken) {
        this.nodeType = nodeType != null ? nodeType : "unknown";
        this.value = value;
        this.sourceToken = sourceToken;
        this.children = new ArrayList<>();
        this.annotations = new HashMap<>();
    }
    
    /**
     * Create a new AST node with no value.
     * 
     * @param nodeType The type of this node
     * @param sourceToken The source token this node was created from
     */
    public SublanguageASTNode(String nodeType, SublanguageToken sourceToken) {
        this(nodeType, null, sourceToken);
    }
    
    // ===== BASIC PROPERTIES =====
    
    public String getNodeType() {
        return nodeType;
    }
    
    public String getValue() {
        return value;
    }
    
    @Override
    public SublanguageToken getSourceToken() {
        return sourceToken;
    }
    
    // ===== CHILDREN MANAGEMENT =====
    
    public List<SublanguageASTNode> getChildren() {
        return new ArrayList<>(children);
    }
    
    public void addChild(SublanguageASTNode child) {
        if (child != null) {
            children.add(child);
        }
    }
    
    public void addChildren(List<SublanguageASTNode> children) {
        if (children != null) {
            this.children.addAll(children);
        }
    }
    
    public SublanguageASTNode getChild(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }
    
    public int getChildCount() {
        return children.size();
    }
    
    public boolean hasChildren() {
        return !children.isEmpty();
    }
    
    // ===== ANNOTATIONS MANAGEMENT =====
    
    public void setAnnotation(String key, Object value) {
        if (key != null) {
            annotations.put(key, value);
        }
    }
    
    public Object getAnnotation(String key) {
        return annotations.get(key);
    }
    
    public String getStringAnnotation(String key) {
        Object value = getAnnotation(key);
        return value != null ? value.toString() : null;
    }
    
    public Integer getIntAnnotation(String key) {
        Object value = getAnnotation(key);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public Boolean getBooleanAnnotation(String key) {
        Object value = getAnnotation(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }
    
    public boolean hasAnnotation(String key) {
        return annotations.containsKey(key);
    }
    
    public Map<String, Object> getAllAnnotations() {
        return new HashMap<>(annotations);
    }
    
    // ===== CONVENIENCE METHODS =====
    
    /**
     * Check if this node is of a specific type.
     */
    public boolean isType(String type) {
        return nodeType.equals(type);
    }
    
    /**
     * Find the first child of a specific type.
     */
    public SublanguageASTNode findChild(String nodeType) {
        for (SublanguageASTNode child : children) {
            if (child.isType(nodeType)) {
                return child;
            }
        }
        return null;
    }
    
    /**
     * Find all children of a specific type.
     */
    public List<SublanguageASTNode> findChildren(String nodeType) {
        List<SublanguageASTNode> result = new ArrayList<>();
        for (SublanguageASTNode child : children) {
            if (child.isType(nodeType)) {
                result.add(child);
            }
        }
        return result;
    }
    
    // ===== VISITOR PATTERN =====
    
    @Override
    public void accept(SublanguageASTVisitor visitor) {
        visitor.visit(this);
    }
    
    // ===== DEBUG AND STRING REPRESENTATION =====
    
    @Override
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append(nodeType);
        if (value != null) {
            sb.append("('").append(value).append("')");
        }
        if (!annotations.isEmpty()) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : annotations.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
            sb.append("}");
        }
        if (hasChildren()) {
            sb.append("[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(children.get(i).toDebugString());
            }
            sb.append("]");
        }
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return toDebugString();
    }
}
