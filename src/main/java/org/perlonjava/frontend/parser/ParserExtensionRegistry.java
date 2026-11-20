package org.perlonjava.frontend.parser;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import java.util.Deque;

/** Connects compile-time module imports to lexical parser keyword plugins. */
public final class ParserExtensionRegistry {
    @FunctionalInterface
    public interface ModuleProvider {
        void apply(Parser parser, boolean enabled, RuntimeList arguments);
    }

    private static final Map<String, ModuleProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Deque<Parser>> ACTIVE_PARSERS =
            ThreadLocal.withInitial(ArrayDeque::new);

    static {
        ModuleProvider functionParameters = ParserExtensionRegistry::applyFunctionParameters;
        registerModuleProvider("Function::Parameters", functionParameters);
        // GraphQL::MaybeTypeCheck delegates Function::Parameters->import::into
        // to its caller. That dynamic import is not visible to the incremental
        // parser, so mirror the resulting default lexical keyword registration.
        registerModuleProvider("GraphQL::MaybeTypeCheck",
                (parser, enabled, arguments) -> applyFunctionParameters(parser, enabled, null));
        registerModuleProvider("Syntax::Keyword::Try",
                ParserExtensionRegistry::applySyntaxKeywordTry);
        registerModuleProvider("Syntax::Feature::Method",
                ParserExtensionRegistry::applySyntaxFeatureMethod);
        registerModuleProvider("syntax",
                ParserExtensionRegistry::applySyntaxDispatcher);
        registerModuleProvider("autobox", ParserExtensionRegistry::applyAutobox);
        registerModuleProvider("autobox::Core", ParserExtensionRegistry::applyAutoboxCore);
        registerModuleProvider("Moose::Autobox", ParserExtensionRegistry::applyMooseAutobox);
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

    public static void pushActiveParser(Parser parser) {
        ACTIVE_PARSERS.get().push(parser);
    }

    public static void popActiveParser(Parser parser) {
        Deque<Parser> parsers = ACTIVE_PARSERS.get();
        if (parsers.isEmpty() || parsers.pop() != parser) {
            throw new IllegalStateException("Parser extension context stack is unbalanced");
        }
        if (parsers.isEmpty()) ACTIVE_PARSERS.remove();
    }

    /** Apply a runtime import callback to the parser currently compiling BEGIN/use. */
    public static void applyDynamicFunctionParametersImport(
            boolean enabled, RuntimeList arguments) {
        Deque<Parser> parsers = ACTIVE_PARSERS.get();
        if (!parsers.isEmpty()) {
            applyFunctionParameters(parsers.peek(), enabled, arguments);
        }
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
                                configuredPlugin(entry.getKey(), entry.getValue()));
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
            } else if (":strict".equals(name) || ":lax".equals(name)) {
                if (enabled) registerStrictnessDefaults(parser, ":strict".equals(name));
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

    private static void applySyntaxKeywordTry(
            Parser parser, boolean enabled, RuntimeList arguments) {
        if (arguments != null) {
            for (RuntimeBase argument : arguments.elements) {
                String option = unwrap(argument).toString();
                if (!option.isEmpty() && !"try".equals(option)
                        && !":experimental".equals(option)) {
                    throw new IllegalArgumentException(
                            "Syntax::Keyword::Try: unsupported import option " + option);
                }
                if (":experimental".equals(option)) {
                    parser.ctx.symbolTable.disableWarningCategory("experimental::try");
                }
            }
        }
        parser.syntaxKeywordTryAllowsUnboundCatch = enabled;
        if (enabled) parser.ctx.symbolTable.enableFeatureCategory("try");
        else parser.ctx.symbolTable.disableFeatureCategory("try");
    }

    private static void applySyntaxFeatureMethod(
            Parser parser, boolean enabled, RuntimeList arguments) {
        if (enabled) {
            parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.METHOD);
        } else {
            parser.removeKeywordPlugin("method");
        }
    }

    private static void applySyntaxDispatcher(
            Parser parser, boolean enabled, RuntimeList arguments) {
        if (arguments == null || arguments.isEmpty()) {
            if (enabled) return;
            parser.removeKeywordPlugin("method");
            return;
        }
        for (RuntimeBase argument : arguments.elements) {
            String feature = unwrap(argument).toString();
            if (!"method".equals(feature)) {
                throw new IllegalArgumentException(
                        "syntax: unsupported feature " + feature);
            }
            if (enabled) {
                parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.METHOD);
            } else {
                parser.removeKeywordPlugin("method");
            }
        }
    }

    private static final Map<String, List<String>> AUTOBOX_DEFAULTS = Map.of(
            "SCALAR", List.of("SCALAR"),
            "ARRAY", List.of("ARRAY"),
            "HASH", List.of("HASH"),
            "CODE", List.of("CODE"));
    private static final int AUTOBOX_HINT_BITS = 0x80020000;
    private static final Set<String> AUTOBOX_TYPES = Set.of(
            "UNDEF", "INTEGER", "FLOAT", "NUMBER", "STRING", "SCALAR",
            "ARRAY", "HASH", "CODE", "UNIVERSAL");

    private static void applyAutobox(
            Parser parser, boolean enabled, RuntimeList arguments) {
        Map<String, List<String>> bindings = new LinkedHashMap<>(
                parser.ctx.symbolTable.getAutoboxBindings());
        List<RuntimeBase> values = arguments == null
                ? List.of() : arguments.elements;

        if (!enabled) {
            if (values.isEmpty()) {
                bindings.clear();
            } else {
                for (RuntimeBase value : values) {
                    for (String type : autoboxTypes(unwrap(value).toString())) {
                        bindings.remove(type);
                    }
                }
            }
            parser.ctx.symbolTable.setAutoboxBindings(bindings);
            updateAutoboxHintBits(parser, bindings);
            return;
        }

        Map<String, List<String>> requested = new LinkedHashMap<>();
        RuntimeBase debugOption = null;
        if (values.isEmpty()) {
            requested.putAll(AUTOBOX_DEFAULTS);
        } else if (values.size() == 1 && unwrap(values.getFirst()) instanceof RuntimeHash hash) {
            for (var entry : hash.elements.entrySet()) {
                if ("DEBUG".equals(entry.getKey())) debugOption = entry.getValue();
                requested.put(entry.getKey(), autoboxClassList(entry.getValue()));
            }
        } else {
            if ((values.size() & 1) != 0) {
                throw new IllegalArgumentException("autobox: expected type/class pairs");
            }
            for (int i = 0; i < values.size(); i += 2) {
                String type = unwrap(values.get(i)).toString();
                if ("DEBUG".equals(type)) debugOption = values.get(i + 1);
                requested.put(type, autoboxClassList(values.get(i + 1)));
            }
        }
        requested.remove("DEBUG");
        if (requested.isEmpty()) requested.putAll(AUTOBOX_DEFAULTS);

        List<String> defaults = requested.get("DEFAULT");
        if (defaults != null) {
            for (String type : List.of("SCALAR", "ARRAY", "HASH", "CODE")) {
                if (!requested.containsKey(type)) {
                    requested.put(type, defaults.stream()
                            .map(name -> expandAutoboxNamespace(name, type)).toList());
                }
            }
            requested.remove("DEFAULT");
        }

        expandAutoboxVirtualType(requested, "SCALAR", List.of("STRING", "NUMBER"));
        expandAutoboxVirtualType(requested, "NUMBER", List.of("INTEGER", "FLOAT"));

        for (var entry : requested.entrySet()) {
            String type = entry.getKey();
            if (!AUTOBOX_TYPES.contains(type)) {
                throw new IllegalArgumentException("autobox: unrecognized type " + type);
            }
            List<String> classes = entry.getValue().stream()
                    .filter(name -> !name.isEmpty())
                    .map(name -> expandAutoboxNamespace(name, type)).toList();
            if (!classes.isEmpty()) {
                List<String> merged = new ArrayList<>(bindings.getOrDefault(type, List.of()));
                merged.addAll(classes);
                bindings.put(type, List.copyOf(new LinkedHashSet<>(merged)));
            } else {
                bindings.remove(type);
            }
        }
        parser.ctx.symbolTable.setAutoboxBindings(bindings);
        updateAutoboxHintBits(parser, bindings);
        invokeAutoboxDebug(debugOption, bindings);
    }

    /**
     * autobox::Core delegates its import to autobox with a DEFAULT namespace.
     * That dynamic import is invisible to the parser, so mirror the lexical
     * bindings here while preserving any caller overrides.
     */
    private static void applyAutoboxCore(
            Parser parser, boolean enabled, RuntimeList arguments) {
        RuntimeList forwarded = new RuntimeList();
        if (enabled) {
            forwarded.elements.add(new RuntimeScalar("DEFAULT"));
            forwarded.elements.add(new RuntimeScalar("autobox::Core::"));
        }
        if (arguments != null) forwarded.elements.addAll(arguments.elements);
        applyAutobox(parser, enabled, forwarded);
    }

    /** Moose::Autobox likewise delegates its lexical setup through import(). */
    private static void applyMooseAutobox(
            Parser parser, boolean enabled, RuntimeList arguments) {
        RuntimeList forwarded = new RuntimeList();
        if (enabled) {
            forwarded.elements.add(new RuntimeScalar("DEFAULT"));
            forwarded.elements.add(new RuntimeScalar("Moose::Autobox::"));
            forwarded.elements.add(new RuntimeScalar("UNDEF"));
            forwarded.elements.add(new RuntimeScalar("Moose::Autobox::Undef"));
        }
        if (arguments != null) forwarded.elements.addAll(arguments.elements);
        applyAutobox(parser, enabled, forwarded);
    }

    private static void updateAutoboxHintBits(Parser parser, Map<String, List<String>> bindings) {
        int strictOptions = parser.ctx.symbolTable.getStrictOptions();
        parser.ctx.symbolTable.setStrictOptions(bindings.isEmpty()
                ? strictOptions & ~AUTOBOX_HINT_BITS
                : strictOptions | AUTOBOX_HINT_BITS);
    }

    private static void invokeAutoboxDebug(
            RuntimeBase debugOption, Map<String, List<String>> bindings) {
        if (!(debugOption instanceof RuntimeScalar callback)
                || callback.type != RuntimeScalarType.CODE) return;

        RuntimeHash prettyBindings = new RuntimeHash();
        List<String> concreteTypes = List.of(
                "UNDEF", "INTEGER", "FLOAT", "STRING", "ARRAY", "HASH", "CODE");
        List<String> universalClasses = bindings.getOrDefault("UNIVERSAL", List.of());
        for (String type : concreteTypes) {
            List<String> effectiveClasses = new ArrayList<>(bindings.getOrDefault(type, List.of()));
            if (!"UNDEF".equals(type)) effectiveClasses.addAll(universalClasses);
            if (effectiveClasses.isEmpty()) continue;
            RuntimeArray classes = new RuntimeArray();
            for (String className : new LinkedHashSet<>(effectiveClasses)) {
                classes.elements.add(new RuntimeScalar(className));
            }
            prettyBindings.put(type, classes.createAnonymousReference());
        }
        RuntimeArray callbackArgs = new RuntimeArray();
        callbackArgs.elements.add(prettyBindings.createAnonymousReference());
        RuntimeCode.apply(callback, callbackArgs, RuntimeContextType.VOID);
    }

    private static List<String> autoboxTypes(String type) {
        if ("SCALAR".equals(type)) return List.of("SCALAR", "STRING", "NUMBER", "INTEGER", "FLOAT");
        if ("NUMBER".equals(type)) return List.of("NUMBER", "INTEGER", "FLOAT");
        return List.of(type);
    }

    private static void expandAutoboxVirtualType(
            Map<String, List<String>> requested, String virtualType, List<String> concreteTypes) {
        List<String> classes = requested.get(virtualType);
        if (classes == null || classes.isEmpty()) return;
        List<String> expanded = classes.stream()
                .filter(name -> !name.isEmpty())
                .map(name -> expandAutoboxNamespace(name, virtualType)).toList();
        // Keep the virtual mapping for native references to the scalar value
        // itself (for example \$value->method), while also expanding it to
        // Perl's concrete string and numeric scalar categories.
        requested.put(virtualType, expanded);
        for (String concreteType : concreteTypes) {
            List<String> combined = new ArrayList<>(requested.getOrDefault(concreteType, List.of()));
            combined.addAll(expanded);
            requested.put(concreteType, List.copyOf(new LinkedHashSet<>(combined)));
        }
    }

    private static List<String> autoboxClassList(RuntimeBase value) {
        RuntimeBase unwrapped = unwrap(value);
        if (unwrapped instanceof RuntimeArray array) {
            List<String> classes = new ArrayList<>();
            for (RuntimeBase element : array.elements) classes.add(unwrap(element).toString());
            return classes;
        }
        if (unwrapped instanceof RuntimeScalar scalar
                && scalar.type == RuntimeScalarType.UNDEF) return List.of();
        return List.of(unwrapped.toString());
    }

    private static String expandAutoboxNamespace(String name, String type) {
        return name.endsWith("::") ? name + type : name;
    }

    private static void registerDefaults(Parser parser) {
        parser.registerKeywordPlugin("fun", FunctionParametersKeywordPlugin.FUNCTION);
        parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.METHOD);
    }

    private static void registerStrictnessDefaults(Parser parser, boolean strict) {
        parser.registerKeywordPlugin("fun", FunctionParametersKeywordPlugin.configured(
                "function", null, 0, strict, true, false, null, List.of(), "optional", true,
                null, null, true, Map.of(), null));
        parser.registerKeywordPlugin("method", FunctionParametersKeywordPlugin.configured(
                "method", null, 0, strict, true, false, null, List.of(), "optional", true,
                null, null, true, Map.of(), null));
    }

    private static void removeDefaults(Parser parser) {
        parser.removeKeywordPlugin("fun");
        parser.removeKeywordPlugin("method");
    }

    private static void registerModifiers(Parser parser) {
        for (String keyword : List.of("before", "after", "around", "augment", "override")) {
            parser.registerKeywordPlugin(keyword, configuredPlugin(keyword, new RuntimeScalar(keyword)));
        }
    }

    private static void removeModifiers(Parser parser) {
        for (String keyword : List.of("before", "after", "around", "augment", "override")) {
            parser.removeKeywordPlugin(keyword);
        }
    }

    private static FunctionParametersKeywordPlugin configuredPlugin(String keyword, RuntimeScalar definition) {
        RuntimeBase value = unwrap(definition);
        String defaults;
        List<String> shift = null;
        Map<String, RuntimeScalar> shiftTypes = new LinkedHashMap<>();
        int optionalInvocantCount = 0;
        boolean strict = true;
        boolean strictSpecified = false;
        boolean checkArgumentTypes = true;
        boolean runtime = false;
        boolean defaultArguments = true;
        boolean types = true;
        String namePolicy = "optional";
        String installSub = null;
        RuntimeScalar installSubCallback = null;
        String reifyType = null;
        RuntimeScalar reifyTypeCallback = null;
        List<String> defaultAttributes = List.of();
        if (value instanceof RuntimeHash configuration) {
            Set<String> supportedOptions = Set.of(
                    "defaults", "shift", "invocant", "strict",
                    "check_argument_count", "check_argument_types", "attributes", "runtime",
                    "install_sub", "default_arguments", "name", "reify_type", "types");
            for (String option : configuration.elements.keySet()) {
                if (!supportedOptions.contains(option)) {
                    throw new IllegalArgumentException("Invalid keyword property: " + option);
                }
            }
            RuntimeBase reifyTypeValue = configuration.elements.get("reify_type");
            RuntimeBase reifyTypeBase = unwrap(reifyTypeValue);
            if (reifyTypeValue instanceof RuntimeScalar reifyMode
                    && reifyMode.type == RuntimeScalarType.CODE) {
                reifyTypeCallback = reifyMode;
            } else if (reifyTypeBase != null
                    && (!(reifyTypeBase instanceof RuntimeScalar reifyMode)
                    || !Set.of("auto", "moose").contains(reifyMode.toString()))) {
                throw new IllegalArgumentException(
                        "Invalid reify_type: expected 'auto', 'moose', or a code reference");
            }
            if (reifyTypeBase instanceof RuntimeScalar reifyTypeScalar) {
                reifyType = reifyTypeScalar.toString();
            }
            RuntimeScalar defaultValue = configuration.elements.get("defaults");
            String keywordDefaults = Set.of("method", "classmethod", "before", "after", "around",
                    "augment", "override").contains(keyword) ? keyword : "function";
            defaults = defaultValue == null ? keywordDefaults : defaultValue.toString();
            RuntimeScalar shiftValue = configuration.elements.get("shift");
            RuntimeBase shiftBase = shiftValue == null ? null : unwrap(shiftValue);
            if (shiftBase instanceof RuntimeArray names) {
                shift = new ArrayList<>();
                for (RuntimeScalar name : names.elements) {
                    RuntimeBase entry = unwrap(name);
                    if (entry instanceof RuntimeArray pair && !pair.elements.isEmpty()) {
                        String shiftName = pair.elements.getFirst().toString();
                        shift.add(shiftName);
                        if (pair.elements.size() > 1) {
                            RuntimeScalar typeObject = pair.elements.get(1);
                            if (RuntimeScalarType.blessedId(typeObject) != 0) {
                                shiftTypes.put(shiftName.startsWith("$")
                                        ? shiftName.substring(1) : shiftName, typeObject);
                            }
                        }
                    } else {
                        shift.add(name.toString());
                    }
                }
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
                if (installSubValue.type == RuntimeScalarType.CODE) {
                    installSubCallback = installSubValue;
                } else {
                    installSub = installSubValue.toString();
                }
            }
            RuntimeScalar attributesValue = configuration.elements.get("attributes");
            if (attributesValue != null) {
                defaultAttributes = parseConfiguredAttributes(attributesValue.toString());
            }
            RuntimeScalar defaultArgumentsValue = configuration.elements.get("default_arguments");
            if (defaultArgumentsValue != null) defaultArguments = defaultArgumentsValue.getBoolean();
            RuntimeScalar typesValue = configuration.elements.get("types");
            if (typesValue != null) types = typesValue.getBoolean();
            RuntimeScalar nameValue = configuration.elements.get("name");
            if (nameValue != null) {
                namePolicy = nameValue.toString();
                if (!Set.of("required", "prohibited", "optional").contains(namePolicy)) {
                    throw new IllegalArgumentException("Invalid keyword property name: " + namePolicy);
                }
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
        if (!Set.of("method", "classmethod", "before", "after", "around", "augment", "override")
                .contains(defaults) && shift != null) {
            optionalInvocantCount = shift.size();
        }
        return FunctionParametersKeywordPlugin.configured(
                defaults, shift, optionalInvocantCount, strict, checkArgumentTypes,
                runtime, installSub, defaultAttributes, namePolicy, defaultArguments,
                reifyType, reifyTypeCallback, types, shiftTypes, installSubCallback);
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
