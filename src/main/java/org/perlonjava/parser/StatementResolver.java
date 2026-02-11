package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import java.util.Arrays;
import org.perlonjava.codegen.ByteCodeSourceMapper;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.OperatorParser.dieWarnNode;
import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The StatementResolver class is responsible for parsing individual statements in the source code.
 * It handles various types of statements such as control structures, declarations, and expressions.
 */
public class StatementResolver {

    /**
     * Parses a single statement from the parser's token stream.
     *
     * @param parser The parser instance used for parsing.
     * @param label  The label for the statement, if any.
     * @return A Node representing the parsed statement.
     */
    public static Node parseStatement(Parser parser, String label) {
        int currentIndex = parser.tokenIndex;
        LexerToken token = peek(parser);
        parser.ctx.logDebug("parseStatement `" + token.text + "`");

        // Store the current source location - this will be used for stack trace generation
        ByteCodeSourceMapper.saveSourceLocation(parser.ctx, parser.tokenIndex);

        if (token.type == LexerTokenType.IDENTIFIER) {
            Node result = switch (token.text) {
                case "CHECK", "INIT", "UNITCHECK", "BEGIN", "END", "ADJUST" -> {
                    // Check if next token is '{' - if not, this might be a lexical sub call
                    parser.tokenIndex++;
                    if (peek(parser).text.equals("{")) {
                        parser.tokenIndex = currentIndex;
                        yield SpecialBlockParser.parseSpecialBlock(parser);
                    }
                    // Not a special block, backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                case "AUTOLOAD", "DESTROY" -> {
                    parser.tokenIndex++;
                    if (peek(parser).text.equals("{")) {
                        parser.tokenIndex = currentIndex;  // point to the subroutine name
                        yield SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                case "if", "unless" -> StatementParser.parseIfStatement(parser);

                case "for", "foreach" -> StatementParser.parseForStatement(parser, label);

                case "while", "until" -> StatementParser.parseWhileStatement(parser, label);

                case "given" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("switch")
                        ? StatementParser.parseGivenStatement(parser)
                        : null;

                case "when" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("switch")
                        ? StatementParser.parseWhenStatement(parser)
                        : null;

                case "default" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("switch")
                        ? StatementParser.parseDefaultStatement(parser)
                        : null;

                case "try" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("try")
                        ? StatementParser.parseTryStatement(parser)
                        : null;

                case "package" -> StatementParser.parsePackageDeclaration(parser, token);

                case "class" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class")
                        ? StatementParser.parsePackageDeclaration(parser, token)
                        : null;

                case "use", "no" -> StatementParser.parseUseDeclaration(parser, token);

                case "sub" -> {
                    parser.tokenIndex++;
                    LexerToken nextToken = peek(parser);
                    if (nextToken.type == LexerTokenType.IDENTIFIER || 
                        nextToken.text.equals("'") || 
                        nextToken.text.equals("::")) {
                        yield SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                case "field" -> {
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("class")) {
                        yield FieldParser.parseFieldDeclaration(parser);
                    }
                    yield null;
                }

                case "method" -> {
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("class")) {
                        // Parse method more directly
                        parser.tokenIndex++;  // consume "method"

                        // Get method name
                        LexerToken nameToken = peek(parser);
                        String methodName = null;
                        if (nameToken.type == LexerTokenType.IDENTIFIER) {
                            methodName = nameToken.text;
                            consume(parser);
                        }

                        // Parse signature if present (optional)
                        String prototype = null;
                        ListNode signatureAST = null;
                        if (peek(parser).text.equals("(")) {
                            // Parse the signature properly to generate parameter declarations
                            // Pass true for isMethod flag to account for implicit $self in error messages
                            signatureAST = SignatureParser.parseSignature(parser, methodName, true);
                            // Note: SignatureParser consumes the closing )
                        }

                        // Check for forward declaration (method name;) or full definition (method name {...})
                        if (peek(parser).text.equals(";")) {
                            // Forward declaration - just consume the semicolon
                            consume(parser, LexerTokenType.OPERATOR, ";");

                            // Create a method stub with empty body for forward declaration
                            // The actual implementation will be provided later
                            BlockNode emptyBlock = new BlockNode(new ArrayList<>(), parser.tokenIndex);
                            SubroutineNode method = new SubroutineNode(
                                    methodName,
                                    prototype,
                                    null,  // attributes
                                    emptyBlock,
                                    false, // useTryCatch
                                    parser.tokenIndex
                            );
                            method.setAnnotation("isMethod", true);
                            method.setAnnotation("isForwardDeclaration", true);

                            // Store signature if present
                            if (signatureAST != null) {
                                method.setAnnotation("signatureAST", signatureAST);
                            }

                            yield method;
                        } else if (peek(parser).text.equals("{")) {
                            // Full method definition with block
                            consume(parser, LexerTokenType.OPERATOR, "{");
                            boolean wasInMethod = parser.isInMethod;
                            parser.isInMethod = true; // Set method context
                            BlockNode block = ParseBlock.parseBlock(parser);
                            parser.isInMethod = wasInMethod; // Restore previous context
                            consume(parser, LexerTokenType.OPERATOR, "}");

                            // Create subroutine node
                            SubroutineNode method = new SubroutineNode(
                                    methodName,
                                    prototype,
                                    null,  // attributes
                                    block,
                                    false, // useTryCatch
                                    parser.tokenIndex
                            );
                            method.setAnnotation("isMethod", true);

                            // If we have a signature, store it in the method for ClassTransformer to handle
                            // The signature AST needs to go AFTER $self = shift (added by ClassTransformer)
                            if (signatureAST != null && !signatureAST.elements.isEmpty()) {
                                method.setAnnotation("signatureAST", signatureAST);
                            }
                            yield method;
                        }
                    }
                    yield null;
                }

                case "our", "my", "state" -> {
                    String declaration = consume(parser).text;
                    LexerToken nextToken = peek(parser);

                    if (nextToken.text.equals("sub")) {
                        consume(parser); // consume "sub"
                        LexerToken nameToken = peek(parser);

                        if (nameToken.type == LexerTokenType.IDENTIFIER) {
                            String subName = consume(parser).text;
                            int subNameIndex = parser.tokenIndex - 1; // Save the token index of the sub name

                            if (declaration.equals("our")) {
                                // our sub works like our var - it creates a package sub AND a lexical alias
                                // The lexical alias stores the fully qualified name so it always resolves
                                // to the correct package sub regardless of the current package
                                
                                // Parse as normal package sub
                                parser.tokenIndex--; // back up to just after "sub"
                                
                                Node packageSub = SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                                
                                // Store the fully qualified name in the symbol table
                                // This allows calls to resolve to the correct package sub even after package switch
                                String fullSubName = NameNormalizer.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());
                                OperatorNode marker = new OperatorNode("our",
                                        new OperatorNode("&", new IdentifierNode(subName, subNameIndex), subNameIndex),
                                        subNameIndex);
                                marker.setAnnotation("isOurSub", true);
                                marker.setAnnotation("fullSubName", fullSubName);  // Store the full qualified name!
                                parser.ctx.symbolTable.addVariable("&" + subName, "our", marker);
                                
                                // Return the package sub
                                yield packageSub;
                            } else {
                                // my sub or state sub - lexical only, not in package
                                // Generate unique hidden variable name
                                String hiddenVarName = subName + "__lexsub_" + parser.tokenIndex;

                                // Create the declaration: my/state $hiddenVarName
                                // First create the inner operand (the $hiddenVarName part)
                                OperatorNode innerVarNode = new OperatorNode("$", new IdentifierNode(hiddenVarName, parser.tokenIndex), parser.tokenIndex);
                                
                                // For state variables, assign a unique ID for persistent tracking
                                if (declaration.equals("state")) {
                                    innerVarNode.id = EmitterMethodCreator.classCounter++;
                                }
                                
                                // Now create the outer declaration node (state/my $hiddenVarName)
                                OperatorNode varDecl = new OperatorNode(declaration, innerVarNode, parser.tokenIndex);

                                // Store the hidden variable name and declaring package as annotations for lookup
                                varDecl.setAnnotation("hiddenVarName", hiddenVarName);
                                varDecl.setAnnotation("declaringPackage", parser.ctx.symbolTable.getCurrentPackage());

                                // IMPORTANT: Manually add the hidden variable to the symbol table
                                // Since we're returning an assignment node, parseVariableDeclaration won't be called again
                                // So we need to register both the sub name (&p) and the hidden variable ($p__lexsub_N)
                                
                                // IMPORTANT: Add the hidden variable NOW (before parsing body)
                                // But delay adding &subName until AFTER parsing the body to make the sub "invisible inside itself"
                                
                                // For my/state subs: If there's already a forward declaration, we need to handle it
                                SymbolTable.SymbolEntry existingEntry = parser.ctx.symbolTable.getSymbolEntry("&" + subName);
                                boolean hadForwardDecl = existingEntry != null;
                                
                                // Add the hidden variable immediately (needed for state variable tracking)
                                if (hadForwardDecl) {
                                    parser.ctx.symbolTable.replaceVariable("$" + hiddenVarName, declaration, innerVarNode);
                                } else {
                                    parser.ctx.symbolTable.addVariable("$" + hiddenVarName, declaration, innerVarNode);
                                }
                                
                                // DO NOT add &subName yet - it will be added after parsing the body

                                // Check if this is a forward declaration or a full definition
                                // Look ahead: after optional attributes (:attr) and prototype (...), is there a body {...}?
                                boolean hasBody = false;
                                String prototype = null;
                                List<String> attributes = new ArrayList<>();
                                
                                // Parse attributes first (e.g., :prototype())
                                while (peek(parser).text.equals(":")) {
                                    String attrProto = SubroutineParser.consumeAttributes(parser, attributes);
                                    if (attrProto != null) {
                                        prototype = attrProto;
                                    }
                                }
                                
                                // Then check for prototype/signature
                                // When signatures are enabled, we need to look ahead:
                                // - (...) { ... } means signature + body
                                // - (...) ; means prototype (forward declaration)
                                if (prototype == null && peek(parser).text.equals("(")) {
                                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("signatures")) {
                                        // Look ahead to see if there's a body after the parens
                                        int savedIndex = parser.tokenIndex;
                                        StringParser.parseRawString(parser, "q"); // consume the parens
                                        boolean hasBodyAfterParens = peek(parser).text.equals("{");
                                        parser.tokenIndex = savedIndex; // restore position
                                        
                                        if (!hasBodyAfterParens) {
                                            // Forward declaration: (...) ; - parse as prototype
                                            prototype = ((StringNode) StringParser.parseRawString(parser, "q")).value;
                                        }
                                        // else: leave (...) for parseSubroutineDefinition to handle as signature
                                    } else {
                                        // Signatures not enabled - always parse as prototype
                                        prototype = ((StringNode) StringParser.parseRawString(parser, "q")).value;
                                    }
                                }
                                
                                // Now check if there's a body
                                // When signatures are enabled, (...) followed by { also indicates a body
                                String peekText = peek(parser).text;
                                hasBody = peekText.equals("{") || 
                                         (peekText.equals("(") && parser.ctx.symbolTable.isFeatureCategoryEnabled("signatures"));

                                if (hasBody) {
                                    // Full definition: my sub name {...} or my sub name (...) {...}
                                    // Parse the rest as an anonymous sub
                                    Node anonSub = SubroutineParser.parseSubroutineDefinition(parser, false, null);

                                    // Store prototype in the sub if present
                                    if (prototype != null && anonSub instanceof SubroutineNode subNode) {
                                        varDecl.setAnnotation("prototype", prototype);
                                    }

                                    // NOW add &subName to symbol table AFTER parsing the body
                                    // This makes the sub "invisible inside itself" during compilation
                                    if (hadForwardDecl) {
                                        parser.ctx.symbolTable.replaceVariable("&" + subName, declaration, varDecl);
                                    } else {
                                        parser.ctx.symbolTable.addVariable("&" + subName, declaration, varDecl);
                                    }

                                    // Create assignment: $hiddenVarName = sub {...}
                                    // We need to create a reference to the already-declared variable
                                    // Use a fully qualified name to ensure it resolves correctly regardless of package context
                                    String declaringPackage = parser.ctx.symbolTable.getCurrentPackage();
                                    String qualifiedHiddenVarName = declaringPackage + "::" + hiddenVarName;
                                    
                                    OperatorNode varRef = new OperatorNode("$", new IdentifierNode(qualifiedHiddenVarName, parser.tokenIndex), parser.tokenIndex);
                                    // For state variables, copy the ID so runtime can track the state
                                    if (declaration.equals("state")) {
                                        varRef.id = innerVarNode.id;
                                    }
                                    BinaryOperatorNode assignment = new BinaryOperatorNode("=", varRef, anonSub, parser.tokenIndex);

                                    // Check if we're inside a subroutine
                                    String currentSub = parser.ctx.symbolTable.getCurrentSubroutine();
                                    boolean insideSubroutine = currentSub != null && !currentSub.isEmpty();
                                    
                                    if (declaration.equals("state") || !insideSubroutine) {
                                        // For state sub: Execute assignment immediately during parsing (like a BEGIN block)
                                        // This is crucial for cases like: state sub foo{...}; use overload => \&foo;
                                        // The closure is created once and reused across all calls
                                        //
                                        // For my sub at FILE SCOPE: Also execute at compile time so that
                                        // use statements (like use overload) can access the sub reference
                                        BlockNode beginBlock = new BlockNode(new ArrayList<>(List.of(assignment)), parser.tokenIndex);
                                        SpecialBlockParser.runSpecialBlock(parser, "BEGIN", beginBlock);
                                        
                                        // Return empty list since the assignment already executed
                                        yield new ListNode(parser.tokenIndex);
                                    } else {
                                        // For my sub INSIDE ANOTHER SUB: Return the assignment to be executed at runtime
                                        // This creates a fresh closure each time the enclosing scope is entered,
                                        // correctly capturing the current values of closure variables
                                        yield assignment;
                                    }
                                } else {
                                    // Forward declaration: my sub name; or my sub name ($);
                                    // For forward declarations, add &subName immediately since there's no body to be invisible in
                                    if (hadForwardDecl) {
                                        parser.ctx.symbolTable.replaceVariable("&" + subName, declaration, varDecl);
                                    } else {
                                        parser.ctx.symbolTable.addVariable("&" + subName, declaration, varDecl);
                                    }
                                    
                                    if (prototype != null) {
                                        // Store prototype in varDecl annotation
                                        varDecl.setAnnotation("prototype", prototype);
                                        
                                        // Create a stub RuntimeCode with the prototype set
                                        // This is needed so that prototype(\&sub) works for forward declarations
                                        String declaringPackage = parser.ctx.symbolTable.getCurrentPackage();
                                        String qualifiedHiddenVarName = declaringPackage + "::" + hiddenVarName;
                                        
                                        // Create a subroutine stub with the prototype that returns undef
                                        // The body needs at least a return statement to avoid bytecode issues
                                        List<Node> stubBody = new ArrayList<>();
                                        stubBody.add(new OperatorNode("undef", null, parser.tokenIndex));
                                        SubroutineNode stubSub = new SubroutineNode(
                                                null,  // anonymous
                                                prototype,
                                                attributes.isEmpty() ? null : attributes,
                                                new BlockNode(stubBody, parser.tokenIndex),
                                                false,  // useTryCatch
                                                parser.tokenIndex
                                        );
                                        
                                        // Create assignment: $hiddenVarName = sub { }
                                        OperatorNode varRef = new OperatorNode("$", 
                                                new IdentifierNode(qualifiedHiddenVarName, parser.tokenIndex), 
                                                parser.tokenIndex);
                                        BinaryOperatorNode stubAssignment = new BinaryOperatorNode("=", varRef, stubSub, parser.tokenIndex);
                                        
                                        // Execute the stub assignment in a BEGIN block
                                        BlockNode beginBlock = new BlockNode(new ArrayList<>(List.of(stubAssignment)), parser.tokenIndex);
                                        SpecialBlockParser.runSpecialBlock(parser, "BEGIN", beginBlock);
                                        
                                        // Return empty list since stub was created
                                        yield new ListNode(parser.tokenIndex);
                                    }
                                    // Just declare the variable (no prototype case)
                                    yield varDecl;
                                }
                            }
                        } else {
                            // anonymous sub
                            yield SubroutineParser.parseSubroutineDefinition(parser, false, declaration);
                        }
                    } else if (nextToken.text.equals("method") && parser.ctx.symbolTable.isFeatureCategoryEnabled("class")) {
                        consume(parser); // consume "method"
                        LexerToken nameToken = peek(parser);

                        if (nameToken.type == LexerTokenType.IDENTIFIER) {
                            // my method name {...} -> similar transformation
                            String methodName = consume(parser).text;

                            // Generate unique hidden variable name
                            String hiddenVarName = methodName + "__lexmethod_" + parser.tokenIndex;

                            // Parse signature if present
                            ListNode signatureAST = null;
                            if (peek(parser).text.equals("(")) {
                                // Pass true for isMethod flag to account for implicit $self in error messages
                                signatureAST = SignatureParser.parseSignature(parser, methodName, true);
                            }

                            // Parse the method body
                            BlockNode block = null;
                            if (peek(parser).text.equals("{")) {
                                consume(parser, LexerTokenType.OPERATOR, "{");
                                boolean wasInMethod = parser.isInMethod;
                                parser.isInMethod = true; // Set method context for lexical method
                                
                                // Enter scope for the lexical method's body
                                int scopeIndex = parser.ctx.symbolTable.enterScope();
                                
                                // Add temp $self to THIS scope (the method's inner scope)
                                // so field access works during parsing
                                // This will be matched by the actual `my $self = shift;` injected during transformation
                                OperatorNode tempSelf = new OperatorNode("my",
                                        new OperatorNode("$", new IdentifierNode("self", parser.tokenIndex), parser.tokenIndex),
                                        parser.tokenIndex);
                                parser.ctx.symbolTable.addVariable("$self", "my", tempSelf);
                                
                                // Parse the block contents (without creating another scope)
                                List<Node> elements = new ArrayList<>();
                                while (!peek(parser).text.equals("}")) {
                                    Node stmt = StatementResolver.parseStatement(parser, null);
                                    if (stmt != null) {
                                        elements.add(stmt);
                                    }
                                }
                                block = new BlockNode(elements, parser.tokenIndex);
                                
                                // Exit the method's scope (this removes temp $self)
                                parser.ctx.symbolTable.exitScope(scopeIndex);
                                
                                parser.isInMethod = wasInMethod; // Restore previous context
                                consume(parser, LexerTokenType.OPERATOR, "}");
                            } else if (peek(parser).text.equals(";")) {
                                // Forward declaration
                                consume(parser, LexerTokenType.OPERATOR, ";");
                                block = new BlockNode(new ArrayList<>(), parser.tokenIndex);
                            } else {
                                throw new RuntimeException("Expected '{' or ';' after method declaration");
                            }

                            // Create anonymous method
                            SubroutineNode anonMethod = new SubroutineNode(
                                    null, // anonymous
                                    null, // prototype
                                    null, // attributes
                                    block,
                                    false, // useTryCatch
                                    parser.tokenIndex
                            );
                            anonMethod.setAnnotation("isMethod", true);
                            if (signatureAST != null) {
                                anonMethod.setAnnotation("signatureAST", signatureAST);
                            }

                            // Create AST for: my $hiddenVarName = sub {...}
                            OperatorNode varDecl = new OperatorNode(declaration,
                                    new OperatorNode("$", new IdentifierNode(hiddenVarName, parser.tokenIndex), parser.tokenIndex),
                                    parser.tokenIndex);

                            ListNode declList = new ListNode(parser.tokenIndex);
                            declList.elements.add(varDecl);

                            BinaryOperatorNode assignment = new BinaryOperatorNode("=", declList, anonMethod, parser.tokenIndex);

                            // Store mapping for method calls
                            parser.ctx.symbolTable.addVariable("&" + methodName, declaration, varDecl);

                            yield assignment;
                        } else {
                            throw new RuntimeException("Method name expected after 'my method'");
                        }
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                case "format" -> {
                    consume(parser); // consume "format"
                    String formatName = null;

                    // Check if format name is provided
                    LexerToken nextToken = peek(parser);
                    if (nextToken.type == LexerTokenType.IDENTIFIER
                            || nextToken.text.equals("'")
                            || nextToken.text.equals("::")) {
                        // Accept legacy package separator ' and leading :: like sub names
                        formatName = IdentifierParser.parseSubroutineIdentifier(parser);
                    }

                    // Expect '=' after format name (or after "format" if no name)
                    if (peek(parser).text.equals("=")) {
                        consume(parser); // consume "="
                        yield FormatParser.parseFormatDeclaration(parser, formatName);
                    } else {
                        // Not a format declaration, backtrack
                        parser.tokenIndex = currentIndex;
                        yield null;
                    }
                }

                default -> null;
            };

            if (result != null) {
                return result;
            }
        }

        // Use pattern matching with switch expression for OPERATOR tokens
        if (token.type == LexerTokenType.OPERATOR) {
            Node result = switch (token.text) {
                case "..." -> {
                    TokenUtils.consume(parser);
                    yield dieWarnNode(parser, "die", new ListNode(List.of(
                            new StringNode("Unimplemented", parser.tokenIndex)), parser.tokenIndex));
                }

                case "{" -> {
                    if (!isHashLiteral(parser)) {
                        int scopeIndex = parser.ctx.symbolTable.enterScope();

                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                        BlockNode block = ParseBlock.parseBlock(parser);

                        // Check for closing brace with better error message
                        LexerToken closingToken = TokenUtils.peek(parser);
                        if (closingToken.type == LexerTokenType.EOF) {
                            String fileName = parser.ctx.errorUtil.getFileName();
                            int lineNum = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex);
                            String errorMsg = "Missing right curly or square bracket at " + fileName + " line " + lineNum + ", at end of line\n" +
                                "syntax error at " + fileName + " line " + lineNum + ", at EOF\n" +
                                "Execution of " + fileName + " aborted due to compilation errors.\n";
                            throw new PerlCompilerException(errorMsg);
                        }
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

                        Node continueNode = null;
                        if (TokenUtils.peek(parser).text.equals("continue")) {
                            TokenUtils.consume(parser);
                            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                            continueNode = ParseBlock.parseBlock(parser);
                            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        }

                        parser.ctx.symbolTable.exitScope(scopeIndex);

                        if (label != null && label.equals("SKIP")) {
                            // Use a macro to emulate Test::More SKIP blocks
                            TestMoreHelper.handleSkipTest(parser, block);
                        }

                        yield new For3Node(label,
                                true,
                                null, null,
                                null, block, continueNode,
                                false, true,
                                parser.tokenIndex);
                    }
                    yield null;
                }

                default -> null;
            };

            if (result != null) {
                return result;
            }
        }

        // Parse expressions
        Node expression = parser.parseExpression(0);
        token = peek(parser);

        if (token.type == LexerTokenType.IDENTIFIER) {
            // Handle statement modifiers using switch expression
            return switch (token.text) {
                case "if" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    yield new BinaryOperatorNode("&&", modifierExpression, expression, parser.tokenIndex);
                }

                case "unless" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    yield new BinaryOperatorNode("||", modifierExpression, expression, parser.tokenIndex);
                }

                case "for", "foreach" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);

                    // Special case: "EXPR for $_" when $_ is the list
                    // This is a loop that executes once with $_ aliased to itself
                    // Instead of creating a for loop with complex aliasing,
                    // transform it to a simple loop block: { EXPR }
                    // This avoids the aliasing issue while preserving loop semantics
                    if (modifierExpression instanceof OperatorNode opNode
                            && opNode.operator.equals("$")
                            && opNode.operand instanceof IdentifierNode idNode
                            && (idNode.name.equals("_") || idNode.name.equals("main::_"))) {
                        // Transform "EXPR for $_" to a simple block that executes once
                        // This preserves loop control (last, next, redo) but avoids aliasing
                        yield new BlockNode(List.of(expression), parser.tokenIndex);
                    }

                    // Statement modifier for loop: EXPR for LIST
                    // The emitter will handle localization of $_ if needed
                    yield new For1Node(null, false, scalarUnderscore(parser), modifierExpression, expression, null, parser.tokenIndex);
                }

                case "while", "until" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    boolean isDoWhile = expression instanceof BlockNode;
                    if (isDoWhile) {
                        // Special case: `do { BLOCK } while CONDITION`
                        // Executes the loop at least once
                        parser.ctx.logDebug("do-while " + expression);
                    }
                    yield new For3Node(null,
                            false,
                            null, modifierExpression,
                            null, expression, null,
                            isDoWhile, false,
                            parser.tokenIndex);
                }

                default -> {
                    parser.throwError("Not implemented: " + token);
                    yield null;
                }
            };
        }

        parseStatementTerminator(parser);
        return expression;
    }

    // disambiguate between Block or Hash literal
    public static boolean isHashLiteral(Parser parser) {
        int currentIndex = parser.tokenIndex;

        // Start after the opening '{'
        consume(parser, LexerTokenType.OPERATOR, "{");

        int braceCount = 1; // Track nested braces
        boolean hasHashIndicator = false;  // Found =>, or comma in hash-like context
        boolean hasBlockIndicator = false; // Found ;, or statement modifier
        boolean hasContent = false; // Track if we've seen any content

        parser.ctx.logDebug("isHashLiteral START - initial braceCount: " + braceCount);

        while (braceCount > 0) {
            LexerToken token = consume(parser);
            parser.ctx.logDebug("isHashLiteral token: '" + token.text + "' type:" + token.type + " braceCount:" + braceCount);

            if (token.type == LexerTokenType.EOF) {
                parser.ctx.logDebug("isHashLiteral EOF reached");
                break; // Let caller handle EOF error
            }

            // Track if we've seen any non-brace content
            if (!token.text.equals("}")) {
                hasContent = true;
            }

            // Update brace count based on token
            int oldBraceCount = braceCount;
            braceCount = switch (token.text) {
                case "{", "(", "[" -> braceCount + 1;
                case ")", "}", "]" -> braceCount - 1;
                default -> braceCount;
            };

            if (oldBraceCount != braceCount) {
                parser.ctx.logDebug("isHashLiteral braceCount changed from " + oldBraceCount + " to " + braceCount);
            }

            // Only check for indicators at depth 1
            if (braceCount == 1 && !token.text.matches("[{(\\[)}\\]]")) {
                parser.ctx.logDebug("isHashLiteral checking token '" + token.text + "' at depth 1");

                switch (token.text) {
                    case "=>" -> {
                        // Fat comma is a definitive hash indicator
                        parser.ctx.logDebug("isHashLiteral found => (hash indicator)");
                        hasHashIndicator = true;
                    }
                    case ";" -> {
                        // Semicolon is a definitive block indicator
                        parser.ctx.logDebug("isHashLiteral found ; (block indicator)");
                        hasBlockIndicator = true;
                    }
                    case "=" -> {
                        // Assign is a block indicator, but be more conservative
                        // Look at the tokens we've seen to determine if this might be a q-string pattern
                        boolean isQStringDelimiter = false;
                        // Look back at the tokens we've processed to see if we recently saw a quote-like operator
                        int searchIndex = parser.tokenIndex - 1;
                        while (searchIndex >= currentIndex && searchIndex < parser.tokens.size()) {
                            LexerToken checkToken = parser.tokens.get(searchIndex);
                            parser.ctx.logDebug("isHashLiteral checking token '" + checkToken.text + "' at index " + searchIndex);
                            if (checkToken.type == LexerTokenType.IDENTIFIER && ParsePrimary.isIsQuoteLikeOperator(checkToken.text)) {
                                parser.ctx.logDebug("isHashLiteral found = after quote-like operator '" + checkToken.text + "', treating as q-string delimiter");
                                isQStringDelimiter = true;
                                break;
                            }
                            // If we hit a meaningful token that's not a quote-like operator, stop searching
                            if (checkToken.type != LexerTokenType.OPERATOR || !checkToken.text.equals("=")) {
                                parser.ctx.logDebug("isHashLiteral stopping search at token '" + checkToken.text + "'");
                                break;
                            }
                            searchIndex--;
                        }
                        
                        // Also check if this looks like an assignment by looking at the next token
                        boolean looksLikeAssignment = false;
                        if (parser.tokenIndex < parser.tokens.size()) {
                            LexerToken nextToken = parser.tokens.get(parser.tokenIndex);
                            // Assignment would be followed by a variable, number, string, or expression
                            if (nextToken.type == LexerTokenType.IDENTIFIER && 
                                (nextToken.text.startsWith("$") || nextToken.text.startsWith("@") || nextToken.text.startsWith("%"))) {
                                looksLikeAssignment = true;
                            } else if (nextToken.type == LexerTokenType.NUMBER || nextToken.type == LexerTokenType.STRING) {
                                looksLikeAssignment = true;
                            }
                        }
                        
                        if (!isQStringDelimiter && looksLikeAssignment) {
                            // This looks like an assignment
                            parser.ctx.logDebug("isHashLiteral found = (block indicator)");
                            hasBlockIndicator = true;
                        } else {
                            parser.ctx.logDebug("isHashLiteral found = but not treating as block indicator (q-string or not assignment-like)");
                        }
                    }
                    case "," -> {
                        // Comma alone is not definitive - could be function args or hash
                        // Continue scanning for more evidence
                        parser.ctx.logDebug("isHashLiteral found comma, continuing scan");
                    }
                    case "for", "while", "if", "unless", "until", "foreach", "my", "our", "say", "print", "local" -> {
                        // Check if this is a hash key (followed by =>) or statement modifier
                        LexerToken nextToken = TokenUtils.peek(parser);
                        parser.ctx.logDebug("isHashLiteral found keyword '" + token.text + "', next token: '" + nextToken.text + "'");
                        if (!nextToken.text.equals("=>") && !nextToken.text.equals(",")) {
                            // Statement modifier - definitive block indicator
                            parser.ctx.logDebug("isHashLiteral found statement modifier (block indicator)");
                            hasBlockIndicator = true;
                        } else {
                            parser.ctx.logDebug("isHashLiteral keyword followed by => or , (possible hash key)");
                        }
                    }
                    default -> {
                        parser.ctx.logDebug("isHashLiteral token '" + token.text + "' not a special indicator");
                    }
                }
            } else if (braceCount == 1) {
                parser.ctx.logDebug("isHashLiteral skipping bracket token '" + token.text + "' at depth 1");
            }

            // Early exit if we have definitive evidence
            if (hasBlockIndicator) {
                parser.ctx.logDebug("isHashLiteral EARLY EXIT - block indicator found, returning FALSE");
                parser.tokenIndex = currentIndex;
                return false;
            }

            if (braceCount == 0) {
                parser.ctx.logDebug("isHashLiteral braceCount reached 0, exiting loop");
            }
        }

        parser.tokenIndex = currentIndex;

        // Decision logic:
        // - If we found => it's definitely a hash
        // - If we found block indicators, it's a block
        // - Empty {} is a hash ref
        // - Otherwise, default to block (safer when parsing is incomplete)
        parser.ctx.logDebug("isHashLiteral FINAL DECISION - hasHashIndicator:" + hasHashIndicator +
                " hasBlockIndicator:" + hasBlockIndicator + " hasContent:" + hasContent);

        if (hasHashIndicator) {
            parser.ctx.logDebug("isHashLiteral RESULT: TRUE - hash indicator found");
            return true;
        } else if (hasBlockIndicator) {
            parser.ctx.logDebug("isHashLiteral RESULT: FALSE - block indicator found");
            return false;
        } else if (!hasContent) {
            parser.ctx.logDebug("isHashLiteral RESULT: TRUE - empty {} is hash ref");
            return true; // Empty {} is a hash ref
        } else {
            parser.ctx.logDebug("isHashLiteral RESULT: FALSE - default for ambiguous case (assuming block)");
            return false; // Default: assume block when we can't determine
        }
    }

    public static void parseStatementTerminator(Parser parser) {
        LexerToken token = peek(parser);
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            parser.throwError("syntax error");
        }
        if (token.text.equals(";")) {
            consume(parser);
        }
    }
}
