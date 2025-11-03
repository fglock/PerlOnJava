package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
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
        String lexicalKey = "&" + subName;
        SymbolTable.SymbolEntry lexicalEntry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);
        if (lexicalEntry != null && lexicalEntry.ast() instanceof OperatorNode varNode) {
            LexerToken nextToken = peek(parser);
            
            // Check if there's a prototype stored for this lexical sub
            String lexicalPrototype = varNode.getAnnotation("prototype") != null ? 
                (String) varNode.getAnnotation("prototype") : null;
            
            // Use lexical sub when:
            // 1. There are explicit parentheses, OR
            // 2. There's no prototype (no ambiguity), OR
            // 3. The next token isn't a bareword identifier (to avoid indirect method call confusion)
            boolean useExplicitParen = nextToken.text.equals("(");
            boolean hasPrototype = lexicalPrototype != null;
            boolean nextIsIdentifier = nextToken.type == LexerTokenType.IDENTIFIER;
            
            if (useExplicitParen || hasPrototype || !nextIsIdentifier) {
                // This is a lexical sub/method - use the hidden variable instead of package lookup
                // The varNode is the "my $name__lexsub_123" or "my $name__lexmethod_123" variable
                
                // Parse arguments using prototype if available
                ListNode arguments;
                if (useExplicitParen) {
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
                    List<Node> argList = ListParser.parseList(parser, ")", 0);
                    arguments = new ListNode(argList, parser.tokenIndex);
                } else if (hasPrototype) {
                    // Use prototype to parse arguments
                    arguments = consumeArgsWithPrototype(parser, lexicalPrototype);
                } else {
                    // No parentheses, no prototype, no arguments
                    arguments = new ListNode(parser.tokenIndex);
                }
                
                // Return a call to the hidden variable using &$hiddenVar(arguments) syntax
                // The varNode contains the variable declaration (my/state/our $hiddenVarName)
                // Extract the variable name and create a reference to it
                OperatorNode myDecl = varNode;
                if (myDecl.operand instanceof OperatorNode dollarOp && "$".equals(dollarOp.operator)) {
                    if (dollarOp.operand instanceof IdentifierNode hiddenVarId) {
                        // Create a fresh variable reference: $hiddenVarName
                        OperatorNode freshDollarOp = new OperatorNode("$", 
                            new IdentifierNode(hiddenVarId.name, currentIndex), currentIndex);
                        // Create the dereference: &$hiddenVarName
                        OperatorNode ampersandDeref = new OperatorNode("&", freshDollarOp, currentIndex);
                        return new BinaryOperatorNode("(",
                                ampersandDeref,
                                arguments,
                                currentIndex);
                    }
                }
            }
        }

        // Normalize the subroutine name to include the current package
        String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        // Check if we are parsing a method;
        // Otherwise, check that the subroutine exists in the global namespace - then fetch prototype and attributes
        // Special case: For method calls to 'new', don't require existence check (for generated constructors)
        boolean isNewMethod = isMethod && subName.equals("new");
        boolean subExists = isNewMethod || (!isMethod && GlobalVariable.existsGlobalCodeRef(fullName));
        String prototype = null;
        List<String> attributes = null;
        if (!isNewMethod && subExists) {
            // Fetch the subroutine reference
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
            if (codeRef.value == null) {
                // subExists = false;
            } else {
                prototype = ((RuntimeCode) codeRef.value).prototype;
                attributes = ((RuntimeCode) codeRef.value).attributes;
            }
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

            // Check if the packageName is not a subroutine or operator
            String fullName1 = NameNormalizer.normalizeVariableName(packageName, parser.ctx.symbolTable.getCurrentPackage());
            if (!GlobalVariable.existsGlobalCodeRef(fullName1) && isValidIndirectMethod(packageName)) {
                LexerToken token = peek(parser);
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
            // System.out.println("maybe indirect object call");
            ListNode arguments = consumeArgsWithPrototype(parser, "$");
            int index = parser.tokenIndex;
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
        }

        // Initialize the prototype node to null. This will store the prototype of the subroutine if it exists.
        String prototype = null;

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();
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

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Save the current subroutine context and set the new one
        String previousSubroutine = parser.ctx.symbolTable.getCurrentSubroutine();

        // Set the current subroutine name (use empty string for anonymous subs)
        parser.ctx.symbolTable.setCurrentSubroutine(subName != null ? subName : "");

        try {
            // Parse the block of the subroutine, which contains the actual code.
            BlockNode block = ParseBlock.parseBlock(parser);

            // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

            // Insert signature code in the block
            if (signature != null) {
                block.elements.addAll(0, signature.elements);
            }

            if (subName == null) {
                return handleAnonSub(parser, subName, prototype, attributes, block, currentIndex);
            } else {
                return handleNamedSub(parser, subName, prototype, attributes, block);
            }
        } finally {
            // Restore the previous subroutine context
            parser.ctx.symbolTable.setCurrentSubroutine(previousSubroutine);
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

    public static ListNode handleNamedSub(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block) {
        return handleNamedSubWithFilter(parser, subName, prototype, attributes, block, false);
    }
    
    public static ListNode handleNamedSubWithFilter(Parser parser, String subName, String prototype, List<String> attributes, BlockNode block, boolean filterLexicalMethods) {
        // - register the subroutine in the namespace
        String fullName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
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
        // Create a filtered snapshot that excludes field declarations
        // Fields cause bytecode generation issues when present in the symbol table
        org.perlonjava.symbols.ScopedSymbolTable filteredSnapshot = new org.perlonjava.symbols.ScopedSymbolTable();
        filteredSnapshot.enterScope();
        
        // Copy all visible variables except field declarations
        Map<Integer, org.perlonjava.symbols.SymbolTable.SymbolEntry> visibleVars = parser.ctx.symbolTable.getAllVisibleVariables();
        for (org.perlonjava.symbols.SymbolTable.SymbolEntry entry : visibleVars.values()) {
            // Skip field declarations when creating snapshot for bytecode generation
            if (!entry.decl().equals("field")) {
                filteredSnapshot.addVariable(entry.name(), entry.decl(), entry.ast());
            }
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
            // Generate bytecode and load into a Class object
            Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(newCtx, block, false);

            try {
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
            } catch (Exception e) {
                // Handle any exceptions during subroutine creation
                throw new PerlCompilerException("Subroutine error: " + e.getMessage());
            }

            // Clear the compilerThread once done
            code.compilerSupplier = null;
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
