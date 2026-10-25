package org.perlonjava.frontend.parser;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Connects compile-time module imports to lexical parser keyword plugins. */
public final class ParserExtensionRegistry {
    @FunctionalInterface
    public interface ModuleProvider {
        void apply(Parser parser, boolean enabled, RuntimeList arguments);
    }

    private static final Map<String, ModuleProvider> PROVIDERS = new ConcurrentHashMap<>();

    static {
        ModuleProvider functionParameters = ParserExtensionRegistry::applyFunctionParameters;
        registerModuleProvider("Function::Parameters", functionParameters);
        // GraphQL::MaybeTypeCheck delegates Function::Parameters->import::into
        // to its caller. That dynamic import is not visible to the incremental
        // parser, so mirror the resulting default lexical keyword registration.
        registerModuleProvider("GraphQL::MaybeTypeCheck",
                (parser, enabled, arguments) -> applyFunctionParameters(parser, enabled, null));
    }

    private ParserExtensionRegistry() { }

    public static void registerModuleProvider(String moduleName, ModuleProvider provider) {
        PROVIDERS.put(moduleName, provider);
    }

    public static void applyModulePragma(
            Parser parser, String moduleName, boolean enabled, RuntimeList arguments) {
        ModuleProvider provider = PROVIDERS.get(moduleName);
        if (provider != null) provider.apply(parser, enabled, arguments);
    }

    private static Set<String> requestedKeywords(RuntimeList arguments, boolean enabled) {
        Set<String> requested = new LinkedHashSet<>();
        if (arguments == null || arguments.isEmpty()) {
            requested.add("fun");
            requested.add("method");
            return requested;
        }
        for (RuntimeBase argument : arguments.elements) {
            String name = argument.toString();
            if (":std".equals(name)) {
                requested.add("fun");
                requested.add("method");
            } else if ("fun".equals(name) || "method".equals(name)) {
                requested.add(name);
            }
        }
        return requested;
    }

    private static void applyFunctionParameters(
            Parser parser, boolean enabled, RuntimeList arguments) {
        Set<String> requested = requestedKeywords(arguments, enabled);
        for (String keyword : Set.of("fun", "method")) {
            if (enabled && requested.contains(keyword)) {
                parser.registerKeywordPlugin(keyword, FunctionParametersKeywordPlugin.INSTANCE);
            } else if (!enabled && requested.contains(keyword)) {
                parser.removeKeywordPlugin(keyword);
            }
        }
    }
}
