package org.perlonjava.parser.sublanguage;

/**
 * Visitor interface for sublanguage AST nodes.
 * 
 * This enables the visitor pattern for AST traversal and transformation.
 * With our generic SublanguageASTNode approach, visitors check the nodeType
 * and handle nodes accordingly.
 * 
 * Different visitors can be implemented for different purposes:
 * - Validation visitors
 * - Transformation visitors (AST → Java regex, AST → pack format, etc.)
 * - Debug/printing visitors
 * - Optimization visitors
 */
public interface SublanguageASTVisitor {
    
    /**
     * Visit a generic sublanguage AST node.
     * Implementations should check node.getNodeType() to determine how to handle the node.
     * 
     * Common node types:
     * - Pack/Unpack: "pack_template", "pack_format", "pack_count", "pack_modifier"
     * - Regex: "regex_sequence", "regex_literal", "regex_quantifier", "regex_group", "regex_class"
     * - Sprintf: "sprintf_format", "sprintf_spec", "sprintf_literal"  
     * - Transliteration: "tr_pattern", "tr_range", "tr_literal", "tr_class", "tr_escape"
     * 
     * @param node The AST node to visit
     */
    void visit(SublanguageASTNode node);
}
