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
    void vivify() {
        String parentValue = lvalue.toString();
        String newValue = this.toString();
        int actualOffset = offset < 0 ? parentValue.length() + offset : offset;

        if (actualOffset < 0) {
            actualOffset = 0;
        }

        // Add this check to throw an exception when offset is beyond string length
        if (actualOffset > parentValue.length()) {
            throw new RuntimeException("substr outside of string");
        }

        StringBuilder updatedValue = new StringBuilder(parentValue);

        // Modify this part to handle negative length as 0
        int actualLength = Math.max(length, 0);

        if (actualOffset >= parentValue.length()) {
            updatedValue.append(" ".repeat(actualOffset - parentValue.length()));
            updatedValue.append(newValue);
        } else {
            int endIndex = Math.min(actualOffset + actualLength, parentValue.length());
            updatedValue.replace(actualOffset, endIndex, newValue);
        }

        lvalue.set(new RuntimeScalar(updatedValue.toString()));
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        this.type = value.type;
        this.value = value.value;
        vivify();
        return this;
    }
}
