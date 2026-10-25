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
        boolean previousFunctionParametersDeclaration = parser.parsingFunctionParametersDeclaration;
        boolean previousFunctionParametersMethod = parser.parsingFunctionParametersMethod;
        keywordToken.text = "sub";
        parser.tokenIndex++;
        parser.parsingFunctionParametersDeclaration = true;
        parser.parsingFunctionParametersMethod = "method".equals(keyword);
        try {
            boolean named = TokenUtils.peek(parser).type
                    == org.perlonjava.frontend.lexer.LexerTokenType.IDENTIFIER;
            if (parser.parsingFunctionParametersMethod && !named) {
                parser.throwCleanError("Function::Parameters method declarations must be named");
            }
            return SubroutineParser.parseSubroutineDefinition(parser, named, "our");
        } finally {
            parser.parsingFunctionParametersDeclaration = previousFunctionParametersDeclaration;
            parser.parsingFunctionParametersMethod = previousFunctionParametersMethod;
            keywordToken.text = originalText;
        }
    }

    @Override
    public Node parseExpression(Parser parser, String keyword) {
        int keywordIndex = parser.tokenIndex;
        parser.tokenIndex++;
        boolean anonymousFun = "(".equals(TokenUtils.peek(parser).text);
        parser.tokenIndex = keywordIndex;
        if ("fun".equals(keyword) && anonymousFun) {
            return parse(parser, keyword);
        }
        return null;
    }
}
