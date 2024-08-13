package org.perlonjava;

import java.util.*;

public class Parser {
  private final List<Token> tokens;
  private final ErrorMessageUtil errorUtil;
  private int tokenIndex = 0;
  private boolean parsingForLoopVariable = false;
  private static final Set<String> TERMINATORS =
      new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when"));
  private static final Set<String> LISTTERMINATORS =
      new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or"));
  private static final Set<String> UNARY_OP =
      new HashSet<>(
          Arrays.asList(
              "!",
              "\\",
              "-",
              "+",
              "--",
              "++", // operators
              "$",
              "@",
              "%",
              "*",
              "&",
              "$#" // sigils
              ));

  public Parser(ErrorMessageUtil errorUtil, List<Token> tokens) {
    this.errorUtil = errorUtil;
    this.tokens = tokens;
  }

  public Node parse() {
    return parseBlock();
  }

  private Node parseBlock() {
    List<Node> statements = new ArrayList<>();
    Token token = peek();
    while (token.type != TokenType.EOF
        && !(token.type == TokenType.OPERATOR && token.text.equals("}"))) {
      if (token.text.equals(";")) {
        consume();
      } else {
        statements.add(parseStatement());
      }
      token = peek();
    }
    return new BlockNode(statements, tokenIndex);
  }

  public Node parseStatement() {
    Token token = peek();

    if (token.type == TokenType.IDENTIFIER) {
      switch (token.text) {
        case "if":
        case "unless":
          return parseIfStatement();
        case "for":
        case "foreach":
          return parseForStatement();
        case "while":
        case "until":
          return parseWhileStatement();
      }
    }
    if (token.type == TokenType.OPERATOR
        && token.text.equals("{")
        && !isHashLiteral()) { // bare-block
      consume(TokenType.OPERATOR, "{");
      Node block = parseBlock();
      consume(TokenType.OPERATOR, "}");
      return block;
    }
    Node expression = parseExpression(0);
    token = peek();
    if (token.type == TokenType.IDENTIFIER) {
        // statement modifier: if, for ...
        switch (token.text) {
          case "if":
            consume();
            return new BinaryOperatorNode("&&", parseExpression(0), expression, tokenIndex);
          case "unless":
            consume();
            return new BinaryOperatorNode("||", parseExpression(0), expression, tokenIndex);
          case "for":
          case "foreach":
          case "while":
          case "until":
        }
        throw new PerlCompilerException(tokenIndex, "Not implemented: " + token, errorUtil);
    }
    if (token.type != TokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
      throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
    }
    if (token.text.equals(";")) {
      consume();
    }
    return expression;
  }

  // disambiguate between Block or Hash literal
  private boolean isHashLiteral() {
    int index = tokenIndex + 1; // Start after the opening '{'
    int braceCount = 1; // Track nested braces
    while (braceCount > 0) {
      Token token = tokens.get(index++);
      if (token.type == TokenType.EOF) {
        return false; // not a hash literal;
      }
      if (token.type == TokenType.OPERATOR) {
          switch (token.text) {
              case "{":
                  braceCount++;
                  break;
              case "}":
                  braceCount--;
                  break;
              case ",":
              case "=>":
                  return true; // Likely a hash literal
              case ";":
                  return false; // Likely a block
          }
      }
    }
    return false;
  }

  private Node parseAnonSub() {
    // token == "sub"
    // TODO - optional name, subroutine prototype
    consume(TokenType.OPERATOR, "{");
    Node block = parseBlock();
    consume(TokenType.OPERATOR, "}");

    // some characters are illegal after an anon sub
    Token token = peek();
    if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
      throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
    }

    return new AnonSubNode(block, false, tokenIndex);
  }

  private Node parseWhileStatement() {
    Token operator = consume(TokenType.IDENTIFIER); // "while" "until"

    consume(TokenType.OPERATOR, "(");
    Node condition = parseExpression(0);
    consume(TokenType.OPERATOR, ")");

    // Parse the body of the loop
    consume(TokenType.OPERATOR, "{");
    Node body = parseBlock();
    consume(TokenType.OPERATOR, "}");

    if (operator.text.equals("until")) {
        condition = new UnaryOperatorNode("not", condition, condition.getIndex());
    }
    return new For3Node(null, condition, null, body, tokenIndex);
  }


  private Node parseForStatement() {
    consume(TokenType.IDENTIFIER); // "for" "foreach"

    Node varNode = null;
    Token token = peek(); // "my" "$" "("
    if (token.text.equals("my") || token.text.equals("$")) {
        parsingForLoopVariable = true;
        varNode = parsePrimary();
        parsingForLoopVariable = false;
    }

    consume(TokenType.OPERATOR, "(");

    // Parse the initialization part
    Node initialization = null;
    if (!peek().text.equals(";")) {
      initialization = parseExpression(0);

      token = peek();
      if (token.text.equals(")")) {
        // 1-argument for
        consume();

        // Parse the body of the loop
        consume(TokenType.OPERATOR, "{");
        Node body = parseBlock();
        consume(TokenType.OPERATOR, "}");

        return new For1Node(varNode, initialization, body, tokenIndex);
      }
    }
    // 3-argument for
    if (varNode != null) {
      throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
    }
    consume(TokenType.OPERATOR, ";");

    // Parse the condition part
    Node condition = null;
    if (!peek().text.equals(";")) {
      condition = parseExpression(0);
    }
    consume(TokenType.OPERATOR, ";");

    // Parse the increment part
    Node increment = null;
    if (!peek().text.equals(")")) {
      increment = parseExpression(0);
    }
    consume(TokenType.OPERATOR, ")");

    // Parse the body of the loop
    consume(TokenType.OPERATOR, "{");
    Node body = parseBlock();
    consume(TokenType.OPERATOR, "}");

    return new For3Node(initialization, condition, increment, body, tokenIndex);
  }

  private Node parseIfStatement() {
    Token operator = consume(TokenType.IDENTIFIER); // "if", "unless", "elsif"
    consume(TokenType.OPERATOR, "(");
    Node condition = parseExpression(0);
    consume(TokenType.OPERATOR, ")");
    consume(TokenType.OPERATOR, "{");
    Node thenBranch = parseBlock();
    consume(TokenType.OPERATOR, "}");
    Node elseBranch = null;
    Token token = peek();
    if (token.text.equals("else")) {
      consume(TokenType.IDENTIFIER); // "else"
      consume(TokenType.OPERATOR, "{");
      elseBranch = parseBlock();
      consume(TokenType.OPERATOR, "}");
    } else if (token.text.equals("elsif")) {
      elseBranch = parseIfStatement();
    }
    return new IfNode(operator.text, condition, thenBranch, elseBranch, tokenIndex);
  }

  /**
   * Parses an expression based on operator precedence.
   * 
   * @param precedence The precedence level of the current expression.
   * @return The root node of the parsed expression.
   */
  private Node parseExpression(int precedence) {
      // First, parse the primary expression (like a number or a variable).
      Node left = parsePrimary();
  
      // Continuously process tokens until we reach the end of the expression.
      while (true) {
          // Peek at the next token to determine what to do next.
          Token token = peek();
  
          // Check if we have reached the end of the input (EOF) or a terminator (like `;`).
          if (token.type == TokenType.EOF || TERMINATORS.contains(token.text)) {
              break; // Exit the loop if we're done parsing.
          }
  
          // Get the precedence of the current token.
          int tokenPrecedence = getPrecedence(token.text);
  
          // If the token's precedence is less than the precedence of the current expression, stop parsing.
          if (tokenPrecedence < precedence) {
              break;
          }
  
          // If the operator is right associative (like exponentiation), parse it with lower precedence.
          if (isRightAssociative(token.text)) {
              left = parseInfix(left, tokenPrecedence - 1); // Parse the right side with lower precedence.
          } else {
              // Otherwise, parse it normally with the same precedence.
              left = parseInfix(left, tokenPrecedence);
          }
      }
  
      // Return the root node of the constructed expression tree.
      return left;
  }

  private Node parsePrimary() {
    Token token = consume(); // Consume the next token from the input
    Node operand;

    switch (token.type) {
      case IDENTIFIER:
        Token nextToken = peek();
        if (nextToken.text.equals("=>")) {
            // Autoquote
            return new StringNode(token.text, tokenIndex);
        }
        switch (token.text) {
          case "undef":
            // Handle 'undef' keyword as a unary operator with no operand
            return new UnaryOperatorNode("undef", null, tokenIndex);
          case "not":
            // Handle 'not' keyword as a unary operator with an operand
            operand = parseExpression(getPrecedence(token.text) + 1);
            return new UnaryOperatorNode("not", operand, tokenIndex);
          case "abs":
          case "log":
          case "rand":
            String text = token.text;
            operand = parseZeroOrOneList();
            if (((ListNode) operand).elements.isEmpty()) {
              if (text.equals("rand")) {
                // create "1"
                operand = new NumberNode("1", tokenIndex);
              } else {
                // create `$_` variable
                operand = new UnaryOperatorNode(
                      "$", new IdentifierNode("_", tokenIndex), tokenIndex);
              }
            }
            return new UnaryOperatorNode(text, operand, tokenIndex);
          case "join":
            // Handle 'join' keyword as a Binary operator with a RuntimeList operand
            operand = parseZeroOrMoreList(1);
            Node separator = ((ListNode) operand).elements.remove(0);
            return new BinaryOperatorNode("join", separator, operand, tokenIndex);
          case "print":
          case "say":
            // Handle 'say' keyword as a unary operator with a RuntimeList operand
            operand = parseZeroOrMoreList(0);
            return new UnaryOperatorNode(token.text, operand, tokenIndex);
          case "my":
            // Handle 'my' keyword as a unary operator with an operand
            operand = parsePrimary();
            return new UnaryOperatorNode("my", operand, tokenIndex);
          case "return":
            // Handle 'return' keyword as a unary operator with an operand;
            // Parenthensis are ignored.
            operand = parseExpression(getPrecedence("print") + 1);
            return new UnaryOperatorNode("return", operand, tokenIndex);
          case "eval":
            // Handle 'eval' keyword which can be followed by a block or an expression
            token = peek();
            if (token.type == TokenType.OPERATOR && token.text.equals("{")) {
              // If the next token is '{', parse a block
              consume(TokenType.OPERATOR, "{");
              Node block = parseBlock();
              consume(TokenType.OPERATOR, "}");
              // transform:  eval { 123 }
              // into:  sub { 123 }->()
              //
              //   BinaryOperatorNode: ->
              //     AnonSubNode:
              //       BlockNode:
              //         NumberNode: 123
              //     ListNode:
              return new BinaryOperatorNode("->",
                new AnonSubNode(block, true, tokenIndex),
                new ListNode(tokenIndex), tokenIndex);
            } else {
              // Otherwise, parse a primary expression
              operand = parsePrimary();
            }
            return new UnaryOperatorNode("eval", operand, tokenIndex);
          case "do":
            // Handle 'do' keyword which can be followed by a block
            token = peek();
            if (token.type == TokenType.OPERATOR && token.text.equals("{")) {
              consume(TokenType.OPERATOR, "{");
              Node block = parseBlock();
              consume(TokenType.OPERATOR, "}");
              return block;
            }
            break;
          case "sub":
            // Handle 'sub' keyword to parse an anonymous subroutine
            return parseAnonSub();
          default:
            // Handle any other identifier as a simple identifier node
            return new IdentifierNode(token.text, tokenIndex);
        }
        break;
      case NUMBER:
        // Handle number literals
        return parseNumber(token);
      case STRING:
        // Handle string literals
        return new StringNode(token.text, tokenIndex);
      case OPERATOR:
        switch (token.text) {
          case "(":
            // Handle parentheses to parse a nested expression or to construct a list
            return new ListNode(parseList(")", 0), tokenIndex);
          case "{":
            // Handle curly brackets to parse a nested expression
            return new HashLiteralNode(parseList("}", 1), tokenIndex);
          case "[":
            // Handle square brackets to parse a nested expression
            return new ArrayLiteralNode(parseList("]", 1), tokenIndex);
          case ".":
            // Handle fractional numbers
            return parseFractionalNumber();
          case "'":
            // Handle single-quoted strings
            return parseSingleQuotedString();
          case "\"":
            // Handle double-quoted strings
            return parseDoubleQuotedString();
          default:
            // Handle unary operators
            if (UNARY_OP.contains(token.text)) {

              String text = token.text;
              int saveIndex = tokenIndex;

              nextToken = peek(); // operator or identifier
              if (isSigil(text)
                  && (nextToken.type == TokenType.OPERATOR
                      || nextToken.type == TokenType.IDENTIFIER
                      || nextToken.type == TokenType.NUMBER)) {
                // Handle normal variables and special variables like $@

                consume(); // operator or identifier

                // handle the special case for $$a
                if (nextToken.text.equals("$")
                    && (peek().text.equals("$")
                        || peek().type == TokenType.IDENTIFIER
                        || peek().type == TokenType.NUMBER)) {
                  // wrong guess: this is not a special variable
                  tokenIndex = saveIndex; // backtrack
                } else {

                  // some characters are illegal after a variable
                    if (peek().text.equals("(") && !parsingForLoopVariable) {
                    // not parsing "for my $v (..."
                    throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
                  }

                  // create a Variable
                  return new UnaryOperatorNode(
                      text, new IdentifierNode(nextToken.text, tokenIndex), tokenIndex);
                }
              }

              operand = parseExpression(getPrecedence(text) + 1);
              return new UnaryOperatorNode(text, operand, tokenIndex);
            }
            break;
        }
        break;
      case EOF:
        // Handle end of input
        return null;
      default:
        // Throw an exception for any unexpected token
        throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
    }
    // Throw an exception if no valid case was found
    throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
  }

  private Node parseDoubleQuotedString() {
    StringBuilder str = new StringBuilder();
    List<Node> parts = new ArrayList<>();
    Token token = tokens.get(tokenIndex);
    while (!token.text.equals("\"")) {
      tokenIndex++;
      String text = token.text;
      if (token.type == TokenType.OPERATOR) {
        if (text.equals("\\")) {
          // Handle escaped characters
          text =
              consume()
                  .text; // XXX TODO the string is tokenized, we need to check single characters
          switch (text) {
            case "\\":
            case "\"":
              str.append(text);
              break;
            case "n":
              str.append("\n");
              break;
            case "t":
              str.append("\t");
              break;
            default:
              str.append(text);
              break;
          }
        } else if (text.equals("$") || text.equals("@")) {
          boolean isArray = text.equals("@");
          Node operand;
          if (str.length() > 0) {
            parts.add(new StringNode(str.toString(), tokenIndex)); // string so far
            str = new StringBuilder(); // continue
          }
          Token nextToken = peek();
          if (nextToken.type == TokenType.IDENTIFIER) {
            operand =
                new UnaryOperatorNode(
                    text, new IdentifierNode(consume().text, tokenIndex), tokenIndex);
          } else if (nextToken.type == TokenType.OPERATOR && nextToken.text.equals("{")) {
            consume(); // consume '{'
            String varName = consume(TokenType.IDENTIFIER).text;
            consume(TokenType.OPERATOR, "}"); // consume '}'
            operand =
                new UnaryOperatorNode(text, new IdentifierNode(varName, tokenIndex), tokenIndex);
          } else {
            throw new PerlCompilerException(tokenIndex, "Final "+text+" should be \\"+text+" or "+text+"name", errorUtil);
          }
          if (isArray) {
            operand = new BinaryOperatorNode("join",
                new UnaryOperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex),        // join with $"
                operand, tokenIndex);
          }
          parts.add(operand);
        } else {
          str.append(text);
        }
      } else {
        str.append(text);
      }
      token = tokens.get(tokenIndex);
    }
    if (str.length() > 0) {
      parts.add(new StringNode(str.toString(), tokenIndex));
    }
    consume(TokenType.OPERATOR, "\""); // Consume the closing double quote

    // Join the parts
    if (parts.isEmpty()) {
      return new StringNode("", tokenIndex);
    } else if (parts.size() == 1) {
      Node result = parts.get(0);
      if (result instanceof StringNode) {
        return parts.get(0);
      }
      // stringify using:  "" . $a
      return new BinaryOperatorNode(".", new StringNode("", tokenIndex), parts.get(0), tokenIndex);
    } else {
      Node result = parts.get(0);
      for (int i = 1; i < parts.size(); i++) {
        result = new BinaryOperatorNode(".", result, parts.get(i), tokenIndex);
      }
      return result;
    }
  }

  private Node parseSingleQuotedString() {
    StringBuilder str = new StringBuilder();
    while (!peek().text.equals("'")) {
      String text = consume().text;
      if (text.equals("\\")) {
        // Handle escaped characters
        text = consume().text;
        if (text.equals("\\") || text.equals("'")) {
          str.append(text);
        } else {
          str.append("\\").append(text);
        }
      } else {
        str.append(text);
      }
    }
    consume(TokenType.OPERATOR, "'"); // Consume the closing single quote
    return new StringNode(str.toString(), tokenIndex);
  }

  private Node parseNumber(Token token) {
    StringBuilder number = new StringBuilder(token.text);

    // Check for fractional part
    if (tokens.get(tokenIndex).text.equals(".")) {
      number.append(consume().text); // consume '.'
      if (tokens.get(tokenIndex).type == TokenType.NUMBER) {
        number.append(consume().text); // consume digits after '.'
      }
    }
    // Check for exponent part
    checkNumberExponent(number);

    return new NumberNode(number.toString(), tokenIndex);
  }

  private Node parseFractionalNumber() {
    StringBuilder number = new StringBuilder("0.");

    number.append(consume(TokenType.NUMBER).text); // consume digits after '.'
    // Check for exponent part
    checkNumberExponent(number);
    return new NumberNode(number.toString(), tokenIndex);
  }

  private void checkNumberExponent(StringBuilder number) {
    // Check for exponent part
    String exponentPart = peek().text;
    if (exponentPart.startsWith("e")
        || exponentPart.startsWith("E")) {
      consume(); // consume 'e' or 'E' and possibly more 'E10'

      // Check if the rest of the token contains digits (e.g., "E10")
      int index = 1;
      for (; index < exponentPart.length(); index++) {
        if (!Character.isDigit(exponentPart.charAt(index)) && exponentPart.charAt(index) != '_') {
          throw new PerlCompilerException(tokenIndex, "Malformed number", errorUtil);
        }
      }
      number.append(exponentPart);

      // If the exponent part was not fully consumed, check for separate tokens
      if (index == 1) {
        // Check for optional sign
        if (tokens.get(tokenIndex).text.equals("-") || tokens.get(tokenIndex).text.equals("+")) {
          number.append(consume().text); // consume '-' or '+'
        }

        // Consume exponent digits
        number.append(consume(TokenType.NUMBER).text);
      }
    }
  }

  private Node parseInfix(Node left, int precedence) {
    Token token = consume();

    Node right;
    switch (token.text) {
      case "or":
      case "xor":
      case "and":
      case "||":
      case "//":
      case "&&":
      case "==":
      case "!=":
      case "<=>":
      case "eq":
      case "ne":
      case "cmp":
      case "<":
      case ">":
      case "<=":
      case ">=":
      case "lt":
      case "gt":
      case "le":
      case "ge":
      case "+":
      case "-":
      case "*":
      case "**":
      case "/":
      case "%":
      case ".":
      case "=":
      case "=~":
      case "!~":
      case "x":
      case "..":
      case "...":
        right = parseExpression(precedence);
        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
      case ",":
      case "=>":
        if (token.text.equals("=>") && left instanceof IdentifierNode) {
            // Autoquote - Convert IdentifierNode to StringNode
            left = new StringNode(((IdentifierNode) left).name, ((IdentifierNode) left).tokenIndex);
        }
        token = peek();
        if (token.type == TokenType.EOF || LISTTERMINATORS.contains(token.text) || token.text.equals(",") || token.text.equals("=>")) {
            // "postfix" comma
            return ListNode.makeList(left);
        }
        right = parseExpression(precedence);
        return ListNode.makeList(left, right);
      case "?":
        Node middle = parseExpression(0);
        consume(TokenType.OPERATOR, ":");
        right = parseExpression(precedence);
        return new TernaryOperatorNode(token.text, left, middle, right, tokenIndex);
      case "->":
        String nextText = peek().text;
        switch (nextText) {
          case "(":
            consume();
            right = new ListNode(parseList(")", 0), tokenIndex);
            return new BinaryOperatorNode(token.text, left, right, tokenIndex);
          case "{":
            consume();
            right = new HashLiteralNode(parseList("}", 1), tokenIndex);
            return new BinaryOperatorNode(token.text, left, right, tokenIndex);
          case "[":
            consume();
            right = new ArrayLiteralNode(parseList("]", 1), tokenIndex);
            return new BinaryOperatorNode(token.text, left, right, tokenIndex);
        }
        right = parseExpression(precedence);
        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
      case "(":
        right = new ListNode(parseList(")", 0), tokenIndex);
        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
      case "{":
        right = new HashLiteralNode(parseList("}", 1), tokenIndex);
        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
      case "[":
        right = new ArrayLiteralNode(parseList("]", 1), tokenIndex);
        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
      case "--":
      case "++":
        return new PostfixOperatorNode(token.text, left, tokenIndex);
    }
    throw new PerlCompilerException(tokenIndex, "Unexpected infix operator: " + token, errorUtil);
  }

  private void skipWhitespace() {
      while (tokenIndex < tokens.size()) {
          Token token = tokens.get(tokenIndex);
          if (token.type == TokenType.WHITESPACE || token.type == TokenType.NEWLINE) {
              tokenIndex++;
          } else if (token.type == TokenType.OPERATOR && token.text.equals("#")) {
              // Skip the comment until the end of the line
              while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != TokenType.NEWLINE) {
                  tokenIndex++;
              }
          } else {
              break;
          }
      }
  }

  private Token peek() {
    skipWhitespace();
    if (tokenIndex >= tokens.size()) {
        return new Token(TokenType.EOF, "");
    }
    return tokens.get(tokenIndex);
  }

  private Token consume() {
    skipWhitespace();
    if (tokenIndex >= tokens.size()) {
        return new Token(TokenType.EOF, "");
    }
    return tokens.get(tokenIndex++);
  }

  private Token consume(TokenType type) {
    Token token = consume();
    if (token.type != type) {
      throw new PerlCompilerException(
          tokenIndex, "Expected token " + type + " but got " + token, errorUtil);
    }
    return token;
  }

  private void consume(TokenType type, String text) {
    Token token = consume();
    if (token.type != type || !token.text.equals(text)) {
      throw new PerlCompilerException(
          tokenIndex,
          "Expected token " + type + " with text " + text + " but got " + token,
          errorUtil);
    }
  }

  private int getPrecedence(String operator) {
    // Define precedence levels for operators
    switch (operator) {
      case "or":
      case "xor":
        return 1;
      case "and":
        return 2;
      case "not":
        return 3;
      case "print":
        return 4;
      case ",":
      case "=>":
        return 5;
      case "=":
      case "**=":
      case "+=":
      case "*=":
      case "&=":
      case "&.=":
      case "<<=":
      case "&&=":
      case "-=":
      case "/=":
      case "|=":
      case "|.=":
      case ">>=":
      case "||=":
      case ".=":
      case "%=":
      case "^=":
      case "^.=":
      case "//=":
      case "x=":
        return 6;
      case "?":
        return 7;
      case "..":
      case "...":
        return 8;
      case "||":
      case "^^":
      case "//":
        return 9;
      case "&&":
        return 10;
      case "==":
      case "!=":
      case "<=>":
      case "eq":
      case "ne":
      case "cmp":
        return 11;
      case "<":
      case ">":
      case "<=":
      case ">=":
      case "lt":
      case "gt":
      case "le":
      case "ge":
        return 12;
      case "+":
      case "-":
        return 13;
      case "*":
      case "/":
      case "%":
      case "x":
        return 14;
      case "=~":
      case "!~":
        return 15;
      case "!":
      case "\\":
        return 16;
      case "**":
        return 17;
      case "++":
      case "--":
        return 18;
      case "->":
        return 19;
      default:
        return 20;
    }
  }

  public static boolean isSigil(String s) {
    switch (s) {
      case "$":
      case "@":
      case "%":
        return true;
      default:
        return false;
    }
  }

  private boolean isRightAssociative(String s) {
    // Define right associative operators
    switch (s) {
      case "=":
      case "-=":
      case "+=":
      case "**":
      case "?":
        return true;
      default:
        return false;
    }
  }

  // List parsers

  // List parser for predeclared function calls with One optional argument,
  // accepts a list with Parentheses or without.
  //
  // Comma is allowed after the argument:   rand, rand 10,
  //
  private ListNode parseZeroOrOneList() {
    ListNode expr;
    Token token = peek();
    if (token.text.equals("(")) {
        // argument in parentheses, can be 0 or 1 argument:    rand(), rand(10)
        // Commas are allowed after the single argument:       rand(10,)
        consume();
        expr = new ListNode(parseList(")", 0), tokenIndex);
        if (expr.elements.size() > 1) {
          throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
        }
    } else if (token.type == TokenType.EOF || LISTTERMINATORS.contains(token.text) || token.text.equals(",")) {
        // no argument
        expr = new ListNode(tokenIndex);
    }
    else {
        // argument without parentheses
        expr = ListNode.makeList(parseExpression(getPrecedence(",") + 1));
    }
    return expr;
  }

  // List parser for predeclared function calls, accepts a list with Parentheses or without
  //
  // The Minimum number of arguments can be set.
  //
  private ListNode parseZeroOrMoreList(int minItems) {
    Token token = peek();
    if (token.text.equals("(")) {
        // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
        // Commas are allowed after the arguments:       print(10,)
        consume();
        return new ListNode(parseList(")", 0), tokenIndex);
    }

    ListNode expr = new ListNode(tokenIndex);
    while (token.type != TokenType.EOF) {
        // argument without parentheses
        expr.elements.add(parseExpression(getPrecedence(",") + 1));
        token = peek();
        if (token.text.equals(",") || token.text.equals("=>")) {
            while (token.text.equals(",") || token.text.equals("=>")) {
                consume();
                token = peek();
            }
        } else {
            return expr;
        }
    }

    if (expr.elements.size() < minItems) {
      throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
    }

    return expr;
  }

  // Generic List parser for Parentheses, Hash literal, Array literal,
  // function arguments, get Array element, get Hash element.
  //
  // The Minimum number of arguments can be set.
  //
  // Example usage:
  //
  //    new ListNode(parseList(")", 0), tokenIndex);
  //    new HashLiteralNode(parseList("}", 1), tokenIndex);
  //    new ArrayLiteralNode(parseList("]", 1), tokenIndex);
  //
  private List<Node> parseList(String close, int minItems) {
    ListNode expr;

    Token token = peek();
    if (token.text.equals(close)) {
      // empty list
      consume();
      List<Node> list = new ArrayList<>();
      expr = new ListNode(list, tokenIndex);
    }
    else {
      expr = ListNode.makeList(parseExpression(0));
      consume(TokenType.OPERATOR, close);
    }

    if (expr.elements.size() < minItems) {
      throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
    }

    return expr.elements;
  }

  public static void main(String[] args) throws Exception {
    String fileName = "example.pl";
    String code = "my $var = 42; 1 ? 2 : 3; print \"Hello, World!\\n\";";
    if (args.length >= 2 && args[0].equals("-e")) {
      code = args[1]; // Read the code from the command line parameter
      fileName = "-e";
    }
    Lexer lexer = new Lexer(code);
    List<Token> tokens = lexer.tokenize();
    ErrorMessageUtil errorMessageUtil = new ErrorMessageUtil(fileName, tokens);
    Parser parser = new Parser(errorMessageUtil, tokens);
    Node ast = parser.parse();
    System.out.println(ast);
  }
}
