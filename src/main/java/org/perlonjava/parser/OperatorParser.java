package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

import static org.perlonjava.lexer.LexerTokenType.OPERATOR;
import static org.perlonjava.parser.NumberParser.parseNumber;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * This class provides methods for parsing various Perl operators and constructs.
 */
public class OperatorParser {

    /**
     * Parses sort operator.
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A BinaryOperatorNode representing the parsed operator.
     */
    static BinaryOperatorNode parseSort(Parser parser, LexerToken token) {
        ListNode operand;
        int currentIndex = parser.tokenIndex;
        try {
            // Handle 'sort' keyword as a Binary operator with a Code and List operands
            operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        } catch (PerlCompilerException e) {
            // sort $sub 1,2,3
            parser.tokenIndex = currentIndex;

            boolean paren = false;
            if (peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                paren = true;
            }

            parser.parsingForLoopVariable = true;
            Node var = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
            operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
            operand.handle = var;

            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            parser.ctx.logDebug("parseSort: " + operand.handle + " : " + operand);
        }

        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null) {
            // create default block for `sort`: { $a cmp $b }
            block = new BlockNode(List.of(new BinaryOperatorNode("cmp", new OperatorNode("$", new IdentifierNode("main::a", parser.tokenIndex), parser.tokenIndex), new OperatorNode("$", new IdentifierNode("main::b", parser.tokenIndex), parser.tokenIndex), parser.tokenIndex)), parser.tokenIndex);
        }
        if (block instanceof BlockNode) {
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }

    /**
     * Parses map and grep operators.
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A BinaryOperatorNode representing the parsed operator.
     */
    static BinaryOperatorNode parseMapGrep(Parser parser, LexerToken token) {
        ListNode operand;
        int currentIndex = parser.tokenIndex;
        try {
            // Handle 'map' keyword as a Binary operator with a Code and List operands
            operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        } catch (PerlCompilerException e) {
            // map chr, 1,2,3
            parser.tokenIndex = currentIndex;

            boolean paren = false;
            if (peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                paren = true;
            }

            parser.parsingForLoopVariable = true;
            Node var = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
            TokenUtils.consume(parser, OPERATOR, ",");
            operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
            operand.handle = new BlockNode(List.of(var), parser.tokenIndex);

            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            parser.ctx.logDebug("parseMap: " + operand.handle + " : " + operand);
        }

        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null) {
            // use the first argument as block: 'map ord, 1,2,3'
            if (!operand.elements.isEmpty()) {
                block = new BlockNode(
                        List.of(operand.elements.removeFirst()),
                        parser.tokenIndex);
            }
        }
        if (block instanceof BlockNode) {
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }

    /**
     * Parses the 'require' operator.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed 'require' operator.
     * @throws PerlCompilerException If there's a syntax error.
     */
    static Node parseRequire(Parser parser) {
        LexerToken token;
        // Handle 'require' keyword which can be followed by a version, bareword or filename
        token = TokenUtils.peek(parser);
        Node operand;

        // `require` version
        if (token.type == LexerTokenType.NUMBER) {
            TokenUtils.consume(parser);
            operand = parseNumber(parser, token);
        } else if (token.text.matches("^v\\d+$")) {
            TokenUtils.consume(parser);
            operand = StringParser.parseVstring(parser, token.text, parser.tokenIndex);
        } else if (token.type == LexerTokenType.IDENTIFIER) {

            // `require` module
            String moduleName = IdentifierParser.parseSubroutineIdentifier(parser);
            parser.ctx.logDebug("name `" + moduleName + "`");
            if (moduleName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            String fileName = NameNormalizer.moduleToFilename(moduleName);
            operand = ListNode.makeList(new StringNode(fileName, parser.tokenIndex));
        } else {
            // `require` file
            operand = ListParser.parseZeroOrOneList(parser, 1);
        }
        return new OperatorNode("require", operand, parser.tokenIndex);
    }

    /**
     * Parses the 'do' operator.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed 'do' operator.
     */
    static Node parseDoOperator(Parser parser) {
        LexerToken token;
        Node block;
        // Handle 'do' keyword which can be followed by a block or filename
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            block = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            return block;
        }
        // `do` file
        Node operand = ListParser.parseZeroOrOneList(parser, 1);
        return new OperatorNode("doFile", operand, parser.tokenIndex);
    }

    /**
     * Parses the 'eval' operator.
     *
     * @param parser The Parser instance.
     * @return An AbstractNode representing the parsed 'eval' operator.
     */
    static AbstractNode parseEval(Parser parser, String operator) {
        Node block;
        Node operand;
        LexerToken token;
        // Handle 'eval' keyword which can be followed by a block or an expression
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            block = ParseBlock.parseBlock(parser);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new SubroutineNode(null, null, null, block, true, parser.tokenIndex), new ListNode(parser.tokenIndex), parser.tokenIndex);
        } else {
            // Otherwise, parse an expression, and default to $_
            operand = ListParser.parseZeroOrOneList(parser, 0);
            if (((ListNode) operand).elements.isEmpty()) {
                // create `$_` variable
                operand = ParserNodeUtils.scalarUnderscore(parser);
            }
        }
        return new EvalOperatorNode(
                operator,
                operand,
                parser.ctx.symbolTable.snapShot(), // Freeze the scoped symbol table for the eval context
                parser.tokenIndex);
    }

    /**
     * Parses the diamond operator (<>).
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A Node representing the parsed diamond operator.
     */
    static Node parseDiamondOperator(Parser parser, LexerToken token) {
        // Save the current token index to restore later if needed
        int currentTokenIndex = parser.tokenIndex;
        if (token.text.equals("<")) {
            String tokenText = parser.tokens.get(parser.tokenIndex).text;

            // Check if the token is a dollar sign, indicating a variable
            if (tokenText.equals("$")) {
                // Handle the case for <$fh>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;
                Node var = Variable.parseVariable(parser, "$"); // Parse the variable following the dollar sign
                parser.ctx.logDebug("diamond operator var " + var);

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            var,
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                }
            }

            // Restore the token index
            parser.tokenIndex = currentTokenIndex;

            // Check if the token is one of the standard input sources
            if (tokenText.equals("STDIN") || tokenText.equals("DATA") || tokenText.equals("ARGV")) {
                // Handle the case for <STDIN>, <DATA>, or <ARGV>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            new IdentifierNode("main::" + tokenText, currentTokenIndex),
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                }
            }
        }
        // Restore the token index
        parser.tokenIndex = currentTokenIndex;

        if (token.text.equals("<<")) {
            String tokenText = parser.tokens.get(parser.tokenIndex).text;
            if (!tokenText.equals(">>")) {
                return ParseHeredoc.parseHeredoc(parser, tokenText);
            }
        }

        // Handle other cases like <>, <<>>, or <*.*> by parsing as a raw string
        return StringParser.parseRawString(parser, token.text);
    }

    static BinaryOperatorNode parsePrint(Parser parser, LexerToken token, int currentIndex) {
        Node handle;
        ListNode operand;
        try {
            // Handle 'print' keyword as a Binary operator with a FileHandle and List operands
            operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, true, false);
        } catch (PerlCompilerException e) {
            // print $fh (1,2,3)
            parser.tokenIndex = currentIndex;

            boolean paren = false;
            if (peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                paren = true;
            }

            parser.parsingForLoopVariable = true;
            Node var = ParsePrimary.parsePrimary(parser);
            parser.parsingForLoopVariable = false;
            operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
            operand.handle = var;

            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            parser.ctx.logDebug("parsePrint: " + operand.handle + " : " + operand);
        }

        handle = operand.handle;
        operand.handle = null;
        if (handle == null) {
            // `print` without arguments means `print to last selected filehandle`
            handle = new OperatorNode("select", new ListNode(currentIndex), currentIndex);
        }
        if (operand.elements.isEmpty()) {
            // `print` without arguments means `print $_`
            operand.elements.add(
                    ParserNodeUtils.scalarUnderscore(parser)
            );
        }
        return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
    }

    private static void addVariableToScope(EmitterContext ctx, String operator, OperatorNode node) {
        String sigil = node.operator;
        if ("$@%".contains(sigil)) {
            // not "undef"
            Node identifierNode = node.operand;
            if (identifierNode instanceof IdentifierNode) { // my $a
                String name = ((IdentifierNode) identifierNode).name;
                String var = sigil + name;
                if (ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                    System.err.println(
                            ctx.errorUtil.errorMessage(node.getIndex(),
                                    "Warning: \"" + operator + "\" variable "
                                            + var
                                            + " masks earlier declaration in same ctx.symbolTable"));
                }
                int varIndex = ctx.symbolTable.addVariable(var, operator, node);
            }
        }
    }

    static OperatorNode parseVariableDeclaration(Parser parser, String operator, int currentIndex) {

        // Create OperatorNode ($, @, %), ListNode (includes undef), SubroutineNode
        Node operand = ParsePrimary.parsePrimary(parser);
        parser.ctx.logDebug("parseVariableDeclaration " + operator + ": " + operand);

        // Add variables to the scope
        if (operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode operandNode) {
                    addVariableToScope(parser.ctx, operator, operandNode);
                }
            }
        } else if (operand instanceof OperatorNode operandNode) {

            if (operator.equals("state")) {
                // Give the variable a persistent id (See: PersistentVariable.java)
                if (operandNode.id == 0) {
                    operandNode.id = EmitterMethodCreator.classCounter++;
                }
            }

            addVariableToScope(parser.ctx, operator, operandNode);
        }

        return new OperatorNode(operator, operand, currentIndex);
    }

    static OperatorNode parseOperatorWithOneOptionalArgument(Parser parser, LexerToken token) {
        Node operand;
        // Handle operators with one optional argument
        String text = token.text;
        operand = ListParser.parseZeroOrOneList(parser, 0);
        if (((ListNode) operand).elements.isEmpty()) {
            switch (text) {
                case "sleep":
                    operand = new NumberNode(Long.toString(Long.MAX_VALUE), parser.tokenIndex);
                    break;
                case "pop":
                case "shift":
                    // create `@_` variable
                    // XXX in main program, use `@ARGV`
                    operand = atUnderscore(parser);
                    break;
                case "localtime":
                case "gmtime":
                case "caller":
                case "reset":
                case "select":
                    // default to empty list
                    break;
                case "srand":
                    operand = new OperatorNode("undef", null, parser.tokenIndex);
                    break;
                case "exit":
                    // create "0"
                    operand = new NumberNode("0", parser.tokenIndex);
                    break;
                case "undef":
                    operand = null;
                    break;  // leave it empty
                case "rand":
                    // create "1"
                    operand = new NumberNode("1", parser.tokenIndex);
                    break;
                default:
                    // create `$_` variable
                    operand = ParserNodeUtils.scalarUnderscore(parser);
                    break;
            }
        }
        return new OperatorNode(text, operand, parser.tokenIndex);
    }

    public static OperatorNode parseSelect(Parser parser, LexerToken token, int currentIndex) {
        // Handle 'select' operator with two different syntaxes:
        // 1. select FILEHANDLE or select (returns/sets current filehandle)
        // 2. select RBITS,WBITS,EBITS,TIMEOUT (syscall)
        ListNode listNode1 = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        int argCount = listNode1.elements.size();
        if (argCount == 1) {
            // select FILEHANDLE
            if (listNode1.elements.getFirst() instanceof IdentifierNode identifierNode) {
                Node handle = FileHandle.parseBarewordHandle(parser, identifierNode.name);
                if (handle != null) {
                    // handle is Bareword
                    listNode1.elements.set(0, handle);
                }
            }
            return new OperatorNode(token.text, listNode1, currentIndex);
        }
        else if (argCount == 0 || argCount == 4) {
            // select or
            // select RBITS,WBITS,EBITS,TIMEOUT (syscall version)
            return new OperatorNode(token.text, listNode1, currentIndex);
        } else {
            throw new PerlCompilerException(parser.tokenIndex,
                "Wrong number of arguments for select: expected 0, 1, or 4, got " + argCount,
                parser.ctx.errorUtil);
        }
    }
}