package org.perlonjava.frontend.parser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Connects compile-time module imports to lexical parser keyword plugins. */
public final class ParserExtensionRegistry {
    @FunctionalInterface
    public interface ModuleProvider {
        void apply(Parser parser, boolean enabled);
    }

    private static final Map<String, ModuleProvider> PROVIDERS = new ConcurrentHashMap<>();

    static {
        registerModuleProvider("Function::Parameters", (parser, enabled) -> {
            if (enabled) {
                parser.registerKeywordPlugin("fun", FunctionParametersKeywordPlugin.INSTANCE);
                parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.INSTANCE);
            } else {
                parser.removeKeywordPlugin("fun");
                parser.removeKeywordPlugin("method");
            }
        });
    }

    private ParserExtensionRegistry() { }

    public static void registerModuleProvider(String moduleName, ModuleProvider provider) {
        PROVIDERS.put(moduleName, provider);
    }

    public static void applyModulePragma(Parser parser, String moduleName, boolean enabled) {
        ModuleProvider provider = PROVIDERS.get(moduleName);
        if (provider != null) provider.apply(parser, enabled);
    }
}
