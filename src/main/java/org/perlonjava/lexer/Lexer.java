package org.perlonjava.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lexer class is responsible for converting a sequence of characters (input string)
 * into a sequence of tokens. This process is known as lexical analysis or tokenization.
 * <p>
 * In the context of programming languages, a lexer (or lexical analyzer) is the first
 * phase of a compiler or interpreter. It reads the input source code and breaks it down
 * into meaningful elements called tokens. Each token represents a basic building block
 * of the language, such as keywords, operators, identifiers, literals, and punctuation.
 * <p>
 * The Lexer class in this example is designed to handle a subset of Perl-like syntax,
 * including identifiers, operators, and string literals. It uses a character array to
 * process the input string and identifies operators using a boolean array.
 * <p>
 * The main responsibilities of the Lexer class include:
 * - Reading and processing the input string.
 * - Identifying and categorizing different types of tokens.
 * - Handling special characters and operators.
 * - Providing a list of tokens that can be used by subsequent phases of a compiler or interpreter.
 * <p>
 * <p>
 * NOTE:
 * The Lexer is optimized for speed rather than accuracy.
 * <p>
 * This can lead to issues in cases such as:
 * <p>
 * qq<=>   // An equal-sign string is parsed as `qq` and `<=>`
 * <p>
 * 10E10   // A floating-point number is parsed as `10` and `E10`
 * <p>
 * The Parser is aware of these issues and implements workarounds to handle them.
 */
public class Lexer {
    // End of File character constant
    public static final String EOF = Character.toString((char) -1);
    // Array to mark operator characters
    public static boolean[] isOperator;

    // Static block to initialize the isOperator array
    static {
        isOperator = new boolean[128];
        // Marking specific characters as operators
        for (char c : "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~".toCharArray()) {
            isOperator[c] = true;
        }
    }

    // Input characters to be tokenized
    public char[] input;
    // Current position in the input
    public int position;
    // Length of the input
    public int length;

    // Constructor to initialize the Lexer with input string
    public Lexer(String input) {
        this.input = input.toCharArray();
        this.length = this.input.length;
        this.position = 0;
    }

    // Main method for testing the Lexer
    public static void main(String[] args) {
        // Sample code to be tokenized
        String code =
                "my $var = 42; print \"Hello, World!\\n\"; $a == $b; qq{ x \" y € z }; "
                        + " &&= &.= **= ... //= <<= <=> >>= ^.= |.= ||= ";
        if (args.length >= 2 && args[0].equals("-e")) {
            code = args[1]; // Read the code from the command line parameter
        }

        // Creating a Lexer instance with the sample code
        Lexer lexer = new Lexer(code);

        // Tokenizing the input code
        List<LexerToken> tokens = lexer.tokenize();

        // Printing the tokens
        for (LexerToken token : tokens) {
            System.out.println(token);
        }
    }

    // Method to tokenize the input string into a list of tokens
    public List<LexerToken> tokenize() {
        List<LexerToken> tokens = new ArrayList<>();
        LexerToken token;

        while ((token = nextToken()) != null) {
            tokens.add(token);
        }
        tokens.add(new LexerToken(LexerTokenType.EOF, EOF));
        tokens.add(new LexerToken(LexerTokenType.EOF, EOF));

        this.input = null;  // Throw away input to spare memory
        return tokens;
    }

    public LexerToken nextToken() {
        if (position >= length) {
            return null;
        }

        char current = input[position];

        if (Character.isWhitespace(current)) {
            if (current == '\n') {
                position++;
                return new LexerToken(LexerTokenType.NEWLINE, "\n");
            } else {
                return consumeWhitespace();
            }
        } else if (Character.isDigit(current)) {
            return consumeNumber();
        } else if (Character.isLetter(current) || current == '_') {
            return consumeIdentifier();
        } else if (current < 128 && isOperator[current]) {
            return consumeOperator();
        } else {
            position++;
            return new LexerToken(LexerTokenType.STRING, String.valueOf(current));
        }
    }

    public LexerToken consumeWhitespace() {
        int start = position;
        while (position < length
                && input[position] != '\n'
                && (input[position] == ' ' || Character.isWhitespace(input[position]))) {
            position++;
        }
        return new LexerToken(LexerTokenType.WHITESPACE, new String(input, start, position - start));
    }

    public LexerToken consumeNumber() {
        int start = position;
        while (position < length && (Character.isDigit(input[position]) || input[position] == '_')) {
            position++;
        }
        return new LexerToken(LexerTokenType.NUMBER, new String(input, start, position - start));
    }

    public LexerToken consumeIdentifier() {
        int start = position;
        while (position < length
                && (Character.isLetterOrDigit(input[position]) || input[position] == '_')) {
            position++;
        }
        return new LexerToken(LexerTokenType.IDENTIFIER, new String(input, start, position - start));
    }

    public LexerToken consumeOperator() {
        int start = position;
        char current = input[position];
        if (position < length && (current < 128 && isOperator[current])) {
            switch (current) {
                case '!':
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "!=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '~') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "!~");
                    }
                    break;
                case '$':
                    if (position + 2 <= input.length && input[position + 1] == '#') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "$#");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '*') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "$*");
                    }
                    break;
                case '@':
                    if (position + 2 <= input.length && input[position + 1] == '*') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "@*");
                    }
                    break;
                case '%':
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "%=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '*') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "%*");
                    }
                    break;
                case '&':
                    if (position + 3 <= input.length
                            && input[position + 1] == '&'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "&&=");
                    }
                    if (position + 3 <= input.length
                            && input[position + 1] == '.'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "&.=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '&') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "&&");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '*') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "&*");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "&=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '.') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "&.");
                    }
                    break;
                case '*':
                    if (position + 3 <= input.length
                            && input[position + 1] == '*'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "**=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '*') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "**");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "*=");
                    }
                    break;
                case '+':
                    if (position + 2 <= input.length && input[position + 1] == '+') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "++");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "+=");
                    }
                    break;
                case '-':
                    if (position + 2 <= input.length && input[position + 1] == '-') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "--");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "-=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '>') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "->");
                    }
                    break;
                case '.':
                    if (position + 3 <= input.length
                            && input[position + 1] == '.'
                            && input[position + 2] == '.') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "...");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '.') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "..");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, ".=");
                    }
                    break;
                case '/':
                    if (position + 3 <= input.length
                            && input[position + 1] == '/'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "//=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '/') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "//");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "/=");
                    }
                    break;
                case ':':
                    if (position + 2 <= input.length && input[position + 1] == ':') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "::");
                    }
                    break;
                case '<':
                    if (position + 3 <= input.length
                            && input[position + 1] == '<'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "<<=");
                    }
                    if (position + 3 <= input.length
                            && input[position + 1] == '='
                            && input[position + 2] == '>') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "<=>");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '<') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "<<");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "<=");
                    }
                    break;
                case '=':
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "==");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '>') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "=>");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '~') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "=~");
                    }
                    break;
                case '>':
                    if (position + 3 <= input.length
                            && input[position + 1] == '>'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, ">>=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, ">=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '>') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, ">>");
                    }
                    break;
                case '^':
                    if (position + 3 <= input.length
                            && input[position + 1] == '^'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "^^=");
                    }
                    if (position + 3 <= input.length
                            && input[position + 1] == '.'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "^.=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "^=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '^') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "^^");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '.') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "^.");
                    }
                    break;
                case 'x':
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "x=");
                    }
                    break;
                case '|':
                    if (position + 3 <= input.length
                            && input[position + 1] == '.'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "|.=");
                    }
                    if (position + 3 <= input.length
                            && input[position + 1] == '|'
                            && input[position + 2] == '=') {
                        position += 3;
                        return new LexerToken(LexerTokenType.OPERATOR, "||=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '=') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "|=");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '|') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "||");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '.') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "|.");
                    }
                    break;
                case '~':
                    if (position + 2 <= input.length && input[position + 1] == '~') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "~~");
                    }
                    if (position + 2 <= input.length && input[position + 1] == '.') {
                        position += 2;
                        return new LexerToken(LexerTokenType.OPERATOR, "~.");
                    }
                    break;
            }
        }

        position++;
        return new LexerToken(LexerTokenType.OPERATOR, new String(input, start, 1));
    }
}

