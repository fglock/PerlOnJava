package org.perlonjava.runtime;
public class RuntimeSubstrLvalue extends RuntimeBaseProxy {
    private final int offset;
    private final int length;

    public RuntimeSubstrLvalue(RuntimeScalar parent, String str, int offset, int length) {
        this.lvalue = parent;
        this.offset = offset;
        this.length = length;

        this.type = RuntimeScalarType.STRING;
        this.value = str;
    }
    @Override
    void vivify() {}

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        this.type = value.type;
        this.value = value.value;

        String parentValue = lvalue.toString();
        String newValue = this.toString();
        int strLength = parentValue.length();
        int actualOffset = offset < 0 ? strLength + offset : offset;

        if (actualOffset < 0) {
            actualOffset = 0;
        }
        if (actualOffset > strLength) {
            throw new RuntimeException("substr outside of string");
        }

        int actualLength = length;
        if (length < 0) {
            actualLength = strLength + length - actualOffset;
        }

        if (actualLength < 0) {
            actualLength = 0;
        }
        if (actualOffset + actualLength > strLength) {
            actualLength = strLength - actualOffset;
        }

        StringBuilder updatedValue = new StringBuilder(parentValue);

        if (actualOffset >= strLength) {
            updatedValue.append(" ".repeat(actualOffset - strLength));
            updatedValue.append(newValue);
        } else {
            int endIndex = actualOffset + actualLength;
            updatedValue.replace(actualOffset, endIndex, newValue);
        }

        lvalue.set(new RuntimeScalar(updatedValue.toString()));

        return this;
    }
}
