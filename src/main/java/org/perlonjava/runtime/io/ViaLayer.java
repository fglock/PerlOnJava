package org.perlonjava.runtime.io;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.runtimetypes.DestroyDispatch;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeGlob;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.Charset;

/**
 * Perl-implemented PerlIO layer, created by :via(...).
 */
public class ViaLayer implements IOLayer {
    private final String resolvedPackage;
    private final RuntimeIO belowIO;
    private final RuntimeScalar belowGlob;
    private final RuntimeScalar self;
    private final StringBuilder fillBuffer = new StringBuilder();

    private final RuntimeScalar readMethod;
    private final RuntimeScalar fillMethod;
    private final RuntimeScalar writeMethod;
    private final RuntimeScalar closeMethod;
    private final RuntimeScalar flushMethod;
    private final RuntimeScalar filenoMethod;
    private final RuntimeScalar eofMethod;
    private final RuntimeScalar tellMethod;
    private final RuntimeScalar seekMethod;
    private final RuntimeScalar poppedMethod;

    private boolean popped;
    private boolean holdsSelfReference;

    public ViaLayer(String requestedPackage, IOHandle belowHandle, String mode) {
        this.belowIO = new RuntimeIO(new BorrowedIOHandle(belowHandle));
        RuntimeGlob glob = new RuntimeGlob(null).setIO(belowIO);
        RuntimeScalar globRef = new RuntimeScalar();
        globRef.type = RuntimeScalarType.GLOBREFERENCE;
        globRef.value = glob;
        this.belowGlob = globRef;

        this.resolvedPackage = resolvePackage(requestedPackage);
        RuntimeScalar pushedMethod = requiredMethod("PUSHED", resolvedPackage);
        this.self = callFunction(
                pushedMethod,
                new RuntimeScalar(resolvedPackage),
                new RuntimeScalar(mode == null ? "" : mode),
                belowGlob);
        if (isPushFailure(self)) {
            ensureIOError();
            throw new IllegalArgumentException("PerlIO layer :via(" + requestedPackage + ") PUSHED failed");
        }
        holdSelfReference();

        String callbackPackage = callbackPackage();
        this.readMethod = optionalMethod("READ", callbackPackage);
        this.fillMethod = optionalMethod("FILL", callbackPackage);
        this.writeMethod = optionalMethod("WRITE", callbackPackage);
        this.closeMethod = optionalMethod("CLOSE", callbackPackage);
        this.flushMethod = optionalMethod("FLUSH", callbackPackage);
        this.filenoMethod = optionalMethod("FILENO", callbackPackage);
        this.eofMethod = optionalMethod("EOF", callbackPackage);
        this.tellMethod = optionalMethod("TELL", callbackPackage);
        this.seekMethod = optionalMethod("SEEK", callbackPackage);
        this.poppedMethod = optionalMethod("POPPED", callbackPackage);
    }

    @Override
    public String getLayerName() {
        return "via(" + resolvedPackage + ")";
    }

    @Override
    public String processInput(String input) {
        return input;
    }

    @Override
    public String processOutput(String output) {
        return output;
    }

    @Override
    public RuntimeScalar onRead(int maxBytes, Charset charset) {
        if (readMethod != null) {
            RuntimeScalar buffer = byteScalar("");
            RuntimeScalar count = callObject(readMethod, buffer, new RuntimeScalar(maxBytes), belowGlob);
            if (!count.getDefinedBoolean()) {
                return RuntimeScalarCache.scalarUndef;
            }
            int bytesRead = count.getInt();
            if (bytesRead <= 0) {
                return byteScalar("");
            }
            String data = buffer.toString();
            if (data.length() > bytesRead) {
                data = data.substring(0, bytesRead);
            }
            return byteScalar(data);
        }

        if (fillMethod != null) {
            if (fillBuffer.length() == 0) {
                RuntimeScalar filled = callObject(fillMethod, belowGlob);
                if (!filled.getDefinedBoolean()) {
                    return byteScalar("");
                }
                fillBuffer.append(filled);
            }
            int take = Math.min(maxBytes, fillBuffer.length());
            String data = fillBuffer.substring(0, take);
            fillBuffer.delete(0, take);
            return byteScalar(data);
        }

        return null;
    }

    @Override
    public RuntimeScalar onWrite(String data) {
        if (writeMethod == null) {
            return belowIO.write(data);
        }

        RuntimeScalar result = callObject(writeMethod, byteScalar(data), belowGlob);
        if (!result.getDefinedBoolean() || !result.getBoolean() || isMinusOne(result)) {
            return callbackFailure();
        }
        return result;
    }

    @Override
    public RuntimeScalar onFlush() {
        if (flushMethod == null) {
            return null;
        }
        RuntimeScalar result = callObject(flushMethod, belowGlob);
        if (!result.getDefinedBoolean() || !result.getBoolean() || isMinusOne(result)) {
            return callbackFailure();
        }
        return result;
    }

    @Override
    public RuntimeScalar onClose() {
        if (closeMethod == null) {
            return null;
        }
        RuntimeScalar result = callObject(closeMethod, belowGlob);
        if (!result.getDefinedBoolean() || !result.getBoolean() || isMinusOne(result)) {
            return callbackFailure();
        }
        return result;
    }

    @Override
    public RuntimeScalar onFileno() {
        if (filenoMethod != null) {
            return callObject(filenoMethod, belowGlob);
        }
        return belowIO.fileno();
    }

    @Override
    public RuntimeScalar onEof() {
        if (eofMethod != null) {
            return callObject(eofMethod, belowGlob);
        }
        return belowIO.eof();
    }

    @Override
    public RuntimeScalar onTell() {
        if (tellMethod != null) {
            return callObject(tellMethod, belowGlob);
        }
        return belowIO.tell();
    }

    @Override
    public RuntimeScalar onSeek(long pos, int whence) {
        if (seekMethod != null) {
            return callObject(seekMethod, belowGlob, new RuntimeScalar(pos), new RuntimeScalar(whence));
        }
        return belowIO.ioHandle.seek(pos, whence);
    }

    @Override
    public void onPopped() {
        if (popped) {
            return;
        }
        popped = true;
        if (poppedMethod != null) {
            callObject(poppedMethod, belowGlob);
        }
        belowIO.unregisterFileno();
        releaseSelfReference();
    }

    @Override
    public boolean interceptsIO() {
        return true;
    }

    private static String resolvePackage(String requestedPackage) {
        if (hasMethod("PUSHED", requestedPackage)) {
            return requestedPackage;
        }
        tryRequire(requestedPackage);
        if (hasMethod("PUSHED", requestedPackage)) {
            return requestedPackage;
        }

        String fallback = requestedPackage.startsWith("PerlIO::via::")
                ? requestedPackage
                : "PerlIO::via::" + requestedPackage;
        if (!fallback.equals(requestedPackage)) {
            if (hasMethod("PUSHED", fallback)) {
                return fallback;
            }
            tryRequire(fallback);
            if (hasMethod("PUSHED", fallback)) {
                return fallback;
            }
        }

        throw new IllegalArgumentException("Cannot resolve PerlIO layer :via(" + requestedPackage + ")");
    }

    private static void tryRequire(String packageName) {
        String fileName = packageName.replace("::", "/") + ".pm";
        try {
            ModuleOperators.require(new RuntimeScalar(fileName));
        } catch (RuntimeException ignored) {
            // Class resolution tries the explicit package first and then PerlIO::via::*.
        }
    }

    private static boolean hasMethod(String methodName, String packageName) {
        return optionalMethod(methodName, packageName) != null;
    }

    private static RuntimeScalar requiredMethod(String methodName, String packageName) {
        RuntimeScalar method = optionalMethod(methodName, packageName);
        if (method == null) {
            throw new IllegalArgumentException("PerlIO layer " + packageName + " has no " + methodName + " method");
        }
        return method;
    }

    private static RuntimeScalar optionalMethod(String methodName, String packageName) {
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, packageName, null, 0, false);
        if (method == null) {
            return null;
        }
        if (method.value instanceof RuntimeCode rc && rc.autoloadVariableName != null) {
            return null;
        }
        return method;
    }

    private RuntimeScalar callObject(RuntimeScalar method, RuntimeBase... args) {
        RuntimeArray array = new RuntimeArray();
        array.elements.add(self);
        for (RuntimeBase arg : args) {
            for (RuntimeScalar scalar : arg) {
                array.elements.add(scalar);
            }
        }
        return RuntimeCode.apply(method, array, RuntimeContextType.SCALAR).getFirst();
    }

    private static RuntimeScalar callFunction(RuntimeScalar method, RuntimeBase... args) {
        RuntimeArray array = new RuntimeArray();
        for (RuntimeBase arg : args) {
            for (RuntimeScalar scalar : arg) {
                array.elements.add(scalar);
            }
        }
        RuntimeList result = RuntimeCode.apply(method, array, RuntimeContextType.SCALAR);
        return result.getFirst();
    }

    private String callbackPackage() {
        int blessId = RuntimeScalarType.blessedId(self);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);
            if (className != null && !className.isEmpty()) {
                return className;
            }
        }
        return resolvedPackage;
    }

    private void holdSelfReference() {
        if (RuntimeScalarType.isReference(self)
                && self.value instanceof RuntimeBase base
                && base.refCount >= 0) {
            base.refCount++;
            holdsSelfReference = true;
        }
    }

    private void releaseSelfReference() {
        if (!holdsSelfReference) {
            return;
        }
        if (RuntimeScalarType.isReference(self) && self.value instanceof RuntimeBase base) {
            if (base.refCount > 0 && --base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
        holdsSelfReference = false;
    }

    private static boolean isPushFailure(RuntimeScalar result) {
        return !result.getDefinedBoolean() || !result.getBoolean() || isMinusOne(result);
    }

    private static boolean isMinusOne(RuntimeScalar result) {
        return switch (result.type) {
            case RuntimeScalarType.INTEGER -> result.getInt() == -1;
            case RuntimeScalarType.DOUBLE -> result.getLong() == -1;
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING -> result.toString().equals("-1");
            default -> false;
        };
    }

    private static RuntimeScalar byteScalar(String data) {
        RuntimeScalar scalar = new RuntimeScalar(data);
        scalar.type = RuntimeScalarType.BYTE_STRING;
        return scalar;
    }

    private static RuntimeScalar callbackFailure() {
        ensureIOError();
        return RuntimeScalarCache.scalarFalse;
    }

    private static void ensureIOError() {
        RuntimeScalar errno = GlobalVariable.getGlobalVariable("main::!");
        if (!errno.getBoolean()) {
            errno.set(5);
        }
    }
}
