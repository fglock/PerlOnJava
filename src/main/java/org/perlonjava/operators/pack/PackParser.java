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
                // Parse repeat count in brackets [n]
                int j = position + 2;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                if (j >= template.length() || template.charAt(j) != ']') {
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }
                String countStr = template.substring(position + 2, j);
                result.count = Integer.parseInt(countStr);
                result.endPosition = j;
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
