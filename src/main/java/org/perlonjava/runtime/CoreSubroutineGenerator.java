package org.perlonjava.runtime;

import org.perlonjava.frontend.parser.ParserTables;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.Set;

/**
 * Generates CORE:: subroutine wrappers on demand for built-in operators.
 * <p>
 * When user code references {@code \&CORE::length} or similar, this class
 * creates a RuntimeCode with a PerlSubroutine lambda that calls the
 * operator's Java implementation directly. The wrapper carries the correct
 * prototype so that {@code prototype(\&CORE::length)} matches
 * {@code prototype("CORE::length")}.
 * <p>
 * See dev/design/CORE_SUBROUTINE_REFERENCES.md for full design.
 */
public class CoreSubroutineGenerator {

    // Tier 3: Keywords that have NO subroutine form.
    // defined(&CORE::X) returns false for these.
    // From coresubs.t %unsupported set.
    private static final Set<String> NO_SUB_KEYWORDS = Set.of(
            "__DATA__", "__END__", "ADJUST", "AUTOLOAD", "BEGIN", "UNITCHECK",
            "CORE", "DESTROY", "END", "INIT", "CHECK",
            "all", "and", "any", "catch", "class", "cmp", "default", "defer",
            "do", "dump", "else", "elsif", "eq", "eval", "field", "finally",
            "for", "foreach", "format", "ge", "given", "goto", "grep", "gt",
            "if", "isa", "last", "le", "local", "lt", "m", "map", "method",
            "my", "ne", "next", "no", "or", "our", "package", "print",
            "printf", "q", "qq", "qr", "qw", "qx", "redo", "require",
            "return", "s", "say", "sort", "state", "sub", "tr", "try",
            "unless", "until", "use", "when", "while", "x", "xor", "y"
    );

    // Tier 2: Functions where \&CORE::X returns a ref but $ref->() dies
    // with "&CORE::X cannot be called directly".
    private static final Set<String> BAREWORD_ONLY = Set.of(
            "chomp", "chop", "defined", "delete", "eof", "exec",
            "exists", "lstat", "split", "stat", "system",
            "truncate", "unlink"
    );

    /**
     * Generate and install a wrapper for a single CORE:: function on demand.
     *
     * @param operatorName the bare operator name (e.g. "length", "push")
     * @return true if wrapper was created or already exists, false if not applicable
     */
    public static boolean generateWrapper(String operatorName) {
        // Tier 3: no subroutine form at all
        if (NO_SUB_KEYWORDS.contains(operatorName)) {
            return false;
        }

        String fullName = "CORE::" + operatorName;

        // Already generated?
        if (GlobalVariable.isGlobalCodeRefDefined(fullName)) {
            return true;
        }

        String prototype = ParserTables.CORE_PROTOTYPES.get(operatorName);

        // Tier 2: create stub that dies when called through reference
        // Check this BEFORE null-prototype check since some bareword-only functions
        // have null prototypes (e.g. chomp, chop, defined, delete, exists)
        if (BAREWORD_ONLY.contains(operatorName)) {
            return installBarewordOnly(operatorName, prototype);
        }

        if (prototype == null && !"select".equals(operatorName)) {
            return false; // syntax-only keyword with null prototype
        }

        // Tier 1: create callable wrapper
        PerlSubroutine sub = buildSubroutine(operatorName, prototype);
        if (sub == null) {
            return false; // unsupported prototype pattern
        }

        return installWrapper(fullName, operatorName, prototype, sub);
    }

    /**
     * Install a Tier 2 wrapper that dies with the correct error when called.
     */
    private static boolean installBarewordOnly(String operatorName, String prototype) {
        String fullName = "CORE::" + operatorName;
        PerlSubroutine sub = (args, ctx) -> {
            throw new PerlCompilerException("&CORE::" + operatorName + " cannot be called directly");
        };
        return installWrapper(fullName, operatorName, prototype, sub);
    }

    /**
     * Install a RuntimeCode wrapper into GlobalVariable.globalCodeRefs.
     */
    private static boolean installWrapper(String fullName, String operatorName,
                                          String prototype, PerlSubroutine sub) {
        PerlSubroutine checkedSub = (args, ctx) -> {
            // Bareword-only CORE entries such as chomp and chop have no
            // prototype. Their generated stub owns the direct-call error.
            if (prototype != null) {
                validatePrototypeArity(operatorName, prototype, args.size());
            }
            return sub.apply(args, ctx);
        };
        RuntimeCode code = new RuntimeCode(checkedSub, prototype);
        code.packageName = "CORE";
        code.subName = operatorName;
        if ("pos".equals(operatorName) || "keys".equals(operatorName)
                || "substr".equals(operatorName)
                || "lock".equals(operatorName)) {
            code.attributes = new java.util.ArrayList<>(java.util.List.of("lvalue"));
        }
        GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
        return true;
    }

    /**
     * CORE wrappers are reached through symbolic code references, after the
     * parser has already lost the call site's prototype information.  Preserve
     * Perl's arity diagnostics before dispatching to their Java implementation.
     */
    private static void validatePrototypeArity(String name, String prototype, int actual) {
        String displayName = switch (name) {
            case "evalbytes" -> "eval \"string\"";
            case "join" -> "join or string";
            case "pos" -> "match position";
            case "prototype" -> "subroutine prototype";
            case "readline" -> "<HANDLE>";
            case "readpipe" -> "quoted execution (``, qx)";
            case "ref" -> "reference-type operator";
            case "reset" -> "symbol reset";
            default -> name;
        };
        int minimum = 0;
        int maximum = 0;
        boolean optional = false;
        boolean unlimited = false;

        for (int i = 0; i < prototype.length(); i++) {
            char token = prototype.charAt(i);
            if (token == ';') {
                optional = true;
                continue;
            }
            if (token == '@' || token == '%') {
                unlimited = true;
                continue;
            }
            if (token == '\\') {
                if (++i < prototype.length() && prototype.charAt(i) == '[') {
                    while (i < prototype.length() && prototype.charAt(i) != ']') {
                        i++;
                    }
                }
                maximum++;
                if (!optional) minimum++;
                continue;
            }
            if (token == '_') {
                // '_' supplies $_ when omitted, but accepts one explicit arg.
                maximum++;
                continue;
            }
            if (token == '$' || token == '*' || token == '&' || token == '+') {
                maximum++;
                if (!optional) minimum++;
            }
        }

        if (actual < minimum) {
            throw new PerlCompilerException("Not enough arguments for " + displayName);
        }
        if (!unlimited && actual > maximum) {
            throw new PerlCompilerException("Too many arguments for " + displayName);
        }
    }

    /**
     * Build a PerlSubroutine lambda based on the operator's prototype.
     * The lambda unpacks @_ and calls the operator's Java implementation.
     */
    private static PerlSubroutine buildSubroutine(String name, String proto) {
        if (proto == null && "select".equals(name)) {
            return buildGenericVarargs(name);
        }
        // First try exact prototype match
        PerlSubroutine result = buildFromExactPrototype(name, proto);
        if (result != null) return result;

        // Then try pattern-based matching for complex prototypes
        return buildFromPrototypePattern(name, proto);
    }

    /**
     * Handle exact prototype strings that map to known calling conventions.
     */
    private static PerlSubroutine buildFromExactPrototype(String name, String proto) {
        return switch (proto) {
            // Zero-arg: time, fork, wantarray, getlogin, getppid, ...
            case "" -> buildZeroArg(name);

            // Unary with $_ default: abs, chr, hex, int, lc, length, ord, ref, ...
            case "_" -> buildUnaryDefault(name);
            // Optional unary default: glob and similar
            case "_;" -> buildUnaryDefault(name);

            // Optional scalar: rand, chdir, exit, sleep, srand, caller, ...
            case ";$" -> buildOptionalScalar(name);

            // Two required scalars: rename, link, symlink, crypt, atan2, ...
            case "$$" -> buildBinaryScalar(name);

            // Three required scalars: setpriority, shmctl, ...
            case "$$$" -> buildTernaryScalar(name);

            // Flat list: die, warn, kill, chmod, chown, unlink, utime, ...
            case "@" -> buildFlatList(name);

            // Scalar + list: join, sprintf, pack, formline, ...
            case "$@" -> buildScalarPlusList(name);

            // Unary with optional second arg: mkdir, binmode, ...
            case "_;$" -> buildUnaryOptSecond(name);

            default -> null;
        };
    }

    /**
     * Handle prototype patterns (regex-like matching for complex prototypes).
     */
    private static PerlSubroutine buildFromPrototypePattern(String name, String proto) {
        // \@@ — push, unshift (first arg is array ref, rest are list)
        if (proto.equals("\\@@")) {
            return buildArrayRefPlusList(name);
        }

        // ;\@ — pop, shift (optional array ref arg)
        if (proto.equals(";\\@")) {
            return buildOptionalArrayRef(name);
        }

        // \@;$$@ — splice
        if (proto.equals("\\@;$$@")) {
            return buildArrayRefPlusList(name);
        }

        // Filehandle + optional args: *;$ (binmode), *$$ (seek), etc.
        // These have a * for the filehandle slot — treat as generic varargs
        if (proto.startsWith("*") || proto.startsWith(";*")) {
            return buildGenericVarargs(name);
        }

        // $$;$ $$;$$ — index, rindex, substr — generic varargs
        if (proto.matches("^\\$+;\\$+$")) {
            return buildGenericVarargs(name);
        }

        // \[%@] — keys, values, each — takes hash or array ref
        if (proto.equals("\\[%@]")) {
            return buildGenericVarargs(name);
        }

        // Fall through: try generic varargs for any remaining prototype
        return buildGenericVarargs(name);
    }

    // ---------------------------------------------------------------
    // Builder methods for each prototype pattern
    // ---------------------------------------------------------------

    /**
     * Zero-arg operators: time(), fork(), wantarray(), getlogin(), etc.
     */
    private static PerlSubroutine buildZeroArg(String name) {
        // Many zero-arg ops use (int ctx, RuntimeBase... args) signature
        // Some like time() use () signature — handle by name
        return switch (name) {
            case "time" -> (args, ctx) ->
                    Time.time().getList();
            case "times" -> (args, ctx) ->
                    Time.times(ctx);
            case "wait" -> (args, ctx) ->
                    WaitpidOperator.waitForChild().getList();
            case "wantarray" -> (args, ctx) ->
                    Operator.wantarray(ctx).getList();
            case "break" -> (args, ctx) -> {
                CallerStack.CallerInfo callSite = CallerStack.peek(0);
                String file = callSite == null ? "(eval)" : callSite.filename();
                int line = callSite == null ? 0 : callSite.line();
                return new RuntimeControlFlowList(
                        ControlFlowType.LAST, null, file, line, null, "break");
            };
            case "continue" -> (args, ctx) -> {
                CallerStack.CallerInfo callSite = CallerStack.peek(0);
                String file = callSite == null ? "(eval)" : callSite.filename();
                int line = callSite == null ? 0 : callSite.line();
                return new RuntimeControlFlowList(
                        ControlFlowType.NEXT, null, file, line, null, "continue");
            };
            case "__SUB__" -> (args, ctx) -> {
                // A CORE::__SUB__ wrapper is itself a RuntimeCode frame, so
                // the Perl subroutine whose identity is requested is its
                // caller, not the wrapper's own CV. JVM-generated wrappers
                // are invoked without an extra active-code frame, so their
                // caller is already at depth zero.
                RuntimeCode caller = RuntimeCode.getActiveCodeAt(1);
                if (caller == null || caller.__SUB__ == null) {
                    caller = RuntimeCode.getActiveCodeAt(0);
                }
                RuntimeScalar self = caller == null ? null : caller.__SUB__;
                if (self == null) {
                    self = RuntimeCode.getJvmSelfReference();
                }
                return RuntimeCode.selfReferenceMaybeNull(self).getList();
            };
            case "fork" -> (args, ctx) ->
                    SystemOperator.fork(ctx).getList();
            default -> (args, ctx) ->
                    callVarargs(name, ctx, args);
        };
    }

    /**
     * Unary with $_ default (prototype "_"): abs, chr, hex, int, length, ...
     */
    private static PerlSubroutine buildUnaryDefault(String name) {
        if ("glob".equals(name)) {
            int globId = ScalarGlobOperator.allocateId();
            return (args, ctx) -> {
                RuntimeScalar arg = args.size() > 0
                        ? args.get(0) : GlobalVariable.getGlobalVariable("main::_");
                return ScalarGlobOperator.evaluate(globId, arg, ctx).getList();
            };
        }
        return (args, ctx) -> {
            RuntimeScalar arg = args.size() > 0 ? args.get(0) : GlobalVariable.getGlobalVariable("main::_");
            if ("evalbytes".equals(name)) {
                return RuntimeCode.evalbytesCodeReference(arg, args, ctx);
            }
            if ("not".equals(name)) {
                return MathOperators.not(arg).getList();
            }
            return callUnary(name, arg);
        };
    }

    /**
     * Optional scalar (prototype ";$"): rand, chdir, exit, sleep, caller, ...
     */
    private static PerlSubroutine buildOptionalScalar(String name) {
        if ("umask".equals(name)) {
            return (args, ctx) -> UmaskOperator.umask(ctx, args.toArray(new RuntimeBase[0])).getList();
        }
        // Some ;$ functions need special dispatch
        if ("caller".equals(name)) {
            return (args, ctx) -> RuntimeCode.caller(args.getList(), ctx);
        }
        if ("reset".equals(name)) {
            return (args, ctx) -> Operator.reset(args.getList(), ctx).getList();
        }
        if ("gmtime".equals(name)) {
            return (args, ctx) -> Time.gmtime(new RuntimeList(args), ctx);
        }
        if ("localtime".equals(name)) {
            return (args, ctx) -> Time.localtime(new RuntimeList(args), ctx);
        }
        return (args, ctx) -> {
            RuntimeScalar arg = args.size() > 0 ? args.get(0) : new RuntimeScalar();
            return callUnary(name, arg);
        };
    }

    /**
     * Two required scalars (prototype "$$"): rename, link, crypt, atan2, ...
     */
    private static PerlSubroutine buildBinaryScalar(String name) {
        return (args, ctx) -> {
            RuntimeScalar a = args.get(0);
            RuntimeScalar b = args.get(1);
            return callBinary(name, a, b);
        };
    }

    /**
     * Three required scalars (prototype "$$$"): setpriority, ...
     */
    private static PerlSubroutine buildTernaryScalar(String name) {
        return (args, ctx) ->
                callVarargs(name, ctx, args);
    }

    /**
     * Flat list (prototype "@"): die, warn, kill, chmod, chown, ...
     */
    private static PerlSubroutine buildFlatList(String name) {
        return (args, ctx) ->
                callVarargs(name, ctx, args);
    }

    /**
     * Scalar + list (prototype "$@"): join, sprintf, pack, ...
     */
    private static PerlSubroutine buildScalarPlusList(String name) {
        return (args, ctx) ->
                callVarargs(name, ctx, args);
    }

    /**
     * Unary with optional second arg (prototype "_;$"): mkdir, ...
     */
    private static PerlSubroutine buildUnaryOptSecond(String name) {
        return (args, ctx) ->
                callVarargs(name, ctx, args);
    }

    /**
     * Array ref + list (prototype "\@@"): push, unshift, splice.
     * The first arg is an array reference, rest are values.
     */
    private static PerlSubroutine buildArrayRefPlusList(String name) {
        return (args, ctx) -> {
            if (args.size() == 0) {
                throw new PerlCompilerException("Not enough arguments for CORE::" + name);
            }
            // First arg is an array reference — dereference it
            RuntimeScalar arrayRef = args.get(0);
            if (arrayRef.type != RuntimeScalarType.ARRAYREFERENCE) {
                throw new PerlCompilerException(
                        "Type of arg 1 to &CORE::" + name + " must be array reference");
            }
            RuntimeArray targetArray = arrayRef.arrayDeref();

            // Build list of remaining args
            RuntimeArray restArgs = new RuntimeArray();
            for (int i = 1; i < args.size(); i++) {
                restArgs.push(args.get(i));
            }

            return switch (name) {
                case "push" -> RuntimeArray.push(targetArray, restArgs).getList();
                case "unshift" -> RuntimeArray.unshift(targetArray, restArgs).getList();
                case "splice" -> {
                    // splice(array, offset, length, list...)
                    yield Operator.splice(targetArray, new RuntimeList(restArgs.elements), ctx);
                }
                default -> throw new PerlCompilerException("&CORE::" + name + " not yet supported as subroutine reference");
            };
        };
    }

    /**
     * Optional array ref (prototype ";\@"): pop, shift.
     */
    private static PerlSubroutine buildOptionalArrayRef(String name) {
        return (args, ctx) -> {
            RuntimeArray targetArray;
            if (args.size() > 0) {
                RuntimeScalar arrayRef = args.get(0);
                if (arrayRef.type != RuntimeScalarType.ARRAYREFERENCE) {
                    throw new PerlCompilerException(
                            "Type of arg 1 to &CORE::" + name + " must be array reference");
                }
                targetArray = arrayRef.arrayDeref();
            } else {
                // The builtin uses the caller's @_ when called inside a sub,
                // and @ARGV at file scope. The wrapper's own @_ is always empty
                // for the no-argument form and must not be used as the target.
                targetArray = RuntimeCode.getCallerArgs();
                // A top-level call still has the builtin's own argument frame,
                // so getCallerArgs() can return the script frame's empty args.
                // Use the Perl-visible caller frame because interpreter wrapper
                // frames do not line up with the raw hasargs stack depth.
                boolean callerIsSubroutine = !RuntimeCode
                        .caller(new RuntimeList(), RuntimeContextType.SCALAR)
                        .elements.isEmpty();
                if (targetArray == null || !callerIsSubroutine) {
                    targetArray = GlobalVariable.getGlobalArray("main::ARGV");
                }
            }
            return switch (name) {
                case "pop" -> RuntimeArray.pop(targetArray).getList();
                case "shift" -> RuntimeArray.shift(targetArray).getList();
                default -> throw new PerlCompilerException("&CORE::" + name + " not yet supported");
            };
        };
    }

    /**
     * Generic varargs — passes all args through to the (int, RuntimeBase...) signature.
     */
    private static PerlSubroutine buildGenericVarargs(String name) {
        return (args, ctx) ->
                callVarargs(name, ctx, args);
    }

    // ---------------------------------------------------------------
    // Dispatch methods that call the actual Java operator implementations
    // ---------------------------------------------------------------

    /**
     * Call a unary operator: f(RuntimeScalar) -> RuntimeScalar.
     * Uses OperatorHandler to find the correct Java method.
     */
    private static RuntimeList callUnary(String name, RuntimeScalar arg) {
        return switch (name) {
            case "abs" -> MathOperators.abs(arg).getList();
            case "alarm" -> Time.alarm(0, new RuntimeBase[]{arg}).getList();
            case "chr" -> StringOperators.chr(arg).getList();
            case "chroot" -> throw new PerlCompilerException("&CORE::chroot not yet implemented");
            case "cos" -> MathOperators.cos(arg).getList();
            case "chdir" -> Directory.chdir(arg).getList();
            case "pos" -> (arg.type == RuntimeScalarType.REFERENCE ? arg.scalarDeref() : arg)
                    .pos().getList();
            case "scalar" -> arg.getList();
            case "prototype" -> {
                RuntimeCode caller = RuntimeCode.getActiveCodeAt(1);
                String packageName = caller == null || caller.packageName == null
                        ? "main" : caller.packageName;
                yield RuntimeCode.prototype(arg, packageName).getList();
            }
            case "evalbytes" -> throw new IllegalStateException("evalbytes must be dispatched with call context");
            case "exit" -> WarnDie.exit(arg).getList();
            case "exp" -> MathOperators.exp(arg).getList();
            case "fc" -> RuntimeCode.featureFlagsContain(
                    org.perlonjava.runtime.FeatureFlagsRegistry.getCallerFeatureFlagsAtFrame(0),
                    "unicode_strings")
                    ? StringOperators.fcUnicode(arg).getList()
                    : StringOperators.fc(arg).getList();
            case "hex" -> ScalarOperators.hex(arg).getList();
            case "int" -> MathOperators.integer(arg).getList();
            case "lc" -> StringOperators.lc(arg).getList();
            case "lcfirst" -> StringOperators.lcfirst(arg).getList();
            case "length" -> StringOperators.length(arg).getList();
            case "log" -> MathOperators.log(arg).getList();
            case "oct" -> ScalarOperators.oct(arg).getList();
            case "ord" -> ScalarOperators.ord(arg).getList();
            case "quotemeta" -> StringOperators.quotemeta(arg).getList();
            case "rand" -> Random.rand(arg).getList();
            case "readlink" -> Operator.readlink(0, arg).getList();
            case "ref" -> ReferenceOperators.ref(arg).getList();
            case "rmdir" -> Directory.rmdir(arg).getList();
            case "reset" -> Operator.reset(new RuntimeList(arg), RuntimeContextType.SCALAR).getList();
            case "sin" -> MathOperators.sin(arg).getList();
            case "sleep" -> Time.sleep(arg).getList();
            case "sqrt" -> MathOperators.sqrt(arg).getList();
            case "srand" -> Random.srand(arg).getList();
            case "study" -> {
                arg.study();
                yield new RuntimeScalar(1).getList();
            }
            case "uc" -> StringOperators.uc(arg).getList();
            case "ucfirst" -> StringOperators.ucfirst(arg).getList();
            default ->
                    throw new PerlCompilerException("&CORE::" + name + " not yet supported as subroutine reference");
        };
    }

    /**
     * Call a binary operator: f(RuntimeScalar, RuntimeScalar) -> RuntimeScalar.
     */
    private static RuntimeList callBinary(String name, RuntimeScalar a, RuntimeScalar b) {
        return switch (name) {
            case "atan2" -> MathOperators.atan2(a, b).getList();
            case "crypt" -> Crypt.crypt(new RuntimeList(new RuntimeArray(a, b))).getList();
            case "link" -> org.perlonjava.runtime.nativ.NativeUtils.link(0, a, b).getList();
            case "rename" -> Operator.rename(0, a, b).getList();
            case "symlink" -> org.perlonjava.runtime.nativ.NativeUtils.symlink(0, a, b).getList();
            default ->
                    throw new PerlCompilerException("&CORE::" + name + " not yet supported as subroutine reference");
        };
    }

    /**
     * Call a varargs operator: f(int ctx, RuntimeBase... args).
     * Converts RuntimeArray to RuntimeBase[] for the operator call.
     */
    private static RuntimeList callVarargs(String name, int ctx, RuntimeArray args) {
        // Build RuntimeBase[] from args
        RuntimeBase[] argsArray = new RuntimeBase[args.size()];
        for (int i = 0; i < args.size(); i++) {
            argsArray[i] = args.get(i);
        }
        return callVarargsArray(name, ctx, argsArray);
    }

    /**
     * Dispatch to the correct operator using (int ctx, RuntimeBase...) signature.
     */
    private static RuntimeList callVarargsArray(String name, int ctx, RuntimeBase[] args) {
        return switch (name) {
            // I/O operators
            case "open" -> IOOperator.open(ctx, args).getList();
            case "binmode" -> IOOperator.binmode(ctx, args).getList();
            case "close" -> {
                RuntimeScalar result = IOOperator.close(ctx, args);
                // Perl's false close result is the empty string, including
                // when the runtime's generic I/O path reports it as undef.
                yield result.getBoolean() ? result.getList() : new RuntimeScalar("").getList();
            }
            case "fileno" -> IOOperator.fileno(ctx, args).getList();
            case "flock" -> IOOperator.flock(ctx, args).getList();
            case "fcntl" -> IOOperator.fcntl(ctx, args).getList();
            case "ioctl" -> IOOperator.ioctl(ctx, args).getList();
            case "truncate" -> IOOperator.truncate(ctx, args).getList();
            case "sysread" -> {
                if (args.length >= 2 && !isPlainScalarReference(args[1])) {
                    throw new PerlCompilerException(
                            "Type of arg 2 to &CORE::sysread must be scalar reference");
                }
                yield IOOperator.sysread(ctx, args).getList();
            }
            case "syswrite" -> IOOperator.syswrite(ctx, args).getList();
            case "sysopen" -> IOOperator.sysopen(ctx, args).getList();
            case "socket" -> IOOperator.socket(ctx, args).getList();
            case "bind" -> IOOperator.bind(ctx, args).getList();
            case "connect" -> IOOperator.connect(ctx, args).getList();
            case "listen" -> IOOperator.listen(ctx, args).getList();
            case "accept" -> IOOperator.accept(ctx, args).getList();
            case "pipe" -> IOOperator.pipe(ctx, args).getList();
            case "shutdown" -> IOOperator.shutdown(ctx, args).getList();
            case "send" -> IOOperator.send(ctx, args).getList();
            case "recv" -> {
                if (args.length >= 2 && (!(args[1] instanceof RuntimeScalar reference)
                        || reference.type != RuntimeScalarType.REFERENCE)) {
                    throw new PerlCompilerException(
                            "Type of arg 2 to &CORE::recv must be scalar reference");
                }
                yield IOOperator.recv(ctx, args).getList();
            }
            case "getsockname" -> IOOperator.getsockname(ctx, args).getList();
            case "getpeername" -> IOOperator.getpeername(ctx, args).getList();
            case "setsockopt" -> IOOperator.setsockopt(ctx, args).getList();
            case "getsockopt" -> IOOperator.getsockopt(ctx, args).getList();
            case "socketpair" -> IOOperator.socketpair(ctx, args).getList();
            case "formline" -> IOOperator.formline(ctx, args).getList();
            case "write" -> IOOperator.write(ctx, args).getList();
            case "getc" -> IOOperator.getc(ctx, args).getList();
            case "syscall" -> SyscallOperator.syscall(ctx, args).getList();
            case "each" -> {
                RuntimeBase target = args[0];
                if (target instanceof RuntimeScalar scalar
                        && scalar.type == RuntimeScalarType.HASHREFERENCE) {
                    yield scalar.hashDeref().each(ctx);
                }
                if (target instanceof RuntimeScalar scalar
                        && scalar.type == RuntimeScalarType.ARRAYREFERENCE) {
                    yield scalar.arrayDeref().each(ctx);
                }
                throw new PerlCompilerException(
                        "Type of arg 1 to &CORE::each must be hash or array reference");
            }
            case "pos" -> {
                RuntimeScalar target;
                if (args.length > 0) {
                    if (!(args[0] instanceof RuntimeScalar reference)
                            || reference.type != RuntimeScalarType.REFERENCE) {
                        throw new PerlCompilerException(
                                "Type of arg 1 to &CORE::pos must be scalar reference");
                    }
                    target = reference.scalarDeref();
                } else {
                    target = GlobalVariable.getGlobalVariable("main::_");
                }
                yield target.pos().getList();
            }
            case "not" -> MathOperators.not((RuntimeScalar) args[0]).getList();
            case "scalar" -> args.length == 0 ? RuntimeScalarCache.scalarUndef.getList()
                    : args[0].scalar().getList();
            case "lock" -> {
                if (args.length == 0) yield RuntimeScalarCache.scalarUndef.getList();
                if (!(args[0] instanceof RuntimeScalar scalar)
                        || !(scalar.type == RuntimeScalarType.REFERENCE
                        || scalar.type == RuntimeScalarType.ARRAYREFERENCE
                        || scalar.type == RuntimeScalarType.HASHREFERENCE
                        || scalar.type == RuntimeScalarType.CODE
                        || scalar.type == RuntimeScalarType.GLOB
                        || (scalar.type == RuntimeScalarType.GLOBREFERENCE
                        && scalar.value instanceof RuntimeGlob))) {
                    throw new PerlCompilerException(
                            "Type of arg 1 to &CORE::lock must be reference to one of [$@%&*]");
                }
                yield TieOperators.lock(ctx, args).getList();
            }

            // Directory operators
            case "mkdir" -> Directory.mkdir(new RuntimeList(args)).getList();
            case "opendir" -> Directory.opendir(new RuntimeList(args)).getList();
            case "closedir" -> Directory.closedir((RuntimeScalar) args[0]).getList();
            case "readdir" -> Directory.readdir((RuntimeScalar) args[0], ctx).getList();
            case "rewinddir" -> Directory.rewinddir((RuntimeScalar) args[0]).getList();
            case "seekdir" -> Directory.seekdir(new RuntimeList(args)).getList();
            case "telldir" -> Directory.telldir((RuntimeScalar) args[0]).getList();

            // System operators
            case "fork" -> SystemOperator.fork(ctx, args).getList();
            case "kill" -> KillOperator.kill(ctx, args).getList();
            case "umask" -> UmaskOperator.umask(ctx, args).getList();
            case "waitpid" -> WaitpidOperator.waitpid(ctx, args).getList();
            case "die" -> {
                RuntimeCode caller = RuntimeCode.getActiveCodeAt(1);
                CallerStack.CallerInfo callSite = CallerStack.peek(0);
                // An interpreted CV's cvStartLine is its compile-time default,
                // which can differ from the active source line after #line.
                // The call frame records the actual CORE::die expression site.
                boolean interpreterCaller = caller instanceof InterpretedCode;
                String file = interpreterCaller && callSite != null && callSite.filename() != null
                        ? callSite.filename() : caller != null && caller.cvStartFile != null
                        ? caller.cvStartFile : callSite == null ? null : callSite.filename();
                int line = interpreterCaller && callSite != null && callSite.line() > 0
                        ? callSite.line() : caller != null && caller.cvStartLine > 0
                        ? caller.cvStartLine : callSite == null ? 0 : callSite.line();
                yield WarnDie.die(new RuntimeList(args), RuntimeScalarCache.scalarEmptyString,
                        file, line).getList();
            }

            // Misc operators
            case "bless" -> {
                if (args.length == 0) {
                    throw new PerlCompilerException("Not enough arguments for bless");
                }
                RuntimeScalar className;
                if (args.length > 1) {
                    className = (RuntimeScalar) args[1];
                } else {
                    RuntimeCode caller = RuntimeCode.getActiveCodeAt(1);
                    String packageName = caller == null || caller.packageName == null
                            ? "main" : caller.packageName;
                    className = new RuntimeScalar(
                            ReferenceOperators.IMPLICIT_PACKAGE_PREFIX + packageName);
                }
                yield ReferenceOperators.bless((RuntimeScalar) args[0], className).getList();
            }
            case "substr" -> Operator.substr(ctx, args).getList();
            case "join" -> StringOperators.join((RuntimeScalar) args[0], tailList(args, 1)).getList();
            case "index" -> StringOperators.index((RuntimeScalar) args[0],
                    (RuntimeScalar) args[1], args.length > 2
                            ? (RuntimeScalar) args[2] : new RuntimeScalar()).getList();
            case "rindex" -> StringOperators.rindex((RuntimeScalar) args[0],
                    (RuntimeScalar) args[1], args.length > 2
                            ? (RuntimeScalar) args[2] : new RuntimeScalar()).getList();
            case "keys", "values" -> {
                if (args.length == 0 || !(args[0] instanceof RuntimeScalar scalar)) {
                    throw new PerlCompilerException("Type of arg 1 to &CORE::" + name
                            + " must be hash or array reference");
                }
                RuntimeBase result;
                if (scalar.type == RuntimeScalarType.HASHREFERENCE) {
                    RuntimeHash hash = scalar.hashDeref();
                    result = "keys".equals(name)
                            ? (ctx == RuntimeContextType.LVALUE || ctx == RuntimeContextType.LVALUE_LIST
                                    ? RuntimeCode.keysLvalue(hash) : hash.keys(ctx))
                            : hash.values();
                } else if (scalar.type == RuntimeScalarType.ARRAYREFERENCE) {
                    RuntimeArray array = scalar.arrayDeref();
                    result = "keys".equals(name)
                            ? (ctx == RuntimeContextType.LVALUE || ctx == RuntimeContextType.LVALUE_LIST
                                    ? RuntimeCode.keysLvalue(array) : array.keys())
                            : array.values();
                } else {
                    throw new PerlCompilerException("Type of arg 1 to &CORE::" + name
                            + " must be hash or array reference");
                }
                yield result.getList();
            }
            case "rename" -> Operator.rename(ctx, args).getList();
            case "readlink" -> Operator.readlink(ctx, args).getList();
            case "read" -> {
                if (args.length < 2 || !(args[1] instanceof RuntimeScalar reference)
                        || reference.type != RuntimeScalarType.REFERENCE) {
                    throw new PerlCompilerException(
                            "Type of arg 2 to &CORE::read must be scalar reference");
                }
                yield IOOperator.read(ctx, args).getList();
            }
            case "readline" -> {
                String argvGlob = GlobalVariable.resolveGlobAlias("main::ARGV");
                RuntimeBase[] actualArgs = args.length == 0
                        ? new RuntimeBase[]{GlobalVariable.getGlobalIO(argvGlob)} : args;
                yield IOOperator.readline(ctx, actualArgs).getList();
            }
            case "reset" -> Operator.reset(new RuntimeList(args), ctx).getList();
            case "seek" -> IOOperator.seek(ctx, args).getList();
            case "tell" -> IOOperator.tell(ctx, args).getList();
            case "select" -> {
                if (args.length > 1 && args.length < 4) {
                    throw new PerlCompilerException("Not enough arguments for select system call");
                }
                if (args.length > 4) {
                    throw new PerlCompilerException("Too many arguments for select system call");
                }
                yield IOOperator.select(new RuntimeList(args), ctx).getList();
            }
            case "prototype" -> {
                RuntimeCode caller = RuntimeCode.getActiveCodeAt(1);
                String packageName = caller == null || caller.packageName == null
                        ? "main" : caller.packageName;
                yield RuntimeCode.prototype((RuntimeScalar) args[0], packageName).getList();
            }
            case "pack" -> Pack.pack(new RuntimeList(args)).getList();
            case "unpack" -> Unpack.unpack(ctx, args);
            case "sprintf" -> SprintfOperator.sprintf((RuntimeScalar) args[0],
                    tailList(args, 1)).getList();
            case "getpgrp" -> Operator.getpgrp(ctx, args).getList();
            case "setpgrp" -> Operator.setpgrp(ctx, args).getList();
            case "getpriority" -> Operator.getpriority(ctx, args).getList();
            case "reverse" -> Operator.reverse(ctx, args).getList();
            case "link" -> {
                // NativeUtils.link expects (int, RuntimeBase...)
                yield org.perlonjava.runtime.nativ.NativeUtils.link(ctx, args).getList();
            }
            case "symlink" -> {
                yield org.perlonjava.runtime.nativ.NativeUtils.symlink(ctx, args).getList();
            }
            case "chmod" -> Operator.chmod(new RuntimeList(args)).getList();
            case "chown" -> ChownOperator.chown(ctx, args).getList();
            case "utime" -> UtimeOperator.utime(ctx, args).getList();
            case "unlink" -> UnlinkOperator.unlink(ctx, args).getList();

            // Tie operators
            case "tie" -> {
                requireTieTarget(name, args);
                yield TieOperators.tie(ctx, args).getList();
            }
            case "untie" -> TieOperators.untie(ctx, args).getList();
            case "tied" -> {
                requireTieTarget(name, args);
                yield TieOperators.tied(ctx, args).getList();
            }
            case "dbmopen" -> {
                requireHashReference(name, args);
                RuntimeBase[] tieArgs = new RuntimeBase[args.length + 1];
                tieArgs[0] = args[0];
                tieArgs[1] = new RuntimeScalar("PerlOnJava::DBM");
                System.arraycopy(args, 1, tieArgs, 2, args.length - 1);
                RuntimeScalar tied = TieOperators.tie(ctx, tieArgs);
                yield tied.getBoolean() ? RuntimeScalarCache.scalarTrue.getList() : tied.getList();
            }
            case "dbmclose" -> {
                requireHashReference(name, args);
                yield TieOperators.untie(ctx, args).getList();
            }

            default ->
                    throw new PerlCompilerException("&CORE::" + name + " not yet supported as subroutine reference");
        };
    }

    private static RuntimeList tailList(RuntimeBase[] args, int start) {
        RuntimeList result = new RuntimeList();
        for (int i = start; i < args.length; i++) {
            result.elements.add(args[i]);
        }
        return result;
    }

    private static boolean isPlainScalarReference(RuntimeBase value) {
        if (!(value instanceof RuntimeScalar scalar)
                || scalar.type != RuntimeScalarType.REFERENCE) {
            return false;
        }
        return !(scalar.value instanceof RuntimeIO);
    }

    private static void requireTieTarget(String name, RuntimeBase[] args) {
        boolean valid = false;
        if (args.length > 0 && args[0] instanceof RuntimeScalar scalar) {
            valid = switch (scalar.type) {
                case REFERENCE, ARRAYREFERENCE, HASHREFERENCE, GLOBREFERENCE ->
                        !(scalar.value instanceof RuntimeIO);
                default -> false;
            };
        }
        if (!valid) {
            throw new PerlCompilerException("Type of arg 1 to &CORE::" + name
                    + " must be reference to one of [$@%*]");
        }
    }

    private static void requireHashReference(String name, RuntimeBase[] args) {
        if (args.length == 0 || !(args[0] instanceof RuntimeScalar scalar)
                || scalar.type != RuntimeScalarType.HASHREFERENCE) {
            throw new PerlCompilerException("Type of arg 1 to &CORE::" + name
                    + " must be hash reference");
        }
    }
}
