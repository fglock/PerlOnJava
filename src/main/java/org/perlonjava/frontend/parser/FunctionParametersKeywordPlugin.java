package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.parser.SubroutineParser;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.MortalList;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses Function::Parameters declarations using core signature lowering. */
final class FunctionParametersKeywordPlugin implements ParserKeywordPlugin {
    static final FunctionParametersKeywordPlugin FUNCTION =
            new FunctionParametersKeywordPlugin(false, 0, true, true, false, null, false,
                    List.of(), "auto", null, true);
    static final FunctionParametersKeywordPlugin METHOD =
            new FunctionParametersKeywordPlugin(true, 0, true, true, false, null, false,
                    List.of("$self"), "auto", null, true);

    private final boolean method;
    private final int optionalInvocantCount;
    private final boolean strict;
    private final boolean checkArgumentTypes;
    private final boolean runtime;
    private final String installSub;
    private final RuntimeScalar installSubCallback;
    private final boolean requiresName;
    private final boolean prohibitsName;
    private final boolean defaultArguments;
    private final boolean types;
    private final String typeReifier;
    private final RuntimeScalar typeReifierCallback;
    private final List<String> invocants;
    private final Map<String, RuntimeScalar> invocantTypes;
    private final List<String> defaultAttributes;

    private FunctionParametersKeywordPlugin(
            boolean method, int optionalInvocantCount, boolean strict,
            boolean checkArgumentTypes, boolean runtime, String installSub,
            boolean requiresName, List<String> invocants, String typeReifier,
            RuntimeScalar typeReifierCallback, boolean types) {
        this(method, optionalInvocantCount, strict, checkArgumentTypes, runtime,
                installSub, requiresName, false, true, invocants, List.of(), typeReifier,
                typeReifierCallback, types, Map.of(), null);
    }

    private FunctionParametersKeywordPlugin(
            boolean method, int optionalInvocantCount, boolean strict,
            boolean checkArgumentTypes, boolean runtime, String installSub, boolean requiresName,
            boolean prohibitsName, boolean defaultArguments,
            List<String> invocants,
            List<String> defaultAttributes,
            String typeReifier,
            RuntimeScalar typeReifierCallback,
            boolean types,
            Map<String, RuntimeScalar> invocantTypes,
            RuntimeScalar installSubCallback) {
        this.method = method;
        this.optionalInvocantCount = optionalInvocantCount;
        this.strict = strict;
        this.checkArgumentTypes = checkArgumentTypes;
        this.runtime = runtime;
        this.installSub = installSub;
        this.installSubCallback = installSubCallback;
        this.requiresName = requiresName;
        this.prohibitsName = prohibitsName;
        this.defaultArguments = defaultArguments;
        this.types = types;
        this.typeReifier = typeReifier;
        this.typeReifierCallback = typeReifierCallback;
        this.invocants = List.copyOf(invocants);
        this.invocantTypes = Map.copyOf(invocantTypes);
        this.defaultAttributes = List.copyOf(defaultAttributes);
    }

    static FunctionParametersKeywordPlugin configured(
            String defaults, List<String> shift, int optionalInvocantCount,
            boolean strict, boolean checkArgumentTypes, boolean runtime, String installSub,
            List<String> defaultAttributes, String namePolicy, boolean defaultArguments,
            String typeReifier, RuntimeScalar typeReifierCallback, boolean types,
            Map<String, RuntimeScalar> invocantTypes, RuntimeScalar installSubCallback) {
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
                    true, 0, strict, checkArgumentTypes, runtime, installSub,
                    "required".equals(namePolicy) || requiresName,
                    "prohibited".equals(namePolicy), defaultArguments, invocants, defaultAttributes,
                    typeReifier, typeReifierCallback, types, invocantTypes, installSubCallback);
        }
        if (optionalInvocantCount == 0 && strict && checkArgumentTypes && !runtime && installSub == null
                && defaultAttributes.isEmpty() && "optional".equals(namePolicy) && defaultArguments
                && "auto".equals(typeReifier) && typeReifierCallback == null && types) {
            return FUNCTION;
        }
        return new FunctionParametersKeywordPlugin(
                false, optionalInvocantCount, strict, checkArgumentTypes, runtime, installSub,
                "required".equals(namePolicy) || requiresName,
                "prohibited".equals(namePolicy), defaultArguments,
                shift == null ? List.of() : shift, defaultAttributes,
                typeReifier, typeReifierCallback, types, invocantTypes, installSubCallback);
    }

    @Override
    public Node parse(Parser parser, String keyword) {
        int declarationIndex = parser.tokenIndex;
        LexerToken keywordToken = parser.tokens.get(declarationIndex);
        String originalText = keywordToken.text;
        int implicitInvocantInsertIndex = -1;
        int implicitMethodSignatureInsertIndex = -1;
        boolean previousFunctionParametersDeclaration = parser.parsingFunctionParametersDeclaration;
        boolean previousFunctionParametersMethod = parser.parsingFunctionParametersMethod;
        boolean previousOptionalInvocant = parser.parsingFunctionParametersOptionalInvocant;
        int previousInvocantCount = parser.functionParametersInvocantCount;
        Map<String, RuntimeScalar> previousInvocantTypes = parser.functionParametersInvocantTypeObjects;
        boolean previousStrict = parser.functionParametersStrict;
        boolean previousCheckArgumentTypes = parser.functionParametersCheckArgumentTypes;
        boolean previousTypes = parser.functionParametersTypes;
        String previousKeyword = parser.functionParametersKeyword;
        String previousInstallSub = parser.functionParametersInstallSub;
        RuntimeScalar previousInstallSubCallback = parser.functionParametersInstallSubCallback;
        String previousTypeReifier = parser.functionParametersTypeReifier;
        RuntimeScalar previousTypeReifierCallback = parser.functionParametersTypeReifierCallback;
        List<String> previousAttributes = parser.functionParametersAttributes;
        boolean previousRuntime = parser.functionParametersRuntime;
        boolean previousDefaultArguments = parser.functionParametersDefaultArguments;
        parser.tokenIndex++;
        boolean startsWithApostrophe = "'".equals(TokenUtils.peek(parser).text);
        if ("{".equals(TokenUtils.peek(parser).text) && !method) {
            parser.throwError("In " + keyword
                    + " (anon): I was expecting a parameter list, not \"{\"");
        }
        int declarationNameIndex = parser.tokenIndex;
        String candidateName = null;
        if (!"(".equals(TokenUtils.peek(parser).text) && !startsWithApostrophe) {
            candidateName = IdentifierParser.parseSubroutineIdentifier(parser);
        }
        boolean validCandidateName = isValidCandidateName(candidateName);
        parser.tokenIndex = declarationIndex;
        String afterName = parser.tokens.get(
                Whitespace.skipWhitespace(parser, declarationNameIndex, parser.tokens)).text;
        boolean anonymousMethodBlock = method && "{".equals(afterName);
        if (startsWithApostrophe || (!"(".equals(afterName)
                && !validCandidateName && !anonymousMethodBlock)) {
            parser.throwError(originalText + " expects a parameter list");
        }
        parser.tokens.set(declarationIndex, LexerToken.of(keywordToken.type, "sub"));
        parser.tokenIndex++;
        parser.parsingFunctionParametersDeclaration = true;
        parser.parsingFunctionParametersMethod = method;
        parser.parsingFunctionParametersOptionalInvocant = optionalInvocantCount > 0;
        parser.functionParametersInvocantCount = method ? invocants.size() : optionalInvocantCount;
        parser.functionParametersInvocantTypeObjects = invocantTypes;
        parser.functionParametersStrict = strict;
        parser.functionParametersCheckArgumentTypes = checkArgumentTypes;
        parser.functionParametersTypes = types;
        parser.functionParametersKeyword = keyword;
        parser.functionParametersAttributes = defaultAttributes;
        parser.functionParametersRuntime = runtime;
        parser.functionParametersDefaultArguments = defaultArguments;
        parser.functionParametersInstallSub = installSub;
        parser.functionParametersInstallSubCallback = installSubCallback;
        parser.functionParametersTypeReifier = typeReifier;
        parser.functionParametersTypeReifierCallback = typeReifierCallback;
        try {
            LexerToken declarationStart = TokenUtils.peek(parser);
            boolean named = declarationStart.type == LexerTokenType.IDENTIFIER
                    || declarationStart.text.equals("::")
                    || declarationStart.text.equals("'");
            if (named && parser.expressionParseDepth > 0) {
                parser.throwError("expecting a parameter list");
            }
            if (requiresName && !named) {
                parser.throwError("expecting a function name");
            }
            if (prohibitsName && named) parser.throwError("expecting a parameter list");
            if (method || !this.invocants.isEmpty()) {
                int openParen = parser.tokenIndex;
                while (openParen < parser.tokens.size()
                        && !"(".equals(parser.tokens.get(openParen).text)
                        && !"{".equals(parser.tokens.get(openParen).text)) {
                    openParen++;
                }
                if (openParen < parser.tokens.size()
                        && "{".equals(parser.tokens.get(openParen).text)) {
                    // Syntax::Feature::Method permits both `method name {}`
                    // and anonymous `method {}` forms. Normalize them to the
                    // same explicit implicit-invocant signature used by the
                    // Function::Parameters method provider.
                    implicitMethodSignatureInsertIndex = openParen;
                    List<String> configuredInvocants = method
                            ? invocants : this.invocants;
                    String[] signature = {"(", "$", configuredInvocants.getFirst().substring(1), ":", ")"};
                    for (int i = 0; i < signature.length; i++) {
                        LexerTokenType type = i == 2
                                ? LexerTokenType.IDENTIFIER : LexerTokenType.OPERATOR;
                        parser.tokens.add(openParen + i,
                                new LexerToken(type, signature[i]));
                    }
                } else if (openParen < parser.tokens.size()
                        && "(".equals(parser.tokens.get(openParen).text)
                        && !hasExplicitInvocant(parser, openParen)) {
                    implicitInvocantInsertIndex = openParen + 1;
                    int insertAt = implicitInvocantInsertIndex;
                    List<String> configuredInvocants = method
                            ? invocants : this.invocants;
                    for (int i = 0; i < configuredInvocants.size(); i++) {
                        String invocant = configuredInvocants.get(i);
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
                // The generated CV is only a temporary argument to the
                // compile-time installer. Complete its lifetime cleanup at
                // the end of the synthetic BEGIN, before parsing the next
                // source statement can observe stale weak references.
                MortalList.flushAboveMark();
                return new org.perlonjava.frontend.astnode.ListNode(parser.tokenIndex);
            }
            return declaration;
        } finally {
            if (implicitMethodSignatureInsertIndex >= 0) {
                for (int i = 0; i < 5; i++) {
                    parser.tokens.remove(implicitMethodSignatureInsertIndex);
                }
                parser.tokenIndex -= 5;
            }
            if (implicitInvocantInsertIndex >= 0) {
                List<String> configuredInvocants = method ? invocants : this.invocants;
                int inserted = configuredInvocants.size() * 2 + (configuredInvocants.size() - 1) + 1;
                for (int i = 0; i < inserted; i++) parser.tokens.remove(implicitInvocantInsertIndex);
                parser.tokenIndex -= inserted;
            }
            parser.parsingFunctionParametersDeclaration = previousFunctionParametersDeclaration;
            parser.parsingFunctionParametersMethod = previousFunctionParametersMethod;
            parser.parsingFunctionParametersOptionalInvocant = previousOptionalInvocant;
            parser.functionParametersInvocantCount = previousInvocantCount;
            parser.functionParametersInvocantTypeObjects = previousInvocantTypes;
            parser.functionParametersStrict = previousStrict;
            parser.functionParametersCheckArgumentTypes = previousCheckArgumentTypes;
            parser.functionParametersTypes = previousTypes;
            parser.functionParametersKeyword = previousKeyword;
            parser.functionParametersInstallSub = previousInstallSub;
            parser.functionParametersInstallSubCallback = previousInstallSubCallback;
            parser.functionParametersTypeReifier = previousTypeReifier;
            parser.functionParametersTypeReifierCallback = previousTypeReifierCallback;
            parser.functionParametersAttributes = previousAttributes;
            parser.functionParametersRuntime = previousRuntime;
            parser.functionParametersDefaultArguments = previousDefaultArguments;
            parser.tokens.set(declarationIndex, LexerToken.of(parser.tokens.get(declarationIndex).type, originalText));
        }
    }

    private static boolean hasExplicitInvocant(Parser parser, int openParen) {
        int first = openParen + 1;
        while (first < parser.tokens.size()
                && (parser.tokens.get(first).type == LexerTokenType.WHITESPACE
                || parser.tokens.get(first).type == LexerTokenType.NEWLINE)) {
            first++;
        }
        if (first >= parser.tokens.size()) return false;
        // An empty signature has no explicit invocant separator. Do not scan
        // past its closing parenthesis into following attributes, where a
        // colon could otherwise suppress insertion of the implicit invocant.
        if (")".equals(parser.tokens.get(first).text)) return false;
        for (int i = first + 1; i < parser.tokens.size(); i++) {
            String text = parser.tokens.get(i).text;
            if (":".equals(text)) return true;
            if (")".equals(text) || "{".equals(text)) return false;
        }
        return false;
    }

    private static boolean isValidCandidateName(String name) {
        if (name == null || name.isEmpty()) return false;
        String[] components = name.split("::", -1);
        int firstComponent = name.startsWith("::") ? 1 : 0;
        for (int componentIndex = firstComponent;
                componentIndex < components.length; componentIndex++) {
            String component = components[componentIndex];
            if (component.isEmpty()) return false;
            int firstCodePoint = component.codePointAt(0);
            if (!IdentifierParser.isPerlIdentifierStart(firstCodePoint)) return false;
            for (int offset = Character.charCount(firstCodePoint); offset < component.length();) {
                int codePoint = component.codePointAt(offset);
                if (!Character.isUnicodeIdentifierPart(codePoint) && codePoint != '_') return false;
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }

    @Override
    public Node parseExpression(Parser parser, String keyword) {
        int keywordIndex = parser.tokenIndex;
        parser.tokenIndex++;
        LexerToken declarationStart = TokenUtils.peek(parser);
        parser.tokenIndex = keywordIndex;
        if (declarationStart.type == LexerTokenType.IDENTIFIER
                || Set.of("(", "::", "'").contains(declarationStart.text)
                || (method && "{".equals(declarationStart.text))) {
            return parse(parser, keyword);
        }
        return null;
    }
}
