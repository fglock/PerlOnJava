package org.perlonjava.parser;

import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;

import static org.perlonjava.parser.TokenUtils.peek;

public class FileHandle {

    public static Node parseFileHandle(Parser parser) {
        boolean hasBracket = false;
        if (peek(parser).text.equals("{")) {
            TokenUtils.consume(parser);
            hasBracket = true;
        }
        LexerToken token = peek(parser);
        Node fileHandle = null;
        if (token.type == LexerTokenType.IDENTIFIER) {
            // bareword
            // Test for bareword like STDOUT, STDERR, FILE
            String name = IdentifierParser.parseSubroutineIdentifier(parser);
            if (name != null) {
                String packageName = parser.ctx.symbolTable.getCurrentPackage();
                if (name.equals("STDOUT") || name.equals("STDERR") || name.equals("STDIN")) {
                    packageName = "main";
                }
                name = NameNormalizer.normalizeVariableName(name, packageName);
                if (GlobalVariable.existsGlobalIO(name)) {
                    // FileHandle name exists
                    fileHandle = new IdentifierNode(name, parser.tokenIndex);
                }
            }
        } else if (token.text.equals("$")) {
            // variable name
            fileHandle = ParsePrimary.parsePrimary(parser);
            if (!hasBracket) {
                // assert that is not followed by infix
                String nextText = peek(parser).text;
                if (ParserTables.INFIX_OP.contains(nextText) || "{[".contains(nextText) || "->".equals(nextText)) {
                    // print $fh + 2;  # not a file handle
                    fileHandle = null;
                }
                // assert that list is not empty
                if (ListParser.looksLikeEmptyList(parser)) {
                    // print $fh;  # not a file handle
                    fileHandle = null;
                }
            }
        }
        if (hasBracket) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }
        return fileHandle;
    }
}
