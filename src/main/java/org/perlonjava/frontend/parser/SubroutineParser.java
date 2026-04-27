package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.VariableCollectorVisitor;
import org.perlonjava.backend.jvm.CompiledCode;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.debugger.DebugState;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.perlmodule.Universal;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import static org.perlonjava.frontend.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.frontend.parser.ParserTables.INFIX_OP;
import static org.perlonjava.frontend.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.frontend.parser.SignatureParser.parseSignature;
import static org.perlonjava.frontend.parser.TokenUtils.peek;

public class SubroutineParser {

    // Create a static semaphore with 1 permit
    private static final Semaphore semaphore = new Semaphore(1);

    /**
     * Parses a subroutine call.
     *
     * @param parser The parser object
     * @return A Node representing the parsed subroutine call.
     */
    static Node parseSubroutineCall(Parser parser, boolean isMethod) {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be the start of a v-string like v10.20.30
        int currentIndex = parser.tokenIndex;

        String subName = IdentifierParser.parseSubroutineIdentifier(parser);
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("SubroutineCall subName `" + subName + "` package " + parser.ctx.symbolTable.getCurrentPackage());
        if (subName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Check if this is a standard filehandle that should be treated as a bareword, not a subroutine call
        if (!isMethod && (subName.equals("STDIN") || subName.equals("STDOUT") || subName.equals("STDERR"))) {
            // Return as a simple identifier node, not a subroutine call
            return new IdentifierNode(subName, currentIndex);
        }

        // Check if this is a lexical sub/method (my sub name / my method name)
        // Lexical subs are stored in the symbol table with "&" prefix
        // IMPORTANT: Check lexical sub FIRST, even if the name is a quote-like operator!
        // This allows "my sub y" to shadow the "y///" transliteration operator
        String lexicalKey = "&" + subName;
        SymbolTable.SymbolEntry lexicalEntry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);
        if (lexicalEntry != null && lexicalEntry.ast() instanceof OperatorNode varNode) {
            // Check if this is an "our sub" - if so, use the stored fully qualified name
            Boolean isOurSub = (Boolean) varNode.getAnnotation("isOurSub");
            if (isOurSub != null && isOurSub) {
                // Use the stored fully qualified name instead of the current package
                String storedFullName = (String) varNode.getAnnotation("fullSubName");
                if (storedFullName != null) {
                    // Replace subName with the fully qualified name and continue with normal package sub lookup
                    subName = storedFullName;
                }
                // Fall through to normal package sub handling below
            } else {
                // This is a lexical sub (my/state) - handle it specially
                LexerToken nextToken = peek(parser);

                // Check if there's a prototype stored for this lexical sub
                String lexicalPrototype = varNode.getAnnotation("prototype") != null ?
                        (String) varNode.getAnnotation("prototype") : null;

                // Use lexical sub when:
                // 1. There are explicit parentheses, OR
                // 2. There's a prototype, OR
                // 3. The next token isn't a bareword identifier (to avoid indirect method call confusion), OR
                // 4. We're parsing a code reference for sort/map/grep (parsingForLoopVariable is true), OR
                // 5. The next token is a statement modifier keyword (if/unless/while/until/for/foreach/when)
                boolean useExplicitParen = nextToken.text.equals("(");
                boolean hasPrototype = lexicalPrototype != null;
                boolean nextIsIdentifier = nextToken.type == LexerTokenType.IDENTIFIER;
                boolean nextIsStatementModifier = nextIsIdentifier && isStatementModifierKeyword(nextToken.text);

                if (useExplicitParen || hasPrototype || !nextIsIdentifier || nextIsStatementModifier || parser.parsingForLoopVariable) {
                    // This is a lexical sub/method - use the hidden variable instead of package lookup
                    // The varNode is the "my $name__lexsub_123" or "my $name__lexmethod_123" variable

                    // Get the hidden variable name for the lexical sub
                    String hiddenVarName = (String) varNode.getAnnotation("hiddenVarName");
                    if (hiddenVarName != null) {
                        // Get the package where this lexical sub was declared
                        String declaringPackage = (String) varNode.getAnnotation("declaringPackage");

                        // Make the hidden variable name fully qualified with the declaring package
                        String qualifiedHiddenVarName = hiddenVarName;
                        if (declaringPackage != null && !hiddenVarName.contains("::")) {
                            qualifiedHiddenVarName = declaringPackage + "::" + hiddenVarName;
                        }

                        // Get the hidden variable entry from the symbol table for the ID
                        String hiddenVarKey = "$" + hiddenVarName;
                        SymbolTable.SymbolEntry hiddenEntry = parser.ctx.symbolTable.getSymbolEntry(hiddenVarKey);

                        // Always create a fresh variable reference to avoid AST reuse issues
                        OperatorNode dollarOp = new OperatorNode("$",
                                new IdentifierNode(qualifiedHiddenVarName, currentIndex), currentIndex);
                        // Propagate hiddenVarName annotation so that emitters can detect lexical subs
                        dollarOp.setAnnotation("hiddenVarName", hiddenVarName);

                        // Copy the ID from the symbol table entry for state variables
                        if (hiddenEntry != null && hiddenEntry.ast() instanceof OperatorNode hiddenVarNode) {
                            dollarOp.id = hiddenVarNode.id;
                        } else if (varNode.operator.equals("state") && varNode.operand instanceof OperatorNode innerNode) {
                            // Fallback: copy ID from the declaration
                            dollarOp.id = innerNode.id;
                        }

                        // If parsingForLoopVariable is set, we just need the code reference, not a call
                        // This is used by sort/map/grep when parsing the comparison sub
                        if (parser.parsingForLoopVariable) {
                            return dollarOp;
                        }

                        // Parse arguments using prototype if available
                        ListNode arguments;
                        if (useExplicitParen) {
                            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
                            if (hasPrototype) {
                                // Use prototype to parse arguments (already consumed opening paren)
                                arguments = consumeArgsWithPrototype(parser, lexicalPrototype, false);
                                TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
                            } else {
                                List<Node> argList = ListParser.parseList(parser, ")", 0);
                                arguments = new ListNode(argList, parser.tokenIndex);
                            }
                        } else {
                            // No explicit parentheses - parse arguments with prototype (or null for no prototype)
                            // This matches behavior of regular package subs which call consumeArgsWithPrototype
                            arguments = consumeArgsWithPrototype(parser, lexicalPrototype);
                        }

                        // Call the hidden variable directly: $hiddenVar(arguments)
                        // The () operator will handle dereferencing and calling
                        return new BinaryOperatorNode("(",
                                dollarOp,
                                arguments,
                                currentIndex);
                    }
                }
            }
        }

        // Normalize the subroutine name to include the current package
        // If subName already contains "::", it's already fully qualified (e.g., from "our sub")
        String fullName = subName.contains("::")
                ? subName
                : NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        // Check if we are parsing a method;
        // Otherwise, check that the subroutine exists in the global namespace - then fetch prototype and attributes
        // Special case: For method calls to 'new', don't require existence check (for generated constructors)
        boolean isNewMethod = isMethod && subName.equals("new");
        boolean subExists = isNewMethod;
        String prototype = null;
        List<String> attributes = null;
        if (!isNewMethod && !isMethod && GlobalVariable.existsGlobalCodeRef(fullName)) {
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
            if (codeRef.value instanceof RuntimeCode runtimeCode) {
                prototype = runtimeCode.prototype;
                attributes = runtimeCode.attributes;
                subExists = runtimeCode.subroutine != null
                        || runtimeCode.methodHandle != null
                        || runtimeCode.compilerSupplier != null
                        || runtimeCode.isBuiltin
                        || prototype != null
                        // Forward declarations like `sub foo;` create a RuntimeCode with a non-null
                        // attributes list (possibly empty). Placeholders created implicitly use null.
                        || attributes != null;
            }
        }
        if (!subExists && !isNewMethod && !isMethod) {
            subExists = GlobalVariable.existsGlobalCodeRefAsScalar(fullName).getBoolean();
        }
        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "` attributes " + attributes);

        boolean prototypeHasGlob = prototype != null && prototype.contains("*");

        // If a package name follows, then it looks like a indirect method
        // Unless the subName looks like an operator
        // Unless the subName has a prototype with `*`
        //
        // Note: feature-gated core keywords (`try`, `catch`, `finally`) should
        // participate in indirect-object parsing when their feature is *off* —
        // this is how Error.pm's classic
        //     try { ... } catch Error::Simple with { ... }
        // idiom is recognised (parses as `Error::Simple->catch(with {...})`).
        if (peek(parser).type == LexerTokenType.IDENTIFIER
                && isValidIndirectMethod(subName, parser)
                && !prototypeHasGlob) {
            int currentIndex2 = parser.tokenIndex;
            String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
            // System.out.println("maybe indirect object: " + packageName + "->" + subName);

            // PERL RULE: Indirect object syntax requires identifier to be a package
            // Check packageExistsCache which is populated when 'package' statement is parsed
            // Note: packageExistsCache uses the package name as-is for packages,
            // and fully qualified names for sub names (e.g., "main::error" not "error")
            Boolean isPackage = GlobalVariable.packageExistsCache.get(packageName);
            // Also check if this is a known sub in the current package (qualified lookup)
            if (isPackage == null && !packageName.contains("::")) {
                String qualifiedName = parser.ctx.symbolTable.getCurrentPackage() + "::" + packageName;
                Boolean qualifiedResult = GlobalVariable.packageExistsCache.get(qualifiedName);
                if (qualifiedResult != null && !qualifiedResult) {
                    isPackage = false;
                }
            }
            LexerToken token = peek(parser);
            String fullName1 = NameNormalizer.normalizeVariableName(packageName, parser.ctx.symbolTable.getCurrentPackage());
            boolean isLexicalSub = parser.ctx.symbolTable.getSymbolEntry("&" + packageName) != null;
            boolean isKnownSub = false;
            if (GlobalVariable.existsGlobalCodeRef(fullName1)) {
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName1);
                if (codeRef.value instanceof RuntimeCode runtimeCode) {
                    isKnownSub = runtimeCode.subroutine != null
                            || runtimeCode.methodHandle != null
                            || runtimeCode.compilerSupplier != null
                            || runtimeCode.isBuiltin
                            || runtimeCode.prototype != null
                            || runtimeCode.attributes != null;
                }
            }

            // Reject if:
            // 1. Explicitly marked as non-package (false in cache), OR
            // 2. Unknown package (null) AND unknown subroutine (!isKnownSub) AND followed by '('
            //    AND name is not package-qualified (no ::) - this is a function call like mycan(...)
            // 3. Calling sub exists AND qualified name with '()' AND NOT a known package
            //    — then it's a function call like: is MojoMonkeyTest::bar(), "bar"
            //    But if the qualified name IS a known package (isPackage==true), treat as
            //    indirect object: new LWP::UserAgent() → LWP::UserAgent->new()
            // Allow if:
            // - Marked as package (true), OR
            // - Unknown (null) but NOT followed by '(' - like 'new NonExistentClass'
            if ((isPackage != null && !isPackage)
                    || (isPackage == null && !isKnownSub && token.text.equals("(") && !packageName.contains("::") && subExists)
                    || (subExists && packageName.contains("::") && token.text.equals("(")
                        && !(isPackage != null && isPackage))) {
                parser.tokenIndex = currentIndex2;
            } else {
                // Not a known subroutine, check if it's valid indirect object syntax
                if (!isKnownSub && !isLexicalSub && isValidIndirectMethod(packageName)) {
                    if (!(token.text.equals("->") || token.text.equals("=>") || INFIX_OP.contains(token.text))) {
                        // System.out.println("  package loaded: " + packageName + "->" + subName);

                        ListNode arguments;
                        if (token.text.equals(",")) {
                            arguments = new ListNode(currentIndex);
                        } else {
                            arguments = consumeArgsWithPrototype(parser, "@");
                        }
                        return new BinaryOperatorNode(
                                "->",
                                new IdentifierNode(packageName, currentIndex2),
                                new BinaryOperatorNode("(",
                                        new OperatorNode("&",
                                                new IdentifierNode(subName, currentIndex2),
                                                currentIndex),
                                        arguments, currentIndex2),
                                currentIndex2);
                    }
                }

                // backtrack
                parser.tokenIndex = currentIndex2;
            }
        }

        // Handle indirect object syntax with variable class: new $type $arg -> $type->new($arg)
        // This is similar to the IDENTIFIER case above, but for variable class names
        // Only applies when the subroutine doesn't exist (otherwise it's a function call)
        if (!subExists && peek(parser).text.equals("$") && isValidIndirectMethod(subName) && !prototypeHasGlob) {
            int currentIndex2 = parser.tokenIndex;
            // Parse the variable that holds the class name
            // Set flag so Variable.parseVariable allows ( after the variable
            boolean savedParsingIndirectObject = parser.parsingIndirectObject;
            parser.parsingIndirectObject = true;
            Node classVar = ParsePrimary.parsePrimary(parser);
            parser.parsingIndirectObject = savedParsingIndirectObject;
            if (classVar != null) {
                LexerToken nextTok = peek(parser);
                // Check this isn't actually a binary operator like $type + 1
                if (!(nextTok.text.equals("->") || nextTok.text.equals("=>") || INFIX_OP.contains(nextTok.text))) {
                    // Parse arguments for the method call
                    ListNode arguments;
                    if (nextTok.text.equals(",") || nextTok.text.equals(";") ||
                            nextTok.text.equals(")") || nextTok.text.equals("}") ||
                            nextTok.type == LexerTokenType.EOF) {
                        // No arguments after class variable
                        arguments = new ListNode(currentIndex);
                    } else {
                        // Parse remaining arguments
                        arguments = consumeArgsWithPrototype(parser, "@");
                    }
                    // Create method call: $classVar->method(args)
                    return new BinaryOperatorNode(
                            "->",
                            classVar,
                            new BinaryOperatorNode("(",
                                    new OperatorNode("&",
                                            new IdentifierNode(subName, currentIndex2),
                                            currentIndex),
                                    arguments, currentIndex2),
                            currentIndex2);
                }
                // Not indirect object syntax - backtrack
                parser.tokenIndex = currentIndex2;
            }
        }

        // Create an identifier node for the subroutine name
        IdentifierNode nameNode = new IdentifierNode(subName, parser.tokenIndex);

        if (subName.startsWith("v") && subName.matches("^v\\d+$")) {
            if (parser.tokens.get(parser.tokenIndex).text.equals(".") || !subExists) {
                return StringParser.parseVstring(parser, subName, currentIndex);
            }
        }

        // Check if the subroutine call has parentheses
        boolean hasParentheses = peek(parser).text.equals("(");
        if (!subExists && !hasParentheses) {
            // Perl allows calling not-yet-declared subs without parentheses when the
            // following token is not an identifier (e.g. `skip "msg", 2;`).
            // This is heavily used by the perl5 test harness (test.pl) inside SKIP/TODO blocks.
            // Keep indirect method call disambiguation for the identifier-followed case.
            // IMPORTANT: do not apply this heuristic for method calls (`->method`) because
            // it can misparse expressions like `$obj->method ? 0 : 1`.
            if (isMethod) {
                return parseIndirectMethodCall(parser, nameNode);
            }
            LexerToken nextTok = peek(parser);
            boolean terminator = nextTok.text.equals(";")
                    || nextTok.text.equals("}")
                    || nextTok.text.equals(")")
                    || nextTok.text.equals("]")
                    || nextTok.text.equals(",")
                    || nextTok.type == LexerTokenType.EOF;
            // Word-operators (eq, ne, lt, gt, le, ge, cmp, x, isa, and, or,
            // xor, …) are produced by the lexer as IDENTIFIER tokens but are
            // listed in INFIX_OP.  Accept both OPERATOR and IDENTIFIER token
            // types here so a bareword like `Foo::Bar::` followed by one of
            // them is not mistaken for a sub call consuming the word
            // operator as its first argument.
            boolean infixOp = (nextTok.type == LexerTokenType.OPERATOR
                    || nextTok.type == LexerTokenType.IDENTIFIER)
                    && (INFIX_OP.contains(nextTok.text)
                    || nextTok.text.equals("?")
                    || nextTok.text.equals(":"));
            if (!terminator
                    && !infixOp
                    && (nextTok.type != LexerTokenType.IDENTIFIER
                        || (subName.contains("::")
                            && !nextTok.text.equals("or")
                            && !nextTok.text.equals("and")
                            && !nextTok.text.equals("not")
                            && !nextTok.text.equals("if")
                            && !nextTok.text.equals("unless")
                            && !nextTok.text.equals("while")
                            && !nextTok.text.equals("until")
                            && !nextTok.text.equals("for")
                            && !nextTok.text.equals("foreach")
                            && !nextTok.text.equals("when")))
                    && !nextTok.text.equals("->")
                    && !nextTok.text.equals("=>")) {
                // Check if this looks like indirect object syntax: method $object, args
                // In Perl, "release $ctx, V" means ($ctx->release(), "V") - a list of two elements
                // NOT $ctx->release("V") - we don't pass additional args to the method
                if (nextTok.text.equals("$")) {
                    // This might be indirect object syntax - only consume the object
                    ListNode objectArg = consumeArgsWithPrototype(parser, "$");
                    if (objectArg.elements.size() > 0) {
                        Node firstArg = objectArg.elements.get(0);
                        if (firstArg instanceof OperatorNode opNode && opNode.operator.equals("$")) {
                            Node object = firstArg;
                            // Create method call: object->method()
                            // The remaining args (after comma) are left for the outer context
                            Node methodCall = new BinaryOperatorNode("(",
                                    new OperatorNode("&", nameNode, currentIndex),
                                    new ListNode(currentIndex),
                                    currentIndex);
                            return new BinaryOperatorNode("->", object, methodCall, currentIndex);
                        }
                    }
                    // Not indirect object syntax - treat the parsed arg as a regular call
                    return new BinaryOperatorNode("(",
                            new OperatorNode("&", nameNode, currentIndex),
                            objectArg,
                            currentIndex);
                }

                // If the next token is "{", this is indirect object syntax when sub doesn't exist.
                // Perl parses "unknownmethod { expr } args" as "(expr)->unknownmethod(args)"
                // The block is evaluated and its result becomes the method invocant.
                // Any following expressions become arguments to the method call.
                if (nextTok.text.equals("{")) {
                    // Consume the opening brace
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                    // Parse the block as an expression - it will be evaluated at runtime
                    // to determine the invocant (class/object) for the method call
                    Node blockExpr = ParseBlock.parseBlock(parser);
                    // Consume the closing brace
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                    
                    // Parse any additional arguments after the block
                    // These become arguments to the method call
                    ListNode arguments = consumeArgsWithPrototype(parser, "@");
                    
                    // Create method call: (block_result)->method(args)
                    Node methodCall = new BinaryOperatorNode("(",
                            new OperatorNode("&", nameNode, currentIndex),
                            arguments,
                            currentIndex);
                    return new BinaryOperatorNode("->", blockExpr, methodCall, currentIndex);
                }

                ListNode arguments = consumeArgsWithPrototype(parser, "@");

                return new BinaryOperatorNode("(",
                        new OperatorNode("&", nameNode, currentIndex),
                        arguments,
                        currentIndex);
            }
            return parseIndirectMethodCall(parser, nameNode);
        }

        // Save the current subroutine context
        String previousSubroutine = parser.ctx.symbolTable.getCurrentSubroutine();

        try {
            // Set the subroutine being called for error messages
            parser.ctx.symbolTable.setCurrentSubroutine(fullName);

            // Handle the parameter list for the subroutine call
            ListNode arguments;
            if (peek(parser).text.equals("->")) {
                // method call without parentheses
                arguments = new ListNode(parser.tokenIndex);
            } else if (isMethod) {
                // FUNDAMENTAL PERL RULE: Method calls NEVER check prototypes!
                // This applies to ALL method calls (using ->), not just constructors.
                // Prototypes are only enforced for direct subroutine calls.
                // Parse arguments directly without any prototype restrictions.
                if (peek(parser).text.equals("(")) {
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
                    // ListParser.parseList returns List<Node>, wrap it in ListNode
                    // IMPORTANT: parseList consumes the closing delimiter ")" internally
                    List<Node> argList = ListParser.parseList(parser, ")", 0);
                    arguments = new ListNode(argList, parser.tokenIndex);
                    // DO NOT consume ")" again - parseList already did it
                } else {
                    // No parentheses, no arguments
                    arguments = new ListNode(parser.tokenIndex);
                }
            } else {
                // Direct subroutine calls DO check prototypes
                arguments = consumeArgsWithPrototype(parser, prototype);
            }

            // Rewrite and return the subroutine call as `&name(arguments)`
            return new BinaryOperatorNode("(",
                    new OperatorNode("&", nameNode, currentIndex),
                    arguments,
                    currentIndex);
        } finally {
            // Restore the previous subroutine context
            parser.ctx.symbolTable.setCurrentSubroutine(previousSubroutine);
        }
    }

    private static boolean isValidIndirectMethod(String subName) {
        return isValidIndirectMethod(subName, null);
    }

    private static boolean isValidIndirectMethod(String subName, Parser parser) {
        if (subName.startsWith("CORE::")) return false;
        if (!CORE_PROTOTYPES.containsKey(subName)) return true;
        // `try`, `catch`, `finally` are feature-gated.  When the `try`
        // feature is *off* they are not reserved and can participate in
        // indirect-object parsing (Error.pm's `catch CLASS with {...}` idiom).
        if (parser != null
                && (subName.equals("try") || subName.equals("catch") || subName.equals("finally"))
                && !parser.ctx.symbolTable.isFeatureCategoryEnabled("try")) {
            return true;
        }
        return false;
    }

    private static Node parseIndirectMethodCall(Parser parser, IdentifierNode nameNode) {
        // If the subroutine does not exist and there are no parentheses, it is not a subroutine call

            /*  It can be a call to a subroutine that is not defined yet:

                `File::Path::rmtree` is a bareword (string) or a file handle

                    $ perl -e ' print File::Path::rmtree '
                    (no output)

                    $ perl -e ' print STDOUT File::Path::rmtree '
                    File::Path::rmtree

                `File::Path::rmtree $_` is a method call `$_->method`:

                    $ perl -e ' File::Path::rmtree $_ '
                    Can't call method "rmtree" on an undefined value at -e line 1.

                    $ perl -e ' File::Path::rmtree this '
                    Can't locate object method "rmtree" via package "File::Path" (perhaps you forgot to load "File::Path"?)
             */

        if (peek(parser).text.equals("$")) {
            ListNode arguments = consumeArgsWithPrototype(parser, "$");
            int index = parser.tokenIndex;

            // For indirect object syntax like "s2 $f", this should be treated as "$f->s2()"
            // not as "s2($f)". The first argument becomes the object.
            if (arguments.elements.size() > 0) {
                Node object = arguments.elements.get(0);
                // Create method call: object->method()
                return new BinaryOperatorNode("->", object, nameNode, index);
            }

            // Fallback to subroutine call if no arguments
            return new BinaryOperatorNode(
                    "(",
                    new OperatorNode("&", nameNode, index),
                    arguments,
                    index);
        }

        return nameNode;
    }

    public static Node parseSubroutineDefinition(Parser parser, boolean wantName, String declaration) {

        // my, our, state subs are handled in StatementResolver, not here
        if (declaration != null && (declaration.equals("my") || declaration.equals("state"))) {
            throw new PerlCompilerException("Internal error: my/state sub should be handled in StatementResolver");
        }

        // This method is responsible for parsing an anonymous subroutine (a subroutine without a name)
        // or a named subroutine based on the 'wantName' flag.
        int currentIndex = parser.tokenIndex;

        // Initialize the subroutine name to null. This will store the name of the subroutine if 'wantName' is true.
        String subName = null;

        // If the 'wantName' flag is true and the next token is an identifier (or starts with ' or ::), we parse the subroutine name.
        // Note: ' and :: can start a subroutine name (old-style package separator or explicit main:: prefix)
        if (wantName && (peek(parser).type == LexerTokenType.IDENTIFIER || peek(parser).text.equals("'") || peek(parser).text.equals("::"))) {
            // 'parseSubroutineIdentifier' is called to handle cases where the subroutine name might be complex
            // (e.g., namespaced, fully qualified names). It may return null if no valid name is found.
            subName = IdentifierParser.parseSubroutineIdentifier(parser);

            // Mark named subroutines as non-packages in packageExistsCache immediately
            // This helps indirect object detection distinguish subs from packages.
            // IMPORTANT: Use the fully qualified name so that `sub error` in Template::Base
            // doesn't prevent `error` from being used as a class name in indirect method
            // syntax from other packages (e.g., `parse error` in main should still work
            // as indirect method call `error->parse()`).
            if (subName != null && !subName.contains("::")) {
                String qualifiedSubName = parser.ctx.symbolTable.getCurrentPackage() + "::" + subName;
                GlobalVariable.packageExistsCache.put(qualifiedSubName, false);
            } else if (subName != null) {
                GlobalVariable.packageExistsCache.put(subName, false);
            }
        }

        // Initialize the prototype node to null. This will store the prototype of the subroutine if it exists.
        String prototype = null;

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();

        // Check for invalid prototype-like constructs without parentheses
        if (peek(parser).text.equals("<") || peek(parser).text.equals("__FILE__")) {
            // This looks like a prototype but without parentheses - it's invalid
            if (subName != null) {
                String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
                parser.throwCleanError("Illegal declaration of subroutine " + fullName);
            } else {
                parser.throwCleanError("Illegal declaration of anonymous subroutine");
            }
        }

        // Build display name for attribute warnings
        String attrSubDisplayName;
        if (subName != null) {
            attrSubDisplayName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
        } else {
            attrSubDisplayName = parser.ctx.symbolTable.getCurrentPackage() + "::__ANON__";
        }

        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        // Track prevAttrPrototype to detect ":prototype(X) : prototype(Y)" across colon-separated calls
        String prevAttrProto = null;
        while (peek(parser).text.equals(":")) {
            String attrPrototype = consumeAttributes(parser, attributes, null, attrSubDisplayName, prevAttrProto);
            if (attrPrototype != null) {
                prevAttrProto = attrPrototype; // remember for "discards earlier" warning in next call
                prototype = attrPrototype;
            }
        }

        // Ensure attributes.pm is loaded when attribute syntax is used, so that
        // attributes::get() is available (Perl 5 implicitly loads attributes.pm)
        if (!attributes.isEmpty()) {
            org.perlonjava.runtime.operators.ModuleOperators.require(new RuntimeScalar("attributes.pm"));
        }

        ListNode signature = null;
        // Scope index for signature parameter variables (for strict vars checking).
        // Entered before parseSignature() so that default value expressions can
        // reference earlier parameters, and exited after the block body is parsed.
        int signatureScopeIndex = -1;

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (peek(parser).text.equals("(")) {
            if (parser.ctx.symbolTable.isFeatureCategoryEnabled("signatures")) {
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Signatures feature enabled");
                // Enter a scope for signature parameter variables so the parse-time
                // strict vars check can find them.  SignatureParser.parseParameter()
                // registers each parameter directly in this scope.
                signatureScopeIndex = parser.ctx.symbolTable.enterScope();
                // If the signatures feature is enabled, we parse a signature.
                signature = parseSignature(parser, subName);
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Signature AST: " + signature);
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("next token " + peek(parser));
            } else {
                // If the signatures feature is not enabled, we just parse the prototype as a string.
                // If a prototype exists, we parse it using 'parseRawString' method which handles it like the 'q()' operator.
                // This means it will take everything inside the parentheses as a literal string.
                prototype = ((StringNode) StringParser.parseRawString(parser, "q")).value;

                // Validate prototype - certain characters are not allowed
                if (prototype.contains("<>") || prototype.contains("__FILE__")) {
                    if (subName != null) {
                        String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
                        parser.throwCleanError("Illegal declaration of subroutine " + fullName);
                    } else {
                        parser.throwCleanError("Illegal declaration of anonymous subroutine");
                    }
                }

                // Emit "Illegal character in prototype" warning for (proto) syntax
                // For (proto) syntax, Perl uses "?" as the name for anonymous subs
                {
                    String protoDisplayName;
                    if (subName != null) {
                        protoDisplayName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
                    } else {
                        protoDisplayName = "?";
                    }
                    emitIllegalProtoWarning(parser, prototype, protoDisplayName);
                }

                // Build display name for :prototype() warnings
                // For :prototype(), Perl uses the full qualified name or __ANON__
                String subDisplayName;
                if (subName != null) {
                    subDisplayName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
                } else {
                    subDisplayName = parser.ctx.symbolTable.getCurrentPackage() + "::__ANON__";
                }

                // While there are attributes after the prototype (denoted by a colon ':'), we keep parsing them.
                while (peek(parser).text.equals(":")) {
                    String attrPrototype = consumeAttributes(parser, attributes, prototype, subDisplayName);
                    if (attrPrototype != null) {
                        prototype = attrPrototype;
                    }
                }
            }
        }

        if (wantName && subName != null && !peek(parser).text.equals("{")) {
            // A named subroutine can be predeclared without a block of code.
            String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeScalar codeRefScalar = GlobalVariable.defineGlobalCodeRef(fullName);
            RuntimeCode codeRef = (RuntimeCode) codeRefScalar.value;
            // Mark as explicitly declared so *{glob}{CODE} returns this code ref
            codeRef.isDeclared = true;

            // Only set prototype/attributes on a forward declaration if the sub
            // doesn't already have a body. Perl 5 ignores prototype changes from
            // forward redeclarations of already-defined subs.
            boolean hasBody = codeRef.subroutine != null || codeRef.methodHandle != null
                    || codeRef.compilerSupplier != null;
            if (!hasBody) {
                codeRef.prototype = prototype;
                codeRef.attributes = attributes;
            } else {
                // When redeclaring an existing sub with attributes (e.g., sub X : method),
                // merge the new attributes into the existing ones. This matches Perl's behavior
                // where `sub X { ... } sub X : method` adds the method attribute to X.
                if (attributes != null && !attributes.isEmpty()) {
                    if (codeRef.attributes == null) {
                        codeRef.attributes = new java.util.ArrayList<>(attributes);
                    } else {
                        for (String attr : attributes) {
                            if (!codeRef.attributes.contains(attr)) {
                                codeRef.attributes.add(attr);
                            }
                        }
                    }
                }

                // Emit "Prototype mismatch" warning when redeclaring with different prototype
                String oldProto = codeRef.prototype;
                if (prototype != null || oldProto != null) {
                    String oldDisplay = oldProto == null ? ": none" : " (" + oldProto + ")";
                    String newDisplay = prototype == null ? "none" : "(" + prototype + ")";
                    String oldForCompare = oldProto == null ? "none" : "(" + oldProto + ")";
                    if (!oldForCompare.equals(newDisplay)) {
                        String location = "";
                        if (parser.ctx.errorUtil != null) {
                            int line = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex);
                            location = " at " + parser.ctx.compilerOptions.fileName + " line " + line + ".\n";
                        }
                        String msg = "Prototype mismatch: sub " + fullName + oldDisplay + " vs " + newDisplay + location;
                        org.perlonjava.runtime.operators.WarnDie.warn(
                                new RuntimeScalar(msg), new RuntimeScalar(""));
                    }
                }
            }

            // Validate attributes on forward declarations too
            if (attributes != null && !attributes.isEmpty()) {
                String packageToUse = parser.ctx.symbolTable.getCurrentPackage();
                // For cross-package declarations like "sub Y::bar : foo", use the
                // original CV's package (where the code was first compiled), not
                // the syntactic target package. This matches Perl 5 behavior.
                if (codeRef.packageName != null) {
                    packageToUse = codeRef.packageName;
                } else if (subName.contains("::")) {
                    packageToUse = subName.substring(0, subName.lastIndexOf("::"));
                }
                callModifyCodeAttributes(packageToUse, codeRefScalar, attributes, parser, currentIndex);
            }

            ListNode result = new ListNode(parser.tokenIndex);
            result.setAnnotation("compileTimeOnly", true);
            return result;
        }

        if (!wantName && !peek(parser).text.equals("{")) {
            parser.throwCleanError("Illegal declaration of anonymous subroutine");
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Save the current subroutine context and set the new one
        String previousSubroutine = parser.ctx.symbolTable.getCurrentSubroutine();
        boolean previousInSubroutineBody = parser.ctx.symbolTable.isInSubroutineBody();

        // Set the current subroutine name (use empty string for anonymous subs)
        // Use fully qualified name so ByteCodeSourceMapper records the declaration-time
        // package, not whatever package might be set inside the sub body
        String qualifiedSubName = subName != null
                ? NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage())
                : "";
        parser.ctx.symbolTable.setCurrentSubroutine(qualifiedSubName);
        // We are now parsing inside a subroutine body (named or anonymous)
        parser.ctx.symbolTable.setInSubroutineBody(true);

        try {
            // Parse the block of the subroutine, which contains the actual code.
            BlockNode block = ParseBlock.parseBlock(parser);

            // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
            // Check if we reached EOF instead of finding the closing brace
            if (parser.tokenIndex >= parser.tokens.size() ||
                    parser.tokens.get(parser.tokenIndex).type == LexerTokenType.EOF) {
                parser.throwCleanError("Missing right curly");
            }
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

            // Insert signature code in the block
            if (signature != null) {
                block.elements.addAll(0, signature.elements);
            }

            if (subName == null) {
                return handleAnonSub(parser, subName, prototype, attributes, block, currentIndex);
            } else {
                return handleNamedSub(parser, subName, prototype, attributes, block, declaration);
            }
        } finally {
            // Exit the signature scope if we entered one
            if (signatureScopeIndex >= 0) {
                parser.ctx.symbolTable.exitScope(signatureScopeIndex);
            }
            // Restore the previous subroutine context
            parser.ctx.symbolTable.setCurrentSubroutine(previousSubroutine);
            parser.ctx.symbolTable.setInSubroutineBody(previousInSubroutineBody);
        }
    }

    static String consumeAttributes(Parser parser, List<String> attributes) {
        return consumeAttributes(parser, attributes, null, null, null);
    }

    /**
     * Parse attributes after a colon. Returns a prototype string if :prototype(...) is found.
     *
     * @param parser     The parser
     * @param attributes List to accumulate parsed attribute strings
     * @param priorPrototype The prototype set by (proto) syntax, for "overridden" warning (may be null)
     * @param subDisplayName The sub name for warning messages (may be null for anon subs)
     * @return The prototype string from :prototype(...), or null if not found
     */
    static String consumeAttributes(Parser parser, List<String> attributes, String priorPrototype, String subDisplayName) {
        return consumeAttributes(parser, attributes, priorPrototype, subDisplayName, null);
    }

    /**
     * Parse attributes after a colon. Returns a prototype string if :prototype(...) is found.
     *
     * @param parser     The parser
     * @param attributes List to accumulate parsed attribute strings
     * @param parenPrototype The prototype from (proto) syntax, for "overridden" warning (may be null)
     * @param subDisplayName The sub name for warning messages (may be null for anon subs)
     * @param prevAttrPrototype Prototype from a previous :prototype(...) call, for "discards" warning (may be null)
     * @return The prototype string from :prototype(...), or null if not found
     */
    static String consumeAttributes(Parser parser, List<String> attributes, String parenPrototype,
                                     String subDisplayName, String prevAttrPrototype) {
        // Consume the colon
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");

        if (parser.tokens.get(parser.tokenIndex).text.equals("=")) {
            parser.throwError("Use of := for an empty attribute list is not allowed");
        }
        if (peek(parser).text.equals("=")) {
            return null;
        }

        String prototype = null;

        // Loop to handle space-separated attributes after a single colon
        // e.g., `: locked method` parses both `locked` and `method`
        while (peek(parser).type == LexerTokenType.IDENTIFIER) {
            String attrString = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER).text;
            if (parser.tokens.get(parser.tokenIndex).text.equals("(")) {
                String argString;
                try {
                    // Parse the parenthesized parameter using raw string parsing.
                    // Unlike q(), Perl's attribute parameter parsing preserves backslashes:
                    // :Foo(\() gives parameter \( not ( — backslash is kept literally.
                    StringParser.ParsedString rawStr = StringParser.parseRawStrings(
                            parser, parser.ctx, parser.tokens, parser.tokenIndex, 1);
                    parser.tokenIndex = rawStr.next;
                    argString = rawStr.buffers.getFirst();
                } catch (PerlCompilerException e) {
                    // Rethrow with Perl-compatible message for unterminated parens
                    if (e.getMessage() != null && e.getMessage().contains("Can't find string terminator")) {
                        String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
                        throw new PerlCompilerException(
                                "Unterminated attribute parameter in attribute list" + loc + ".\n");
                    }
                    throw e;
                }

                if (attrString.equals("prototype")) {
                    //  :prototype($)
                    // Validate prototype characters first (Perl emits this before "overridden")
                    emitIllegalProtoWarning(parser, argString, subDisplayName);
                    // Emit "Prototype overridden" warning if prior prototype was set from (proto) syntax
                    if (parenPrototype != null && subDisplayName != null) {
                        String msg = "Prototype '" + parenPrototype + "' overridden by attribute 'prototype("
                                + argString + ")' in " + subDisplayName;
                        String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
                        org.perlonjava.runtime.operators.WarnDie.warn(
                                new RuntimeScalar(msg), new RuntimeScalar(loc));
                    }
                    // Emit "discards earlier prototype" warning if :prototype was already set
                    // (either in this same call or from a previous :prototype() call)
                    String existingAttrProto = prototype != null ? prototype : prevAttrPrototype;
                    if (existingAttrProto != null && subDisplayName != null) {
                        String msg = "Attribute prototype(" + argString
                                + ") discards earlier prototype attribute in same sub";
                        String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
                        org.perlonjava.runtime.operators.WarnDie.warn(
                                new RuntimeScalar(msg), new RuntimeScalar(loc));
                    }
                    prototype = argString;
                }

                attrString += "(" + argString + ")";
            }

            // Consume the attribute name (an identifier) and add it to the attributes list.
            attributes.add(attrString);
        }

        // Check for invalid separator characters after attributes
        // Valid separators are: colon (:), semicolon (;), opening/closing brace ({, }), assignment (=), EOF
        if (!attributes.isEmpty()) {
            LexerToken nextToken = peek(parser);
            if (nextToken.type == LexerTokenType.OPERATOR) {
                String t = nextToken.text;
                if (!t.equals(":") && !t.equals(";") && !t.equals("{") && !t.equals("}") && !t.equals("=")
                        && !t.equals("(") && !t.equals(",") && !t.equals(")")
                        && !t.equals("$") && !t.equals("@") && !t.equals("%")) {
                    // Check for :: (double colon is invalid separator in attr list)
                    if (t.equals("::") || (t.length() == 1 && !Character.isWhitespace(t.charAt(0)))) {
                        throw new PerlCompilerException(parser.tokenIndex,
                                "Invalid separator character '" + t.charAt(0) + "' in attribute list",
                                parser.ctx.errorUtil);
                    }
                }
            }
        }

        return prototype;
    }

    public static ListNode handleNamedSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, String declaration) {
        return handleNamedSubWithFilter(parser, subName, prototype, attributes, block, false, declaration);
    }

    public static ListNode handleNamedSubWithFilter(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, boolean filterLexicalMethods, String declaration) {
        // Check if there's a lexical forward declaration (our/my/state sub name;) that this definition should fulfill
        String lexicalKey = "&" + subName;
        SymbolTable.SymbolEntry lexicalEntry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);
        String packageToUse = parser.ctx.symbolTable.getCurrentPackage();

        // If the package stash has been aliased (e.g. via `*{Pkg::} = *{Other::}`), then
        // new symbols defined in this package should land in the effective stash.
        packageToUse = GlobalVariable.resolveStashAlias(packageToUse);

        if (lexicalEntry != null && lexicalEntry.ast() instanceof OperatorNode varNode) {
            // Check if this is an "our sub" forward declaration
            Boolean isOurSub = (Boolean) varNode.getAnnotation("isOurSub");
            if (isOurSub != null && isOurSub) {
                // Use the package from the forward declaration, not the current package
                String storedFullName = (String) varNode.getAnnotation("fullSubName");
                if (storedFullName != null && storedFullName.contains("::")) {
                    // Extract package from stored full name (e.g., "main::d" -> "main")
                    int lastColon = storedFullName.lastIndexOf("::");
                    packageToUse = storedFullName.substring(0, lastColon);
                }
            } else if (lexicalEntry.decl().equals("my") || lexicalEntry.decl().equals("state")) {
                // This is a "my sub" or "state sub" forward declaration
                // The body should be filled in by creating a runtime code object
                String hiddenVarName = (String) varNode.getAnnotation("hiddenVarName");
                if (hiddenVarName != null) {
                    // Create an anonymous sub that will be used to fill the lexical sub
                    // We need to compile this into a RuntimeCode object that can be executed
                    SubroutineNode anonSub = new SubroutineNode(
                            null,  // anonymous (no name)
                            prototype,
                            attributes,
                            block,
                            false,  // useTryCatch
                            parser.tokenIndex
                    );

                    // Create assignment that will execute at runtime
                    // Use the declaring package to create a fully qualified variable name
                    String declaringPackage = (String) varNode.getAnnotation("declaringPackage");
                    String qualifiedHiddenVarName = hiddenVarName;
                    if (declaringPackage != null && !hiddenVarName.contains("::")) {
                        qualifiedHiddenVarName = declaringPackage + "::" + hiddenVarName;
                    }

                    OperatorNode varRef = new OperatorNode("$",
                            new IdentifierNode(qualifiedHiddenVarName, parser.tokenIndex),
                            parser.tokenIndex);

                    BinaryOperatorNode assignment = new BinaryOperatorNode("=", varRef, anonSub, parser.tokenIndex);

                    // Wrap the assignment in a BEGIN block so it executes at compile time
                    // This ensures that "sub name { }" inside another sub still fills the forward declaration immediately
                    List<Node> blockElements = new ArrayList<>();
                    blockElements.add(assignment);
                    BlockNode beginBlock = new BlockNode(blockElements, parser.tokenIndex);

                    // Execute the BEGIN block immediately during parsing
                    SpecialBlockParser.runSpecialBlock(parser, "BEGIN", beginBlock);

                    ListNode result = new ListNode(parser.tokenIndex);
                    result.setAnnotation("compileTimeOnly", true);
                    return result;
                }
            }
        }

        // - register the subroutine in the namespace
        String fullName = NameNormalizer.normalizeVariableName(subName, packageToUse);
        // Apply stash-alias resolution so `*Dst:: = *Src::; sub Dst::foo {}`
        // installs in Src::foo and reports "Src::foo" from caller(). The
        // resolution happens here (not in NameNormalizer) to avoid rewriting
        // compile-time read references that should keep pointing at their
        // original CvGV — only install-site names are resolved.
        fullName = GlobalVariable.resolveAliasedFqn(fullName);
        RuntimeScalar codeRef = GlobalVariable.defineGlobalCodeRef(fullName);
        InheritanceResolver.invalidateCache();
        
        // Check if we're redefining an existing subroutine that already has code.
        // In that case, create a NEW RuntimeCode so that saved code references
        // (from \&sub or can()) continue pointing to the old implementation.
        // This matches Perl's behavior where:
        //   my $orig = \&foo; sub foo { "new" }; $orig->() returns "old"
        boolean isRedefinition = false;
        String oldPrototype = null;
        boolean isConstantSub = false;
        boolean isBuiltinSub = false;  // Java-registered (XS-like) methods don't trigger redefine warnings
        if (codeRef.value instanceof RuntimeCode existingCode) {
            // Check if the existing code has actual implementation OR pending compilation
            // compilerSupplier != null means there's a lazy definition waiting to be compiled
            isRedefinition = existingCode.subroutine != null 
                    || existingCode.methodHandle != null
                    || existingCode.codeObject != null
                    || existingCode.compilerSupplier != null;
            if (isRedefinition) {
                oldPrototype = existingCode.prototype;
                // A constant sub has empty prototype "()" - detect for "Constant subroutine" warning
                isConstantSub = "".equals(oldPrototype);
                // Java-registered methods (via registerMethod) have isStatic=true and methodHandle set
                isBuiltinSub = existingCode.isStatic && existingCode.methodHandle != null;
            }
        }

        // Emit "Prototype mismatch" and "Subroutine redefined" warnings
        // Skip warnings for Java-registered (XS-like) built-in methods being overridden by Perl stubs
        if (isRedefinition && block != null && !isBuiltinSub) {
            String location = "";
            if (parser.ctx.errorUtil != null) {
                int line = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex);
                location = " at " + parser.ctx.compilerOptions.fileName + " line " + line + ".\n";
            }

            // Prototype mismatch is a default warning (always on unless explicitly disabled)
            boolean dollarW = GlobalVariable.getGlobalVariable("main::" + Character.toString('W' - 'A' + 1)).getBoolean();
            {
                // Perl format: "sub NAME: none vs (new)" or "sub NAME (old) vs none"
                // When prototype is null, display as ": none"; when defined, display as " (proto)"
                String oldDisplay = oldPrototype == null ? ": none" : " (" + oldPrototype + ")";
                String newDisplay = prototype == null ? "none" : "(" + prototype + ")";
                String oldForCompare = oldPrototype == null ? "none" : "(" + oldPrototype + ")";
                if (!oldForCompare.equals(newDisplay)) {
                    String msg = "Prototype mismatch: sub " + fullName + oldDisplay + " vs " + newDisplay + location;
                    org.perlonjava.runtime.operators.WarnDie.warn(
                            new RuntimeScalar(msg), new RuntimeScalar(""));
                }
            }

            // "Constant subroutine X redefined" is a default warning (always on)
            // "Subroutine X redefined" requires -w or use warnings 'redefine'
            if (isConstantSub) {
                String msg = "Constant subroutine " + subName + " redefined" + location;
                org.perlonjava.runtime.operators.WarnDie.warn(
                        new RuntimeScalar(msg), new RuntimeScalar(""));
            } else if (!Warnings.warningManager.isWarningDisabled("redefine")
                    && (dollarW || Warnings.warningManager.isWarningEnabled("redefine"))) {
                String msg = "Subroutine " + subName + " redefined" + location;
                org.perlonjava.runtime.operators.WarnDie.warn(
                        new RuntimeScalar(msg), new RuntimeScalar(""));
            }
        }

        if (codeRef.value == null || isRedefinition) {
            codeRef.type = RuntimeScalarType.CODE;
            codeRef.value = new RuntimeCode(subName, attributes);
        }
        // Mark as explicitly declared so *{glob}{CODE} returns this code ref.
        // In Perl 5, declared subs (even forward declarations) are visible via *{glob}{CODE}.
        if (codeRef.value instanceof RuntimeCode declaredCode) {
            declaredCode.isDeclared = true;
        }

        // Register subroutine location for %DB::sub (only in debug mode)
        if (DebugState.debugMode && parser.ctx.errorUtil != null && block != null) {
            int startLine = parser.ctx.errorUtil.getLineNumber(block.tokenIndex);
            // Use current position as end for now (could track block end for accuracy)
            int endLine = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex);
            DebugState.registerSubroutine(fullName, parser.ctx.compilerOptions.fileName, startLine, endLine);
        }

        // Initialize placeholder metadata (accessed via codeRef.value)
        RuntimeCode placeholder = (RuntimeCode) codeRef.value;
        placeholder.prototype = prototype;
        // Preserve existing attributes from forward declarations when the new definition
        // doesn't specify attributes. In Perl, `sub PS : lvalue; sub PS { }` preserves
        // the lvalue attribute. Only overwrite if the new definition specifies attributes.
        if (attributes != null && !attributes.isEmpty()) {
            placeholder.attributes = attributes;
        } else if (placeholder.attributes == null) {
            placeholder.attributes = attributes;
        }
        // else: preserve existing attributes (e.g., from forward declaration)

        // Split fullName into package/name so subName never contains "::".
        // The raw `subName` parameter may include package qualifiers (e.g. parsing
        // `sub Dst::foo { }` arrives here with subName="Dst::foo"), and fullName
        // may have been rewritten by a stash alias — always derive both halves
        // from fullName so caller()/set_subname see a consistent pair.
        int lastSep = fullName.lastIndexOf("::");
        placeholder.subName = lastSep >= 0 ? fullName.substring(lastSep + 2) : subName;

        // Call MODIFY_CODE_ATTRIBUTES if attributes are present
        // In Perl, this is called at compile time after the sub is defined.
        // The dispatch package is the CvSTASH of the existing code ref (if any),
        // not the current package. E.g., *Y::bar = \&X::foo; sub Y::bar : attr
        // dispatches X::MODIFY_CODE_ATTRIBUTES because the code ref's stash is X.
        if (attributes != null && !attributes.isEmpty()) {
            String attrPackage = (placeholder.packageName != null && !placeholder.packageName.isEmpty())
                    ? placeholder.packageName
                    : packageToUse;
            callModifyCodeAttributes(attrPackage, codeRef, attributes, parser, block.tokenIndex);
        }

        // Set packageName from the sub's fully-qualified name (CvSTASH equivalent).
        // For `sub X::foo { }` in package main, packageName should be "X", not "main".
        placeholder.packageName = lastSep >= 0
                ? fullName.substring(0, lastSep)
                : parser.ctx.symbolTable.getCurrentPackage();

        // Optimization - https://github.com/fglock/PerlOnJava/issues/8
        // Prepare capture variables
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();

        // Selective capture: only capture variables actually used in the sub body.
        // This prevents hitting JVM's 255 constructor argument limit for named subs
        // in modules like Perl::Tidy that have 200+ lexicals in scope.
        Set<String> usedVars = null;
        {
            Set<String> usedVarSet = new HashSet<>();
            VariableCollectorVisitor collector = new VariableCollectorVisitor(usedVarSet);
            block.accept(collector);
            if (!collector.hasEvalString()) {
                usedVars = usedVarSet;
            }
        }

        ArrayList<Class> classList = new ArrayList<>();
        ArrayList<Object> paramList = new ArrayList<>();
        for (SymbolTable.SymbolEntry entry : outerVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty()) {
                // Skip field declarations - they are not closure variables
                // Fields have "field" as their declaration type
                if (entry.decl().equals("field")) {
                    continue;
                }

                String sigil = entry.name().substring(0, 1);

                // Skip code references (subroutines/methods) - they are not captured as closure variables
                if (sigil.equals("&")) {
                    continue;
                }

                // For generated methods (constructor, readers, writers), skip lexical sub/method hidden variables
                // These variables (like $priv__lexmethod_123) are implementation details
                // User-defined methods can capture them, but generated methods should not
                if (filterLexicalMethods) {
                    String varName = entry.name();
                    if (varName.contains("__lexmethod_") || varName.contains("__lexsub_")) {
                        continue;
                    }
                }

                // Skip variables not actually used in the sub body (selective capture optimization)
                if (usedVars != null && !usedVars.contains(entry.name())) {
                    continue;
                }

                String variableName = null;
                if (entry.decl().equals("our")) {
                    // Normalize variable name for 'our' declarations
                    variableName = NameNormalizer.normalizeVariableName(
                            entry.name().substring(1),
                            entry.perlPackage());
                } else {
                    OperatorNode ast = entry.ast();
                    // For state variables, the persistent-variable id is already
                    // assigned (see OperatorParser "state" handling). Reuse it so
                    // that the storage key here matches the key used by the state
                    // initializer / retrieveStateScalar (which also use ast.id).
                    int beginId;
                    if ("state".equals(entry.decl()) && ast != null && ast.id != 0) {
                        beginId = ast.id;
                        RuntimeCode.evalBeginIds.putIfAbsent(ast, beginId);
                    } else {
                        beginId = RuntimeCode.evalBeginIds.computeIfAbsent(
                                ast,
                                k -> EmitterMethodCreator.classCounter++);
                    }
                    variableName = NameNormalizer.normalizeVariableName(
                            entry.name().substring(1),
                            PersistentVariable.beginPackage(beginId));
                }
                // Determine the class type based on the sigil
                classList.add(
                        switch (sigil) {
                            case "$" -> RuntimeScalar.class;
                            case "%" -> RuntimeHash.class;
                            case "@" -> RuntimeArray.class;
                            default -> throw new IllegalStateException("Unexpected value: " + sigil);
                        }
                );
                // Add the corresponding global variable to the parameter list
                Object capturedVar = switch (sigil) {
                    case "$" -> GlobalVariable.getGlobalVariable(variableName);
                    case "%" -> GlobalVariable.getGlobalHash(variableName);
                    case "@" -> GlobalVariable.getGlobalArray(variableName);
                    default -> throw new IllegalStateException("Unexpected value: " + sigil);
                };
                paramList.add(capturedVar);
                // System.out.println("Capture " + entry.decl() + " " + entry.name() + " as " + variableName);
            }
        }
        // Create a new EmitterContext for generating bytecode
        // Create a filtered snapshot that excludes field declarations and code references
        // Fields cause bytecode generation issues when present in the symbol table
        // Code references (&) should not be captured as closure variables
        ScopedSymbolTable filteredSnapshot = new ScopedSymbolTable();
        filteredSnapshot.enterScope();

        // Copy all visible variables except field declarations and code references
        // IMPORTANT: Use the 4-argument version to preserve the original perlPackage
        // This is critical for 'our' variables which must retain their declared package
        // for correct global lookup (especially with the 'local' fix)
        //
        // NOTE: We apply usedVars filtering here too, but we must preserve the first
        // entries that correspond to skipVariables (3 slots: this, @_, wantarray).
        // The outerVars loop (classList/paramList) skips @_ and empty-decl entries,
        // so those entries only appear in filteredSnapshot as "padding" for the
        // skipVariables slots. We must always include them.
        Map<Integer, SymbolTable.SymbolEntry> visibleVars = parser.ctx.symbolTable.getAllVisibleVariables();
        int addedCount = 0;
        for (SymbolTable.SymbolEntry entry : visibleVars.values()) {
            // Skip field declarations when creating snapshot for bytecode generation
            if (entry.decl().equals("field")) {
                continue;
            }
            // Skip code references (subroutines) - they should not be captured as closure variables
            String sigil = entry.name().substring(0, 1);
            if (sigil.equals("&")) {
                continue;
            }
            // Determine if this entry is a "padding" entry (would be skipped by classList loop)
            // These correspond to the skipVariables slots and must always be included
            boolean isPadding = entry.name().equals("@_") || entry.decl().isEmpty();
            // Skip variables not actually used in the sub body (selective capture optimization)
            // but always keep padding entries
            if (!isPadding && usedVars != null && !usedVars.contains(entry.name())) {
                continue;
            }
            filteredSnapshot.addVariable(entry.name(), entry.decl(), entry.perlPackage(), entry.ast());
            addedCount++;
        }

        // Clone the current package
        filteredSnapshot.setCurrentPackage(parser.ctx.symbolTable.getCurrentPackage(),
                parser.ctx.symbolTable.currentPackageIsClass());

        // Clone the current subroutine
        filteredSnapshot.setCurrentSubroutine(parser.ctx.symbolTable.getCurrentSubroutine());

        // Clone warning flags (critical for 'no warnings' pragmas)
        filteredSnapshot.warningFlagsStack.pop(); // Remove the initial value pushed by enterScope
        filteredSnapshot.warningFlagsStack.push((java.util.BitSet) parser.ctx.symbolTable.warningFlagsStack.peek().clone());
        
        // Clone fatal warning flags (critical for 'use warnings FATAL' pragmas)
        filteredSnapshot.warningFatalStack.pop();
        filteredSnapshot.warningFatalStack.push((java.util.BitSet) parser.ctx.symbolTable.warningFatalStack.peek().clone());
        
        // Clone disabled warning flags (critical for 'no warnings' pragmas)
        filteredSnapshot.warningDisabledStack.pop();
        filteredSnapshot.warningDisabledStack.push((java.util.BitSet) parser.ctx.symbolTable.warningDisabledStack.peek().clone());

        // Clone feature flags (critical for 'use feature' pragmas like refaliasing)
        filteredSnapshot.featureFlagsStack.pop(); // Remove the initial value pushed by enterScope
        filteredSnapshot.featureFlagsStack.push(parser.ctx.symbolTable.featureFlagsStack.peek());

        // Clone strict options (critical for 'use strict' pragma)
        filteredSnapshot.strictOptionsStack.pop(); // Remove the initial value pushed by enterScope
        filteredSnapshot.strictOptionsStack.push(parser.ctx.symbolTable.strictOptionsStack.peek());

        EmitterContext newCtx = new EmitterContext(
                new JavaClassInfo(),
                filteredSnapshot,
                null,
                null,
                RuntimeContextType.RUNTIME,
                true,
                parser.ctx.errorUtil,
                parser.ctx.compilerOptions,
                new RuntimeArray()
        );

        // Hybrid lazy/eager compilation approach:
        // - Keep lazy compilation for normal code (preserves test compatibility)
        // - The Supplier tries createRuntimeCode() which handles both normal compilation and interpreter fallback
        // - For InterpretedCode, we replace codeRef.value (not just code fields)

        Supplier<Void> subroutineCreationTaskSupplier = () -> {
            // Try unified API (returns RuntimeCode - either CompiledCode or InterpretedCode)
            RuntimeCode runtimeCode =
                    EmitterMethodCreator.createRuntimeCode(newCtx, block, false);

            try {
                if (runtimeCode instanceof CompiledCode compiledCode) {
                    // CompiledCode path - fill in the existing placeholder
                    Class<?> generatedClass = compiledCode.generatedClass;

                    // Prepare constructor with the captured variable types
                    Class<?>[] parameterTypes = classList.toArray(new Class<?>[0]);
                    Constructor<?> constructor = generatedClass.getConstructor(parameterTypes);

                    // Instantiate the subroutine with the captured variables
                    Object[] parameters = paramList.toArray();
                    placeholder.codeObject = constructor.newInstance(parameters);

                    // Set the PerlSubroutine interface for direct invocation
                    placeholder.subroutine = (PerlSubroutine) placeholder.codeObject;

                    // Set the __SUB__ instance field to codeRef
                    Field field = placeholder.codeObject.getClass().getDeclaredField("__SUB__");
                    field.set(placeholder.codeObject, codeRef);

                } else if (runtimeCode instanceof InterpretedCode interpretedCode) {
                    // InterpretedCode path - update placeholder in-place (not replace codeRef.value)
                    // This is critical: hash assignments copy RuntimeScalar but share the same
                    // RuntimeCode value object. If we replace codeRef.value, hash copies won't see
                    // the update. By setting subroutine/codeObject on the placeholder, ALL
                    // references (including hash copies) will see the compiled code.

                    // Set captured variables if there are any
                    if (!paramList.isEmpty()) {
                        Object[] parameters = paramList.toArray();
                        RuntimeBase[] capturedVars =
                                new RuntimeBase[parameters.length];
                        for (int i = 0; i < parameters.length; i++) {
                            capturedVars[i] = (RuntimeBase) parameters[i];
                        }
                        interpretedCode = interpretedCode.withCapturedVars(capturedVars);
                    }

                    // Copy metadata from the placeholder
                    interpretedCode.prototype = placeholder.prototype;
                    interpretedCode.attributes = placeholder.attributes;
                    interpretedCode.subName = placeholder.subName;
                    interpretedCode.packageName = placeholder.packageName;

                    // Set the __SUB__ field for self-reference
                    interpretedCode.__SUB__ = codeRef;

                    // Set PerlSubroutine interface for direct invocation
                    // InterpretedCode implements PerlSubroutine, so we can use it directly
                    placeholder.subroutine = interpretedCode;
                    placeholder.codeObject = interpretedCode;
                }
            } catch (VerifyError ve) {
                // VerifyError extends Error (not Exception), so it's not caught by catch(Exception).
                // This happens when JVM verification fails for the compiled class during deferred
                // instantiation (constructor.newInstance()). The class was accepted by defineClass()
                // but the verifier rejected it at link time due to StackMapTable inconsistencies
                // (e.g., local variable slot type conflicts in complex methods).
                // Fall back to interpreter for this subroutine.
                boolean showFallback = System.getenv("JPERL_SHOW_FALLBACK") != null;
                if (showFallback) {
                    System.err.println("Note: JVM VerifyError during subroutine instantiation, recompiling with interpreter.");
                }
                InterpretedCode interpretedCode = EmitterMethodCreator.compileToInterpreter(block, newCtx, false);

                // Set captured variables if there are any
                if (!paramList.isEmpty()) {
                    Object[] parameters = paramList.toArray();
                    RuntimeBase[] capturedVars = new RuntimeBase[parameters.length];
                    for (int i = 0; i < parameters.length; i++) {
                        capturedVars[i] = (RuntimeBase) parameters[i];
                    }
                    interpretedCode = interpretedCode.withCapturedVars(capturedVars);
                }

                // Copy metadata from the placeholder
                interpretedCode.prototype = placeholder.prototype;
                interpretedCode.attributes = placeholder.attributes;
                interpretedCode.subName = placeholder.subName;
                interpretedCode.packageName = placeholder.packageName;
                interpretedCode.__SUB__ = codeRef;
                placeholder.subroutine = interpretedCode;
                placeholder.codeObject = interpretedCode;
            } catch (Exception e) {
                // Handle any exceptions during subroutine creation
                throw new PerlCompilerException("Subroutine error: " + e.getMessage());
            }

            // Transfer pad constants (cached string literals referenced via \)
            // from compile context to the RuntimeCode for optree reaping.
            if (newCtx.javaClassInfo.padConstants != null && !newCtx.javaClassInfo.padConstants.isEmpty()) {
                placeholder.padConstants = newCtx.javaClassInfo.padConstants.toArray(
                        new org.perlonjava.runtime.runtimetypes.RuntimeBase[0]);
            }

            // Clear the compilerSupplier once done (use the captured placeholder variable)
            // This prevents the Supplier from being invoked multiple times
            placeholder.compilerSupplier = null;
            return null;
        };

        // Store the supplier in the placeholder
        RuntimeCode placeholderForSupplier = (RuntimeCode) codeRef.value;
        placeholderForSupplier.compilerSupplier = subroutineCreationTaskSupplier;

        ListNode result = new ListNode(parser.tokenIndex);
        result.setAnnotation("compileTimeOnly", true);
        return result;
    }

    /**
     * Call MODIFY_CODE_ATTRIBUTES on the package if it exists.
     * In Perl, when a subroutine is defined with attributes (sub foo : Attr { }),
     * the package's MODIFY_CODE_ATTRIBUTES method is called at compile time with
     * ($package, \&code, @attributes). If it returns any values, those are
     * unrecognized attributes and an error is thrown.
     *
     * If no MODIFY_CODE_ATTRIBUTES handler exists, non-built-in attributes
     * are rejected with an error.
     */
    private static void callModifyCodeAttributes(String packageName, RuntimeScalar codeRef,
                                                  List<String> attributes, Parser parser,
                                                  int declTokenIndex) {
        // Built-in CODE attributes that are always recognized
        java.util.Set<String> builtinAttrs = java.util.Set.of("lvalue", "method", "const");

        // Filter out built-in and prototype(...) attributes — these are always valid
        List<String> nonBuiltinAttrs = new java.util.ArrayList<>();
        for (String attr : attributes) {
            String name = attr;
            if (name.startsWith("-")) name = name.substring(1);
            // Strip (args) for matching
            int parenIdx = name.indexOf('(');
            String baseName = parenIdx >= 0 ? name.substring(0, parenIdx) : name;
            if (!builtinAttrs.contains(baseName) && !baseName.equals("prototype")) {
                nonBuiltinAttrs.add(attr);
            }
        }

        // If all attributes are built-in, nothing more to check
        if (nonBuiltinAttrs.isEmpty()) {
            return;
        }

        // Check if the package has MODIFY_CODE_ATTRIBUTES
        RuntimeArray canArgs = new RuntimeArray();
        RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
        RuntimeArray.push(canArgs, new RuntimeScalar("MODIFY_CODE_ATTRIBUTES"));

        InheritanceResolver.autoloadEnabled = false;
        RuntimeList codeList;
        try {
            codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
        } finally {
            InheritanceResolver.autoloadEnabled = true;
        }

        boolean hasHandler = codeList.size() == 1 && codeList.getFirst().getBoolean();

        if (hasHandler) {
            RuntimeScalar method = codeList.getFirst();
            // Build args: ($package, \&code, @attributes)
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, new RuntimeScalar(packageName));
            RuntimeArray.push(callArgs, codeRef);
            for (String attr : nonBuiltinAttrs) {
                RuntimeArray.push(callArgs, new RuntimeScalar(attr));
            }

            // Push caller frames so that Attribute::Handlers can find the source file/line
            // via `caller 2`. Frame 0 = the MODIFY handler, frame 1 = attributes dispatch,
            // frame 2 = the original source location where the attribute was declared.
            String fileName = parser.ctx.compilerOptions.fileName;
            int lineNum = parser.ctx.errorUtil != null
                    ? parser.ctx.errorUtil.getLineNumber(declTokenIndex) : 0;
            CallerStack.push(packageName, fileName, lineNum);
            CallerStack.push(packageName, fileName, lineNum);
            try {
                RuntimeList result = RuntimeCode.apply(method, callArgs, RuntimeContextType.LIST);

                // If MODIFY_CODE_ATTRIBUTES returns any values, they are unrecognized attributes
                RuntimeArray resultArray = result.getArrayOfAlias();
                if (resultArray.size() > 0) {
                    throwInvalidAttributeError("CODE", resultArray, parser);
                }
            } finally {
                CallerStack.pop();
                CallerStack.pop();
            }
        } else {
            // No MODIFY_CODE_ATTRIBUTES handler — all non-built-in attributes are invalid
            throwInvalidAttributeError("CODE", nonBuiltinAttrs, parser);
        }
    }

    static void throwInvalidAttributeError(String type, RuntimeArray attrs, Parser parser) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) sb.append(" : ");
            sb.append(attrs.get(i).toString());
        }
        String attrMsg = "Invalid " + type + " attribute" + (attrs.size() > 1 ? "s" : "") + ": " + sb;
        if (!type.equals("CODE")) {
            // Variable attributes (SCALAR, ARRAY, HASH) use Perl's "use attributes" style error format:
            // "Invalid TYPE attribute: Name at FILE line LINE.\nBEGIN failed--compilation aborted at FILE line LINE.\n"
            String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
            throw new PerlCompilerException(attrMsg + loc + ".\nBEGIN failed--compilation aborted" + loc + ".\n");
        }
        throw new PerlCompilerException(parser.tokenIndex, attrMsg, parser.ctx.errorUtil);
    }

    static void throwInvalidAttributeError(String type, List<String> attrs, Parser parser) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) sb.append(" : ");
            sb.append(attrs.get(i));
        }
        String attrMsg = "Invalid " + type + " attribute" + (attrs.size() > 1 ? "s" : "") + ": " + sb;
        if (!type.equals("CODE")) {
            // Variable attributes (SCALAR, ARRAY, HASH) use Perl's "use attributes" style error format
            String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
            throw new PerlCompilerException(attrMsg + loc + ".\nBEGIN failed--compilation aborted" + loc + ".\n");
        }
        throw new PerlCompilerException(parser.tokenIndex, attrMsg, parser.ctx.errorUtil);
    }

    private static SubroutineNode handleAnonSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, int currentIndex) {
        // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
        // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
        LexerToken token = peek(parser);
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            // Throw an exception indicating a syntax error.
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        // Finally, we return a new 'SubroutineNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        return new SubroutineNode(subName, prototype, attributes, block, false, currentIndex);
    }

    /**
     * Validates prototype characters and emits "Illegal character in prototype" warnings.
     * Valid prototype characters: $ @ % & * ; + \ [ ] _
     *
     * @param parser The parser (for location info)
     * @param proto  The prototype string to validate
     * @param subDisplayName The sub name for the warning message (may be null)
     */
    static void emitIllegalProtoWarning(Parser parser, String proto, String subDisplayName) {
        if (proto == null || proto.isEmpty()) return;
        // Check if any character is illegal
        boolean hasIllegal = false;
        for (int i = 0; i < proto.length(); i++) {
            char c = proto.charAt(i);
            if ("$@%&*;+\\[]_ ".indexOf(c) < 0) {
                hasIllegal = true;
                break;
            }
        }
        if (hasIllegal) {
            String name = subDisplayName != null ? subDisplayName : "?";
            String msg = "Illegal character in prototype for " + name + " : " + proto;
            String loc = parser.ctx.errorUtil.warningLocation(parser.tokenIndex);
            Warnings.warnWithCategory("illegalproto", msg, loc);
        }
    }

    /**
     * Checks if a token text is a statement modifier keyword.
     * These keywords cannot start an indirect method call, so a lexical sub
     * followed by one of these should be treated as a function call.
     */
    private static boolean isStatementModifierKeyword(String text) {
        return switch (text) {
            case "if", "unless", "while", "until", "for", "foreach", "when" -> true;
            default -> false;
        };
    }
}
