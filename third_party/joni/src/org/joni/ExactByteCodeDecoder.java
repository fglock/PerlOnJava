/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni;

import java.util.Arrays;

import org.jcodings.Encoding;
import org.joni.constants.internal.OPCode;
import org.joni.constants.internal.OPSize;

/** Shared bounds-checked decoder for native exact-instruction payloads. */
final class ExactByteCodeDecoder {
    private ExactByteCodeDecoder() {
    }

    record Instruction(int opcode, byte[] bytes, int logicalLength,
            int byteWidth, boolean ignoreCase, boolean singleByteFold,
            boolean templated, int end) {
        Instruction {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        int byteLength() {
            return bytes.length;
        }
    }

    static Instruction decode(Regex regex, int cursor) {
        return decode(regex.code, regex.codeLength, regex.templates,
                regex.enc, cursor);
    }

    static Instruction decode(int[] code, int codeLength, byte[][] templates,
            Encoding encoding, int cursor) {
        if (code == null || cursor < 0 || cursor >= codeLength) return null;
        int opcode = code[cursor];
        int payload = cursor + OPSize.OPCODE;
        int logicalLength;
        int byteWidth;
        int byteLength;
        boolean ignoreCase = false;
        boolean singleByteFold = false;
        boolean templated = false;

        switch (opcode) {
            case OPCode.EXACT1, OPCode.EXACT2, OPCode.EXACT3,
                    OPCode.EXACT4, OPCode.EXACT5 -> {
                logicalLength = opcode - OPCode.EXACT1 + 1;
                byteWidth = 1;
                byteLength = logicalLength;
            }
            case OPCode.EXACTN -> {
                if (payload >= codeLength) return null;
                logicalLength = code[payload++];
                byteWidth = 1;
                byteLength = logicalLength;
                templated = Config.USE_STRING_TEMPLATES;
            }
            case OPCode.EXACTMB2N1, OPCode.EXACTMB2N2,
                    OPCode.EXACTMB2N3 -> {
                logicalLength = opcode - OPCode.EXACTMB2N1 + 1;
                byteWidth = 2;
                byteLength = logicalLength * byteWidth;
            }
            case OPCode.EXACTMB2N -> {
                if (payload >= codeLength) return null;
                logicalLength = code[payload++];
                byteWidth = 2;
                byteLength = logicalLength * byteWidth;
                templated = Config.USE_STRING_TEMPLATES;
            }
            case OPCode.EXACTMB3N -> {
                if (payload >= codeLength) return null;
                logicalLength = code[payload++];
                byteWidth = 3;
                byteLength = logicalLength * byteWidth;
                templated = Config.USE_STRING_TEMPLATES;
            }
            case OPCode.EXACTMBN -> {
                if (payload + OPSize.LENGTH >= codeLength) return null;
                byteWidth = code[payload++];
                logicalLength = code[payload++];
                byteLength = logicalLength * byteWidth;
                templated = Config.USE_STRING_TEMPLATES;
            }
            case OPCode.EXACT1_IC, OPCode.EXACT1_IC_SB -> {
                ignoreCase = true;
                singleByteFold = opcode == OPCode.EXACT1_IC_SB;
                byteWidth = 0;
                int available = Math.min(encoding.maxLength(),
                        codeLength - payload);
                if (available <= 0) return null;
                byte[] probe = copyCodeBytes(code, payload, available);
                byteLength = encoding.length(probe, 0, probe.length);
                if (byteLength <= 0 || byteLength > available) return null;
                logicalLength = 1;
            }
            case OPCode.EXACTN_IC, OPCode.EXACTN_IC_SB -> {
                if (payload >= codeLength) return null;
                ignoreCase = true;
                singleByteFold = opcode == OPCode.EXACTN_IC_SB;
                byteWidth = 0;
                byteLength = code[payload++];
                logicalLength = byteLength;
                templated = Config.USE_STRING_TEMPLATES;
            }
            default -> {
                return null;
            }
        }
        if (logicalLength < 0 || byteWidth < 0 || byteLength < 0) return null;

        byte[] bytes;
        int end;
        if (templated) {
            if (payload + 2 * OPSize.INDEX > codeLength) return null;
            int template = code[payload++];
            int index = code[payload++];
            if (templates == null || template < 0 || template >= templates.length
                    || templates[template] == null || index < 0
                    || index + byteLength > templates[template].length) {
                return null;
            }
            bytes = Arrays.copyOfRange(templates[template], index,
                    index + byteLength);
            end = payload;
        } else {
            if (payload + byteLength > codeLength) return null;
            bytes = copyCodeBytes(code, payload, byteLength);
            end = payload + byteLength;
        }
        return new Instruction(opcode, bytes, logicalLength, byteWidth,
                ignoreCase, singleByteFold, templated, end);
    }

    private static byte[] copyCodeBytes(int[] code, int cursor, int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte)code[cursor + index];
        }
        return bytes;
    }
}
