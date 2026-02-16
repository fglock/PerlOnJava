package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.mro.InheritanceResolver;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.SymbolTable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import static org.perlonjava.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.parser.ParserTables.INFIX_OP;
import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.parser.SignatureParser.parseSignature;
import static org.perlonjava.parser.TokenUtils.peek;

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
        parser.ctx.logDebug("SubroutineCall subName `" + subName + "` package " + parser.ctx.symbolTable.getCurrentPackage());
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
                // 4. We're parsing a code reference for sort/map/grep (parsingForLoopVariable is true)
                boolean useExplicitParen = nextToken.text.equals("(");
                boolean hasPrototype = lexicalPrototype != null;
                boolean nextIsIdentifier = nextToken.type == LexerTokenType.IDENTIFIER;
                
                if (useExplicitParen || hasPrototype || !nextIsIdentifier || parser.parsingForLoopVariable) {
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
                subExists = runtimeCode.methodHandle != null
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
        parser.ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "` attributes " + attributes);

        boolean prototypeHasGlob = prototype != null && prototype.contains("*");

        // If a package name follows, then it looks like a indirect method
        // Unless the subName looks like an operator
        // Unless the subName has a prototype with `*`
        if (peek(parser).type == LexerTokenType.IDENTIFIER && isValidIndirectMethod(subName) && !prototypeHasGlob) {
            int currentIndex2 = parser.tokenIndex;
            String packageName = IdentifierParser.parseSubroutineIdentifier(parser);
            // System.out.println("maybe indirect object: " + packageName + "->" + subName);

            // PERL RULE: Indirect object syntax requires identifier to be a package
            // Check packageExistsCache which is populated when 'package' statement is parsed
            // Note: packageExistsCache uses the package name as-is, not normalized
            Boolean isPackage = GlobalVariable.packageExistsCache.get(packageName);
            LexerToken token = peek(parser);
            String fullName1 = NameNormalizer.normalizeVariableName(packageName, parser.ctx.symbolTable.getCurrentPackage());
            boolean isLexicalSub = parser.ctx.symbolTable.getSymbolEntry("&" + packageName) != null;
            boolean isKnownSub = false;
            if (GlobalVariable.existsGlobalCodeRef(fullName1)) {
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName1);
                if (codeRef.value instanceof RuntimeCode runtimeCode) {
                    isKnownSub = runtimeCode.methodHandle != null
                            || runtimeCode.compilerSupplier != null
                            || runtimeCode.isBuiltin
                            || runtimeCode.prototype != null
                            || runtimeCode.attributes != null;
                }
            }
            
            // Reject if:
            // 1. Explicitly marked as non-package (false in cache), OR
            // 2. Unknown package (null) AND unknown subroutine (!isKnownSub) AND followed by '('
            //    - this is a function call like mycan(...)
            // Allow if:
            // - Marked as package (true), OR
            // - Unknown (null) but NOT followed by '(' - like 'new NonExistentClass'
            if ((isPackage != null && !isPackage) || (isPackage == null && !isKnownSub && token.text.equals("("))) {
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
            boolean infixOp = nextTok.type == LexerTokenType.OPERATOR
                    && (INFIX_OP.contains(nextTok.text)
                        || nextTok.text.equals("?")
                        || nextTok.text.equals(":"));
            if (!terminator
                    && !infixOp
                    && nextTok.type != LexerTokenType.IDENTIFIER
                    && !nextTok.text.equals("->")
                    && !nextTok.text.equals("=>")) {
                ListNode arguments = consumeArgsWithPrototype(parser, "@");
                
                // Check if this is indirect object syntax like "s2 $f"
                if (arguments.elements.size() > 0) {
                    Node firstArg = arguments.elements.get(0);
                    if (firstArg instanceof OperatorNode opNode && opNode.operator.equals("$")) {
                        Node object = firstArg;
                        // Create method call: object->method()
                        // Need to wrap the method name like other method calls do
                        Node methodCall = new BinaryOperatorNode("(",
                                new OperatorNode("&", nameNode, currentIndex),
                                new ListNode(currentIndex),
                                currentIndex);
                        return new BinaryOperatorNode("->", object, methodCall, currentIndex);
                    }
                }
                
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
        return !CORE_PROTOTYPES.containsKey(subName) && !subName.startsWith("CORE::");
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
            // This helps indirect object detection distinguish subs from packages
            if (subName != null) {
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
        
        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (peek(parser).text.equals(":")) {
            prototype = consumeAttributes(parser, attributes);
        }

        ListNode signature = null;

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (peek(parser).text.equals("(")) {
            if (parser.ctx.symbolTable.isFeatureCategoryEnabled("signatures")) {
                parser.ctx.logDebug("Signatures feature enabled");
                // If the signatures feature is enabled, we parse a signature.
                signature = parseSignature(parser, subName);
                parser.ctx.logDebug("Signature AST: " + signature);
                parser.ctx.logDebug("next token " + peek(parser));
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

                // While there are attributes after the prototype (denoted by a colon ':'), we keep parsing them.
                while (peek(parser).text.equals(":")) {
                    consumeAttributes(parser, attributes);
                }
            }
        }

        if (wantName && subName != null && !peek(parser).text.equals("{")) {
            // A named subroutine can be predeclared without a block of code.
            String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = (RuntimeCode) GlobalVariable.getGlobalCodeRef(fullName).value;
            codeRef.prototype = prototype;
            codeRef.attributes = attributes;
            // return an empty AST list
            return new ListNode(parser.tokenIndex);
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
        parser.ctx.symbolTable.setCurrentSubroutine(subName != null ? subName : "");
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
            // Restore the previous subroutine context
            parser.ctx.symbolTable.setCurrentSubroutine(previousSubroutine);
            parser.ctx.symbolTable.setInSubroutineBody(previousInSubroutineBody);
        }
    }

    static String consumeAttributes(Parser parser, List<String> attributes) {
        // Consume the colon
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");

        if (parser.tokens.get(parser.tokenIndex).text.equals("=")) {
            parser.throwError("Use of := for an empty attribute list is not allowed");
        }
        if (peek(parser).text.equals("=")) {
            return null;
        }

        String prototype = null;

        String attrString = TokenUtils.consume(parser, LexerTokenType.IDENTIFIER).text;
        if (parser.tokens.get(parser.tokenIndex).text.equals("(")) {
            String argString = ((StringNode) StringParser.parseRawString(parser, "q")).value;

            if (attrString.equals("prototype")) {
                //  :prototype($)
                prototype = argString;
            }

            attrString += "(" + argString + ")";
        }

        // Consume the attribute name (an identifier) and add it to the attributes list.
        attributes.add(attrString);
        return prototype;
    }

    public static ListNode handleNamedSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, String declaration) {
        return handleNamedSubWithFilter(parser, subName, prototype, attributes, block, false, declaration);
    }
    
    public static ListNode handleNamedSubWithFilter(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, boolean filterLexicalMethods, String declaration) {
        // Check if there's a lexical forward declaration (our/my/state sub name;) that this definition should fulfill
        String lexicalKey = "&" + subName;
        org.perlonjava.symbols.SymbolTable.SymbolEntry lexicalEntry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);
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
                    
                    // Return empty list since the assignment already executed
                    return new ListNode(parser.tokenIndex);
                }
            }
        }
        
        // - register the subroutine in the namespace
        String fullName = NameNormalizer.normalizeVariableName(subName, packageToUse);
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
        InheritanceResolver.invalidateCache();
        if (codeRef.value == null) {
            codeRef.type = RuntimeScalarType.CODE;
            codeRef.value = new RuntimeCode(subName, attributes);
        }

        RuntimeCode code = (RuntimeCode) codeRef.value;
        code.prototype = prototype;
        code.attributes = attributes;
        code.subName = subName;
        code.packageName = parser.ctx.symbolTable.getCurrentPackage();

        // Optimization - https://github.com/fglock/PerlOnJava/issues/8
        // Prepare capture variables
        Map<Integer, SymbolTable.SymbolEntry> outerVars = parser.ctx.symbolTable.getAllVisibleVariables();
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
                
                String variableName = null;
                if (entry.decl().equals("our")) {
                    // Normalize variable name for 'our' declarations
                    variableName = NameNormalizer.normalizeVariableName(
                            entry.name().substring(1),
                            entry.perlPackage());
                } else {
                    // Handle "my" or "state" variables which live in a special BEGIN package
                    // Retrieve the variable id from the AST; create a new id if needed
                    OperatorNode ast = entry.ast();
                    if (ast.id == 0) {
                        ast.id = EmitterMethodCreator.classCounter++;
                    }
                    // Normalize variable name for 'my' or 'state' declarations
                    variableName = NameNormalizer.normalizeVariableName(
                            entry.name().substring(1),
                            PersistentVariable.beginPackage(ast.id));
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
        org.perlonjava.symbols.ScopedSymbolTable filteredSnapshot = new org.perlonjava.symbols.ScopedSymbolTable();
        filteredSnapshot.enterScope();
        
        // Copy all visible variables except field declarations and code references
        Map<Integer, org.perlonjava.symbols.SymbolTable.SymbolEntry> visibleVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (org.perlonjava.symbols.SymbolTable.SymbolEntry entry : visibleVars.values()) {
            // Skip field declarations when creating snapshot for bytecode generation
            if (entry.decl().equals("field")) {
                continue;
            }
            // Skip code references (subroutines) - they should not be captured as closure variables
            String sigil = entry.name().substring(0, 1);
            if (sigil.equals("&")) {
                continue;
            }
            filteredSnapshot.addVariable(entry.name(), entry.decl(), entry.ast());
        }
        
        // Clone the current package
        filteredSnapshot.setCurrentPackage(parser.ctx.symbolTable.getCurrentPackage(), 
                parser.ctx.symbolTable.currentPackageIsClass());
        
        // Clone the current subroutine
        filteredSnapshot.setCurrentSubroutine(parser.ctx.symbolTable.getCurrentSubroutine());
        
        // Clone warning flags (critical for 'no warnings' pragmas)
        filteredSnapshot.warningFlagsStack.pop(); // Remove the initial value pushed by enterScope
        filteredSnapshot.warningFlagsStack.push(parser.ctx.symbolTable.warningFlagsStack.peek());
        
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

        // Encapsulate the subroutine creation task in a Supplier
        Supplier<Void> subroutineCreationTaskSupplier = () -> {
            // Generate bytecode using unified API (returns RuntimeCode - either CompiledCode or InterpretedCode)
            org.perlonjava.runtime.RuntimeCode runtimeCode =
                EmitterMethodCreator.createRuntimeCode(newCtx, block, false);

            try {
                // Check if we got CompiledCode or InterpretedCode
                if (runtimeCode instanceof org.perlonjava.codegen.CompiledCode) {
                    // CompiledCode path - use reflection as before
                    org.perlonjava.codegen.CompiledCode compiledCode =
                        (org.perlonjava.codegen.CompiledCode) runtimeCode;
                    Class<?> generatedClass = compiledCode.generatedClass;

                    // Prepare constructor with the captured variable types
                    Class<?>[] parameterTypes = classList.toArray(new Class<?>[0]);
                    Constructor<?> constructor = generatedClass.getConstructor(parameterTypes);

                    // Instantiate the subroutine with the captured variables
                    Object[] parameters = paramList.toArray();
                    code.codeObject = constructor.newInstance(parameters);

                    // Retrieve the 'apply' method from the generated class
                    code.methodHandle = RuntimeCode.lookup.findVirtual(generatedClass, "apply", RuntimeCode.methodType);

                    // Set the __SUB__ instance field to codeRef
                    Field field = code.codeObject.getClass().getDeclaredField("__SUB__");
                    field.set(code.codeObject, codeRef);
                } else if (runtimeCode instanceof org.perlonjava.interpreter.InterpretedCode) {
                    // InterpretedCode path - replace the RuntimeCode object with InterpretedCode
                    org.perlonjava.interpreter.InterpretedCode interpretedCode =
                        (org.perlonjava.interpreter.InterpretedCode) runtimeCode;

                    System.err.println("DEBUG: Got InterpretedCode for subroutine " + code.subName);

                    // Set captured variables if there are any
                    if (!paramList.isEmpty()) {
                        System.err.println("DEBUG: Setting " + paramList.size() + " captured variables");
                        Object[] parameters = paramList.toArray();
                        org.perlonjava.runtime.RuntimeBase[] capturedVars =
                            new org.perlonjava.runtime.RuntimeBase[parameters.length];
                        for (int i = 0; i < parameters.length; i++) {
                            capturedVars[i] = (org.perlonjava.runtime.RuntimeBase) parameters[i];
                        }
                        interpretedCode = interpretedCode.withCapturedVars(capturedVars);
                    }

                    // Replace codeRef.value with the InterpretedCode instance
                    // This allows polymorphic dispatch to work correctly
                    interpretedCode.prototype = code.prototype;
                    interpretedCode.attributes = code.attributes;
                    interpretedCode.subName = code.subName;
                    interpretedCode.packageName = code.packageName;

                    System.err.println("DEBUG: Replacing codeRef.value for " + code.subName);
                    System.err.println("DEBUG: Before: codeRef.value = " + codeRef.value);
                    codeRef.value = interpretedCode;
                    System.err.println("DEBUG: After: codeRef.value = " + codeRef.value);
                    System.err.println("DEBUG: InterpretedCode.defined() = " + interpretedCode.defined());
                }
            } catch (Exception e) {
                // Handle any exceptions during subroutine creation
                throw new PerlCompilerException("Subroutine error: " + e.getMessage());
            }

            // Clear the compilerThread once done
            code.compilerSupplier = null;
            System.err.println("DEBUG: Cleared compilerSupplier for " + code.subName);
            System.err.println("DEBUG: code object is now: " + code);
            System.err.println("DEBUG: code.defined() = " + code.defined());
            return null;
        };

        // Store the supplier for later execution
        code.compilerSupplier = subroutineCreationTaskSupplier;


        // return an empty AST list
        return new ListNode(parser.tokenIndex);
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

}
