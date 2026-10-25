package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.SubroutineParser;

/** Initial Function::Parameters {@code fun} declaration provider. */
final class FunctionParametersKeywordPlugin implements ParserKeywordPlugin {
    static final FunctionParametersKeywordPlugin INSTANCE = new FunctionParametersKeywordPlugin();

    private FunctionParametersKeywordPlugin() { }

    @Override
    public Node parse(Parser parser, String keyword) {
        int declarationIndex = parser.tokenIndex;
        LexerToken keywordToken = parser.tokens.get(declarationIndex);
        String originalText = keywordToken.text;
        int implicitInvocantInsertIndex = -1;
        boolean previousFunctionParametersDeclaration = parser.parsingFunctionParametersDeclaration;
        boolean previousFunctionParametersMethod = parser.parsingFunctionParametersMethod;
        keywordToken.text = "sub";
        parser.tokenIndex++;
        parser.parsingFunctionParametersDeclaration = true;
        parser.parsingFunctionParametersMethod = "method".equals(keyword);
        try {
            boolean named = TokenUtils.peek(parser).type
                    == org.perlonjava.frontend.lexer.LexerTokenType.IDENTIFIER;
            if (parser.parsingFunctionParametersMethod) {
                int openParen = parser.tokenIndex;
                while (openParen < parser.tokens.size()
                        && !"(".equals(parser.tokens.get(openParen).text)
                        && !"{".equals(parser.tokens.get(openParen).text)) {
                    openParen++;
                }
                if (openParen < parser.tokens.size()
                        && "(".equals(parser.tokens.get(openParen).text)
                        && !hasExplicitInvocant(parser, openParen)) {
                    implicitInvocantInsertIndex = openParen + 1;
                    parser.tokens.add(implicitInvocantInsertIndex,
                            new LexerToken(org.perlonjava.frontend.lexer.LexerTokenType.OPERATOR, "$"));
                    parser.tokens.add(implicitInvocantInsertIndex + 1,
                            new LexerToken(org.perlonjava.frontend.lexer.LexerTokenType.IDENTIFIER, "self"));
                    parser.tokens.add(implicitInvocantInsertIndex + 2,
                            new LexerToken(org.perlonjava.frontend.lexer.LexerTokenType.OPERATOR, ":"));
                }
            }
            return SubroutineParser.parseSubroutineDefinition(parser, named, "our");
        } finally {
            if (implicitInvocantInsertIndex >= 0) {
                parser.tokens.remove(implicitInvocantInsertIndex);
                parser.tokens.remove(implicitInvocantInsertIndex);
                parser.tokens.remove(implicitInvocantInsertIndex);
                parser.tokenIndex -= 3;
            }
            parser.parsingFunctionParametersDeclaration = previousFunctionParametersDeclaration;
            parser.parsingFunctionParametersMethod = previousFunctionParametersMethod;
            keywordToken.text = originalText;
        }
    }

    private static boolean hasExplicitInvocant(Parser parser, int openParen) {
        if (openParen + 3 >= parser.tokens.size()) return false;
        String sigil = parser.tokens.get(openParen + 1).text;
        String name = parser.tokens.get(openParen + 2).text;
        String colon = parser.tokens.get(openParen + 3).text;
        return "$".equals(sigil) && name.matches("[A-Za-z_][A-Za-z0-9_]*") && ":".equals(colon);
    }

    @Override
    public Node parseExpression(Parser parser, String keyword) {
        int keywordIndex = parser.tokenIndex;
        parser.tokenIndex++;
        boolean anonymousDeclaration = "(".equals(TokenUtils.peek(parser).text);
        parser.tokenIndex = keywordIndex;
        if (anonymousDeclaration && ("fun".equals(keyword) || "method".equals(keyword))) {
            return parse(parser, keyword);
        }
        return null;
    }
}
