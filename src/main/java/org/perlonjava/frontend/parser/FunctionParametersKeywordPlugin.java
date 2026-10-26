package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.parser.SubroutineParser;

import java.util.List;
import java.util.Set;

/** Parses Function::Parameters declarations using core signature lowering. */
final class FunctionParametersKeywordPlugin implements ParserKeywordPlugin {
    static final FunctionParametersKeywordPlugin FUNCTION =
            new FunctionParametersKeywordPlugin(false, 0, true, true, false, null, false, List.of());
    static final FunctionParametersKeywordPlugin METHOD =
            new FunctionParametersKeywordPlugin(true, 0, true, true, false, null, false, List.of("$self"));

    private final boolean method;
    private final int optionalInvocantCount;
    private final boolean strict;
    private final boolean checkArgumentTypes;
    private final boolean runtime;
    private final String installSub;
    private final boolean requiresName;
    private final List<String> invocants;
    private final List<String> defaultAttributes;

    private FunctionParametersKeywordPlugin(
            boolean method, int optionalInvocantCount, boolean strict,
            boolean checkArgumentTypes, boolean runtime, String installSub,
            boolean requiresName, List<String> invocants) {
        this(method, optionalInvocantCount, strict, checkArgumentTypes, runtime,
                installSub, requiresName, invocants, List.of());
    }

    private FunctionParametersKeywordPlugin(
            boolean method, int optionalInvocantCount, boolean strict,
            boolean checkArgumentTypes, boolean runtime, String installSub, boolean requiresName,
            List<String> invocants,
            List<String> defaultAttributes) {
        this.method = method;
        this.optionalInvocantCount = optionalInvocantCount;
        this.strict = strict;
        this.checkArgumentTypes = checkArgumentTypes;
        this.runtime = runtime;
        this.installSub = installSub;
        this.requiresName = requiresName;
        this.invocants = List.copyOf(invocants);
        this.defaultAttributes = List.copyOf(defaultAttributes);
    }

    static FunctionParametersKeywordPlugin configured(
            String defaults, List<String> shift, int optionalInvocantCount,
            boolean strict, boolean checkArgumentTypes, boolean runtime, String installSub,
            List<String> defaultAttributes) {
        boolean method = Set.of("method", "classmethod", "before", "after", "around",
                "augment", "override").contains(defaults);
        boolean requiresName = Set.of("before", "after", "around", "augment", "override")
                .contains(defaults);
        if (method) {
            List<String> invocants = shift;
            if (invocants == null || invocants.isEmpty()) {
                invocants = "around".equals(defaults)
                        ? List.of("$orig", "$self")
                        : List.of("classmethod".equals(defaults) ? "$class" : "$self");
            }
            return new FunctionParametersKeywordPlugin(
                    true, 0, strict, checkArgumentTypes, runtime, installSub, requiresName,
                    invocants, defaultAttributes);
        }
        if (optionalInvocantCount == 0 && strict && checkArgumentTypes && !runtime && installSub == null
                && defaultAttributes.isEmpty()) return FUNCTION;
        return new FunctionParametersKeywordPlugin(
                false, optionalInvocantCount, strict, checkArgumentTypes, runtime, installSub,
                requiresName,
                List.of(), defaultAttributes);
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
        String previousInstallSub = parser.functionParametersInstallSub;
        List<String> previousAttributes = parser.functionParametersAttributes;
        boolean previousRuntime = parser.functionParametersRuntime;
        keywordToken.text = "sub";
        parser.tokenIndex++;
        parser.parsingFunctionParametersDeclaration = true;
        parser.parsingFunctionParametersMethod = method;
        parser.parsingFunctionParametersOptionalInvocant = optionalInvocantCount > 0;
        parser.functionParametersInvocantCount = method ? invocants.size() : optionalInvocantCount;
        parser.functionParametersStrict = strict;
        parser.functionParametersCheckArgumentTypes = checkArgumentTypes;
        parser.functionParametersKeyword = keyword;
        parser.functionParametersAttributes = defaultAttributes;
        parser.functionParametersRuntime = runtime;
        parser.functionParametersInstallSub = installSub;
        try {
            boolean named = TokenUtils.peek(parser).type == LexerTokenType.IDENTIFIER;
            if (requiresName && !named) {
                parser.throwError("expecting a function name");
            }
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
            Node declaration = SubroutineParser.parseSubroutineDefinition(parser, named, "our");
            if (installSub != null && !runtime) {
                SpecialBlockParser.runSpecialBlock(parser, "BEGIN", declaration);
                return new org.perlonjava.frontend.astnode.ListNode(parser.tokenIndex);
            }
            return declaration;
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
            parser.functionParametersInstallSub = previousInstallSub;
            parser.functionParametersAttributes = previousAttributes;
            parser.functionParametersRuntime = previousRuntime;
            keywordToken.text = originalText;
        }
    }

    private static boolean hasExplicitInvocant(Parser parser, int openParen) {
        if (openParen + 1 >= parser.tokens.size()
                || !Set.of("$", "@", "%").contains(parser.tokens.get(openParen + 1).text)) return false;
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
                || !strict || !checkArgumentTypes || runtime || this == FUNCTION)) {
            return parse(parser, keyword);
        }
        return null;
    }
}
