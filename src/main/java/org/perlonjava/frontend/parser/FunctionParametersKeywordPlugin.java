package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.parser.SubroutineParser;

import java.util.List;

/** Parses Function::Parameters declarations using core signature lowering. */
final class FunctionParametersKeywordPlugin implements ParserKeywordPlugin {
    static final FunctionParametersKeywordPlugin FUNCTION =
            new FunctionParametersKeywordPlugin(false, 0, true, true, List.of());
    static final FunctionParametersKeywordPlugin METHOD =
            new FunctionParametersKeywordPlugin(true, 0, true, true, List.of("$self"));

    private final boolean method;
    private final int optionalInvocantCount;
    private final boolean strict;
    private final boolean checkArgumentTypes;
    private final List<String> invocants;

    private FunctionParametersKeywordPlugin(
            boolean method, int optionalInvocantCount, boolean strict,
            boolean checkArgumentTypes, List<String> invocants) {
        this.method = method;
        this.optionalInvocantCount = optionalInvocantCount;
        this.strict = strict;
        this.checkArgumentTypes = checkArgumentTypes;
        this.invocants = List.copyOf(invocants);
    }

    static FunctionParametersKeywordPlugin configured(
            String defaults, List<String> shift, int optionalInvocantCount,
            boolean strict, boolean checkArgumentTypes) {
        boolean method = "method".equals(defaults) || "classmethod".equals(defaults);
        if (method) {
            List<String> invocants = shift;
            if (invocants == null || invocants.isEmpty()) {
                invocants = List.of("classmethod".equals(defaults) ? "$class" : "$self");
            }
            return new FunctionParametersKeywordPlugin(
                    true, 0, strict, checkArgumentTypes, invocants);
        }
        if (optionalInvocantCount == 0 && strict && checkArgumentTypes) return FUNCTION;
        return new FunctionParametersKeywordPlugin(
                false, optionalInvocantCount, strict, checkArgumentTypes, List.of());
    }

    @Override
    public Node parse(Parser parser, String keyword) {
        int declarationIndex = parser.tokenIndex;
        LexerToken keywordToken = parser.tokens.get(declarationIndex);
        String originalText = keywordToken.text;
        int implicitInvocantInsertIndex = -1;
        boolean previousFunctionParametersDeclaration = parser.parsingFunctionParametersDeclaration;
        boolean previousFunctionParametersMethod = parser.parsingFunctionParametersMethod;
        boolean previousOptionalInvocant = parser.parsingFunctionParametersOptionalInvocant;
        int previousInvocantCount = parser.functionParametersInvocantCount;
        boolean previousStrict = parser.functionParametersStrict;
        boolean previousCheckArgumentTypes = parser.functionParametersCheckArgumentTypes;
        String previousKeyword = parser.functionParametersKeyword;
        keywordToken.text = "sub";
        parser.tokenIndex++;
        parser.parsingFunctionParametersDeclaration = true;
        parser.parsingFunctionParametersMethod = method;
        parser.parsingFunctionParametersOptionalInvocant = optionalInvocantCount > 0;
        parser.functionParametersInvocantCount = method ? invocants.size() : optionalInvocantCount;
        parser.functionParametersStrict = strict;
        parser.functionParametersCheckArgumentTypes = checkArgumentTypes;
        parser.functionParametersKeyword = keyword;
        try {
            boolean named = TokenUtils.peek(parser).type == LexerTokenType.IDENTIFIER;
            if (method) {
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
                    int insertAt = implicitInvocantInsertIndex;
                    for (int i = 0; i < invocants.size(); i++) {
                        String invocant = invocants.get(i);
                        if (i > 0) {
                            parser.tokens.add(insertAt++,
                                    new LexerToken(LexerTokenType.OPERATOR, ","));
                        }
                        parser.tokens.add(insertAt++,
                                new LexerToken(LexerTokenType.OPERATOR, "$"));
                        parser.tokens.add(insertAt++,
                                new LexerToken(LexerTokenType.IDENTIFIER,
                                        invocant.startsWith("$") ? invocant.substring(1) : invocant));
                    }
                    parser.tokens.add(insertAt,
                            new LexerToken(LexerTokenType.OPERATOR, ":"));
                }
            }
            return SubroutineParser.parseSubroutineDefinition(parser, named, "our");
        } finally {
            if (implicitInvocantInsertIndex >= 0) {
                int inserted = invocants.size() * 2 + (invocants.size() - 1) + 1;
                for (int i = 0; i < inserted; i++) parser.tokens.remove(implicitInvocantInsertIndex);
                parser.tokenIndex -= inserted;
            }
            parser.parsingFunctionParametersDeclaration = previousFunctionParametersDeclaration;
            parser.parsingFunctionParametersMethod = previousFunctionParametersMethod;
            parser.parsingFunctionParametersOptionalInvocant = previousOptionalInvocant;
            parser.functionParametersInvocantCount = previousInvocantCount;
            parser.functionParametersStrict = previousStrict;
            parser.functionParametersCheckArgumentTypes = previousCheckArgumentTypes;
            parser.functionParametersKeyword = previousKeyword;
            keywordToken.text = originalText;
        }
    }

    private static boolean hasExplicitInvocant(Parser parser, int openParen) {
        if (openParen + 1 >= parser.tokens.size()
                || !"$".equals(parser.tokens.get(openParen + 1).text)) return false;
        for (int i = openParen + 2; i < parser.tokens.size(); i++) {
            String text = parser.tokens.get(i).text;
            if (":".equals(text)) return true;
            if (")".equals(text) || "{".equals(text)) return false;
        }
        return false;
    }

    @Override
    public Node parseExpression(Parser parser, String keyword) {
        int keywordIndex = parser.tokenIndex;
        parser.tokenIndex++;
        boolean anonymousDeclaration = "(".equals(TokenUtils.peek(parser).text);
        parser.tokenIndex = keywordIndex;
        if (anonymousDeclaration && (method || optionalInvocantCount > 0
                || !strict || !checkArgumentTypes || this == FUNCTION)) {
            return parse(parser, keyword);
        }
        return null;
    }
}
