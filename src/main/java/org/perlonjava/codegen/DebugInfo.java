package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import java.util.*;

public class DebugInfo {
    private static final Map<String, SourceFileInfo> sourceFiles = new HashMap<>();
    private static final ArrayList<String> packageNamePool = new ArrayList<>();
    private static final Map<String, Integer> packageNameToId = new HashMap<>();

    private static class SourceFileInfo {
        final String fileName;
        final Map<Integer, LineInfo> tokenToLineInfo = new HashMap<>();

        SourceFileInfo(String fileName) {
            this.fileName = fileName;
        }
    }

    private record LineInfo(int lineNumber, int packageNameId) {}

    private static int getOrCreatePackageId(String packageName) {
        return packageNameToId.computeIfAbsent(packageName, name -> {
            packageNamePool.add(name);
            return packageNamePool.size() - 1;
        });
    }

    static void setDebugInfoFileName(EmitterContext ctx) {
        String sourceFile = ctx.compilerOptions.fileName;
        sourceFiles.computeIfAbsent(sourceFile, SourceFileInfo::new);
        ctx.cw.visitSource(ctx.compilerOptions.fileName, null);
    }

    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        String sourceFile = ctx.compilerOptions.fileName;
        SourceFileInfo info = sourceFiles.computeIfAbsent(sourceFile, SourceFileInfo::new);

        info.tokenToLineInfo.put(tokenIndex, new LineInfo(
                ctx.errorUtil.getLineNumber(tokenIndex),
                getOrCreatePackageId(ctx.symbolTable.getCurrentPackage())
        ));

        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(tokenIndex, thisLabel);
    }

    public static SourceLocation parseStackTraceElement(StackTraceElement element) {
        String sourceFile = element.getFileName();
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = sourceFiles.get(sourceFile);
        if (info == null) {
            return new SourceLocation(sourceFile, "", tokenIndex);
        }

        LineInfo lineInfo = info.tokenToLineInfo.get(tokenIndex);
        return new SourceLocation(
                sourceFile,
                packageNamePool.get(lineInfo.packageNameId()),
                lineInfo.lineNumber()
        );
    }

    public record SourceLocation(String sourceFileName, String packageName, int lineNumber) {}
}
