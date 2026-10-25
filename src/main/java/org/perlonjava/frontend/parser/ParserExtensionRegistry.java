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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            } else if (":modifiers".equals(name)) {
                if (enabled) registerModifiers(parser);
                else removeModifiers(parser);
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

    private static void registerModifiers(Parser parser) {
        for (String keyword : List.of("before", "after", "around", "augment", "override")) {
            parser.registerKeywordPlugin(keyword, configuredPlugin(new RuntimeScalar(keyword)));
        }
    }

    private static void removeModifiers(Parser parser) {
        for (String keyword : List.of("before", "after", "around", "augment", "override")) {
            parser.removeKeywordPlugin(keyword);
        }
    }

    private static FunctionParametersKeywordPlugin configuredPlugin(RuntimeScalar definition) {
        RuntimeBase value = unwrap(definition);
        String defaults;
        List<String> shift = null;
        int optionalInvocantCount = 0;
        boolean strict = true;
        boolean strictSpecified = false;
        boolean checkArgumentTypes = true;
        boolean runtime = false;
        String installSub = null;
        List<String> defaultAttributes = List.of();
        if (value instanceof RuntimeHash configuration) {
            Set<String> supportedOptions = Set.of(
                    "defaults", "shift", "invocant", "strict",
                    "check_argument_count", "check_argument_types", "attributes", "runtime",
                    "install_sub");
            for (String option : configuration.elements.keySet()) {
                if (!supportedOptions.contains(option)) {
                    throw new IllegalArgumentException("Invalid keyword property: " + option);
                }
            }
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
            RuntimeScalar invocantValue = configuration.elements.get("invocant");
            if (invocantValue != null) optionalInvocantCount = Math.max(0, invocantValue.getInt());
            RuntimeScalar strictValue = configuration.elements.get("strict");
            if (strictValue != null) {
                strict = strictValue.getBoolean();
                strictSpecified = true;
            }
            RuntimeScalar checkArgumentCountValue = configuration.elements.get("check_argument_count");
            if (checkArgumentCountValue != null) {
                strict = checkArgumentCountValue.getBoolean()
                        || (strictSpecified && strict);
                strictSpecified = true;
            }
            RuntimeScalar checkArgumentTypesValue = configuration.elements.get("check_argument_types");
            if (checkArgumentTypesValue != null) {
                checkArgumentTypes = checkArgumentTypesValue.getBoolean();
            }
            RuntimeScalar runtimeValue = configuration.elements.get("runtime");
            if (runtimeValue != null) {
                runtime = runtimeValue.getBoolean();
            }
            RuntimeScalar installSubValue = configuration.elements.get("install_sub");
            if (installSubValue != null && installSubValue.getDefinedBoolean()) {
                installSub = installSubValue.toString();
            }
            RuntimeScalar attributesValue = configuration.elements.get("attributes");
            if (attributesValue != null) {
                defaultAttributes = parseConfiguredAttributes(attributesValue.toString());
            }
        } else {
            defaults = definition.toString();
        }
        if (defaults.endsWith("_strict") || defaults.endsWith("_lax")) {
            if (!strictSpecified) strict = defaults.endsWith("_strict");
            defaults = defaults.substring(0, defaults.lastIndexOf('_'));
        }
        Set<String> modifiers = Set.of("before", "after", "around", "augment", "override");
        if (modifiers.contains(defaults)) {
            if (installSub == null) installSub = defaults;
            // Function::Parameters modifier keywords default to runtime
            // installation. Explicit configuration can still select parse-time.
            if (!(value instanceof RuntimeHash configuration)
                    || !configuration.elements.containsKey("runtime")) runtime = true;
        }
        if (!Set.of("function", "method", "classmethod", "before", "after", "around",
                "augment", "override").contains(defaults)) {
            throw new IllegalArgumentException(
                    "Unsupported Function::Parameters keyword defaults: " + defaults);
        }
        return FunctionParametersKeywordPlugin.configured(
                defaults, shift, optionalInvocantCount, strict, checkArgumentTypes,
                runtime, installSub, defaultAttributes);
    }

    private static List<String> parseConfiguredAttributes(String source) {
        String trimmed = source.trim();
        String attributePattern = "[A-Za-z_]\\w*(?:\\([^()]*\\))?";
        if (!trimmed.matches("\\s*:\\s*" + attributePattern
                + "(?:\\s*:?\\s*" + attributePattern + ")*\\s*")) {
            throw new IllegalArgumentException(
                    "\"" + source + "\" doesn't look like valid attributes");
        }
        Matcher matcher = Pattern.compile("[A-Za-z_]\\w*(?:\\([^()]*\\))?").matcher(trimmed);
        List<String> attributes = new ArrayList<>();
        while (matcher.find()) attributes.add(matcher.group());
        return List.copyOf(attributes);
    }

    private static RuntimeBase unwrap(RuntimeBase value) {
        if (value instanceof RuntimeScalar scalar && scalar.value instanceof RuntimeBase nested) {
            return nested;
        }
        return value;
    }
}
