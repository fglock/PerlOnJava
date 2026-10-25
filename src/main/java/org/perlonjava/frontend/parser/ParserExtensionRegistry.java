package org.perlonjava.frontend.parser;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static void applyFunctionParameters(
            Parser parser, boolean enabled, RuntimeList arguments) {
        if (!enabled && (arguments == null || arguments.isEmpty())) {
            parser.getVisibleParserKeywordPlugins().forEach((keyword, plugin) -> {
                if (plugin instanceof FunctionParametersKeywordPlugin) {
                    parser.removeKeywordPlugin(keyword);
                }
            });
            return;
        }

        if (arguments == null || arguments.isEmpty()) {
            if (enabled) registerDefaults(parser);
            return;
        }

        for (RuntimeBase argument : arguments.elements) {
            RuntimeBase value = unwrap(argument);
            if (value instanceof RuntimeHash definitions) {
                for (var entry : definitions.elements.entrySet()) {
                    if (enabled) {
                        parser.registerKeywordPlugin(entry.getKey(),
                                configuredPlugin(entry.getValue()));
                    } else {
                        parser.removeKeywordPlugin(entry.getKey());
                    }
                }
                continue;
            }

            String name = argument.toString();
            if (":std".equals(name)) {
                if (enabled) registerDefaults(parser);
                else removeDefaults(parser);
            } else if ("fun".equals(name) || "method".equals(name)) {
                if (enabled) parser.registerKeywordPlugin(name,
                        "fun".equals(name) ? FunctionParametersKeywordPlugin.FUNCTION
                                : FunctionParametersKeywordPlugin.METHOD);
                else parser.removeKeywordPlugin(name);
            } else if (!enabled && !name.isEmpty()) {
                parser.removeKeywordPlugin(name);
            }
        }
    }

    private static void registerDefaults(Parser parser) {
        parser.registerKeywordPlugin("fun", FunctionParametersKeywordPlugin.FUNCTION);
        parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.METHOD);
    }

    private static void removeDefaults(Parser parser) {
        parser.removeKeywordPlugin("fun");
        parser.removeKeywordPlugin("method");
    }

    private static FunctionParametersKeywordPlugin configuredPlugin(RuntimeScalar definition) {
        RuntimeBase value = unwrap(definition);
        String defaults;
        List<String> shift = null;
        if (value instanceof RuntimeHash configuration) {
            RuntimeScalar defaultValue = configuration.elements.get("defaults");
            defaults = defaultValue == null ? "function" : defaultValue.toString();
            RuntimeScalar shiftValue = configuration.elements.get("shift");
            RuntimeBase shiftBase = shiftValue == null ? null : unwrap(shiftValue);
            if (shiftBase instanceof RuntimeArray names) {
                shift = new ArrayList<>();
                for (RuntimeScalar name : names.elements) shift.add(name.toString());
            } else if (shiftValue != null) {
                shift = List.of(shiftValue.toString());
            }
        } else {
            defaults = definition.toString();
        }
        if (defaults.endsWith("_strict") || defaults.endsWith("_lax")) {
            defaults = defaults.substring(0, defaults.lastIndexOf('_'));
        }
        if (!Set.of("function", "method", "classmethod").contains(defaults)) {
            throw new IllegalArgumentException(
                    "Unsupported Function::Parameters keyword defaults: " + defaults);
        }
        return FunctionParametersKeywordPlugin.configured(defaults, shift);
    }

    private static RuntimeBase unwrap(RuntimeBase value) {
        if (value instanceof RuntimeScalar scalar && scalar.value instanceof RuntimeBase nested) {
            return nested;
        }
        return value;
    }
}
