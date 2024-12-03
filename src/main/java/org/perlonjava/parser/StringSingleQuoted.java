package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.StringNode;

/**
 * The {@code StringSingleQuoted} class provides functionality to parse strings
 * that are enclosed in single quotes. It handles escape sequences within the
 * string and returns a parsed representation of the string as a {@link StringNode}.
 */
public class StringSingleQuoted {

    /**
     * Parses a single-quoted string from the given {@link StringParser.ParsedString} object.
     * This method processes escape sequences and constructs a parsed string representation.
     *
     * @param rawStr A {@link StringParser.ParsedString} object containing the raw string data,
     *               including the buffer, start delimiter, end delimiter, and the token index.
     * @return A {@link StringNode} containing the parsed string and its token index.
     */
    static Node parseSingleQuotedString(StringParser.ParsedString rawStr) {
        // Retrieve the first buffer from the parsed string object
        String input = rawStr.buffers.getFirst();

        // Get the start and end delimiters for the string
        char startDelim = rawStr.startDelim;
        char endDelim = rawStr.endDelim;

        // Get the token index associated with this string
        int tokenIndex = rawStr.index;

        // Buffer to hold the parsed string
        StringBuilder str = new StringBuilder();

        // Convert the input string to a character array for processing
        char[] chars = input.toCharArray();

        // Determine the length of the character array
        int length = chars.length;

        // Initialize the current position in the character array
        int index = 0;

        // Loop through the character array until the end
        while (index < length) {
            // Get the current character
            char ch = chars[index];

            // Check if the current character is an escape character
            if (ch == '\\') {
                index++;  // Move to the next character
                if (index < length) {
                    // Get the next character after the escape character
                    char nextChar = chars[index];

                    // Check if the next character is escapable
                    if (nextChar == '\\' || nextChar == startDelim || nextChar == endDelim) {
                        // Append the escaped character to the buffer
                        str.append(nextChar);
                    } else {
                        // Append the backslash and the next character if it's not escapable
                        str.append('\\').append(nextChar);
                    }
                }
            } else {
                // Append the current character if it's not an escape character
                str.append(ch);
            }
            index++;  // Move to the next character
        }

        // Return a new StringNode with the parsed string and the token index
        return new StringNode(str.toString(), tokenIndex);
    }
}
