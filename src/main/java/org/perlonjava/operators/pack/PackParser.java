package org.perlonjava.operators.pack;

import org.perlonjava.operators.Pack;
import org.perlonjava.runtime.PerlCompilerException;

public class PackParser {
    public static ParsedModifiers parseModifiers(String template, int position) {
        ParsedModifiers result = new ParsedModifiers();
        result.endPosition = position;

        while (result.endPosition + 1 < template.length()) {
            char modifier = template.charAt(result.endPosition + 1);
            if (modifier == '<') {
                result.littleEndian = true;
                result.endPosition++;
            } else if (modifier == '>') {
                result.bigEndian = true;
                result.endPosition++;
            } else if (modifier == '!') {
                result.nativeSize = true;
                result.endPosition++;
            } else {
                break;
            }
        }

        return result;
    }

    public static ParsedCount parseRepeatCount(String template, int position) {
        ParsedCount result = new ParsedCount();
        result.count = 1;
        result.hasStar = false;
        result.endPosition = position;

        if (position + 1 < template.length()) {
            char nextChar = template.charAt(position + 1);
            if (nextChar == '[') {
                System.err.println("DEBUG: PackParser found opening bracket at position " + (position + 1));
                // Parse repeat count in brackets [n] or [template]
                int j = position + 2;
                int bracketDepth = 1;

                // Find the matching ']' with proper bracket depth counting
                while (j < template.length() && bracketDepth > 0) {
                    char ch = template.charAt(j);
                    System.err.println("DEBUG: PackParser checking character '" + ch + "' at position " + j + ", bracketDepth=" + bracketDepth);
                    if (ch == '[') {
                        bracketDepth++;
                    } else if (ch == ']') {
                        bracketDepth--;
                    }
                    if (bracketDepth > 0) {
                        j++;
                    }
                }

                if (j >= template.length() || bracketDepth > 0) {
                    System.err.println("DEBUG: PackParser bracket parsing failed: j=" + j + ", template.length()=" + template.length() + ", bracketDepth=" + bracketDepth);
                    System.err.println("DEBUG: PackParser template around position: '" + template.substring(Math.max(0, position - 5), Math.min(template.length(), position + 10)) + "'");
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }

                String countStr = template.substring(position + 2, j);
                System.err.println("DEBUG: PackParser bracket content: '" + countStr + "'");

                // Check if it's purely numeric
                if (countStr.matches("\\d+")) {
                    result.count = Integer.parseInt(countStr);
                    System.err.println("DEBUG: PackParser parsed numeric count: " + result.count);
                } else if (countStr.isEmpty()) {
                    // Empty brackets - treat as count 0
                    result.count = 0;
                    System.err.println("DEBUG: PackParser empty brackets, using count 0");
                } else {
                    // Template-based count - for now, use 1 as fallback
                    System.err.println("DEBUG: PackParser template-based repeat count [" + countStr + "] - using count 1 as fallback");
                    result.count = 1;
                    // TODO: Implement proper template size calculation
                }

                result.endPosition = j;
                System.err.println("DEBUG: PackParser after bracket parsing, endPosition=" + result.endPosition);
            } else if (Character.isDigit(nextChar)) {
                int j = position + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                result.count = Integer.parseInt(template.substring(position + 1, j));
                result.endPosition = j - 1;
            } else if (nextChar == '*') {
                result.hasStar = true;
                result.endPosition = position + 1;
            }
        }

        return result;
    }

    public static GroupInfo parseGroupInfo(String template, int closePos) {
        GroupInfo info = new GroupInfo();
        int nextPos = closePos + 1;

        // Parse modifiers after ')'
        while (nextPos < template.length()) {
            char nextChar = template.charAt(nextPos);
            if (nextChar == '<' || nextChar == '>') {
                if (info.endian == ' ') {
                    info.endian = nextChar;
                }
                nextPos++;
            } else if (nextChar == '!') {
                nextPos++;
            } else if (nextChar == '[') {
                // Parse repeat count in brackets [n]
                int j = nextPos + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                if (j >= template.length() || template.charAt(j) != ']') {
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }
                info.repeatCount = Integer.parseInt(template.substring(nextPos + 1, j));
                nextPos = j + 1;
                break;
            } else if (Character.isDigit(nextChar)) {
                // Parse repeat count
                int j = nextPos;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                info.repeatCount = Integer.parseInt(template.substring(nextPos, j));
                nextPos = j;
                break;
            } else if (nextChar == '*') {
                info.repeatCount = Integer.MAX_VALUE;
                nextPos++;
                break;
            } else {
                break;
            }
        }

        info.endPosition = nextPos;
        return info;
    }
}
