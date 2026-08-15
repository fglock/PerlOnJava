package com.google.zxing.qrcode.encoder;

import com.google.zxing.WriterException;
import com.google.zxing.common.BitArray;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.decoder.Version;

import java.nio.charset.Charset;

/** Accesses ZXing's package-level encoder primitives to force QR byte mode. */
public final class PerlOnJavaByteModeEncoder {
    private PerlOnJavaByteModeEncoder() {
    }

    public static QRCode encode(String text, Charset charset, ErrorCorrectionLevel level,
                                int requestedVersion, int mask) throws WriterException {
        BitArray data = new BitArray();
        Encoder.append8BitBytes(text, data, charset);

        Version version = requestedVersion > 0
                ? Version.getVersionForNumber(requestedVersion)
                : smallestVersion(data, level);
        BitArray headerAndData = new BitArray();
        Encoder.appendModeInfo(Mode.BYTE, headerAndData);
        Encoder.appendLengthInfo(data.getSizeInBytes(), version, Mode.BYTE, headerAndData);
        headerAndData.appendBitArray(data);

        Version.ECBlocks ecBlocks = version.getECBlocksForLevel(level);
        int totalCodewords = version.getTotalCodewords();
        int dataCodewords = totalCodewords - ecBlocks.getTotalECCodewords();
        if (!Encoder.willFit(headerAndData.getSize(), version, level)) {
            throw new WriterException("Data too big for requested version");
        }
        Encoder.terminateBits(dataCodewords, headerAndData);
        BitArray finalBits = Encoder.interleaveWithECBytes(
                headerAndData, totalCodewords, dataCodewords, ecBlocks.getNumBlocks());

        ByteMatrix matrix = new ByteMatrix(
                version.getDimensionForVersion(), version.getDimensionForVersion());
        MatrixUtil.buildMatrix(finalBits, level, version, mask, matrix);
        QRCode result = new QRCode();
        result.setMode(Mode.BYTE);
        result.setECLevel(level);
        result.setVersion(version);
        result.setMaskPattern(mask);
        result.setMatrix(matrix);
        return result;
    }

    private static Version smallestVersion(BitArray data, ErrorCorrectionLevel level)
            throws WriterException {
        for (int number = 1; number <= 40; number++) {
            Version version = Version.getVersionForNumber(number);
            int bits = 4 + Mode.BYTE.getCharacterCountBits(version) + data.getSize();
            if (Encoder.willFit(bits, version, level)) return version;
        }
        throw new WriterException("Data too big");
    }
}
