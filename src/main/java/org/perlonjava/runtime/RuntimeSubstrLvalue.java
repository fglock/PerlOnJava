package org.perlonjava.runtime;

public class RuntimeSubstrLvalue extends RuntimeBaseProxy {
    private final RuntimeScalar parent;
    private int offset;
    private final int length;
    private final boolean isNegativeOffset;

    public RuntimeSubstrLvalue(RuntimeScalar parent, int offset, int length) {
        super();
        this.parent = parent;
        this.offset = offset;
        this.length = length;
        this.isNegativeOffset = offset < 0;
    }

    @Override
    void vivify() {
        if (lvalue == null) {
            String parentValue = parent.toString();
            int actualOffset = isNegativeOffset ? parentValue.length() + offset : offset;
            int endIndex = Math.min(actualOffset + length, parentValue.length());

            if (actualOffset < 0) {
                actualOffset = 0;
            }

            if (actualOffset < parentValue.length()) {
                String substring = parentValue.substring(actualOffset, endIndex);
                lvalue = new RuntimeScalar(substring);
            } else {
                lvalue = new RuntimeScalar();
            }
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        vivify();
        RuntimeScalar result = super.set(value);
        updateParent();
        return result;
    }

    private void updateParent() {
        String parentValue = parent.toString();
        String newValue = lvalue.toString();
        int actualOffset = isNegativeOffset ? parentValue.length() + offset : offset;

        if (actualOffset < 0) {
            actualOffset = 0;
        }

        StringBuilder updatedValue = new StringBuilder(parentValue);

        if (actualOffset >= parentValue.length()) {
            updatedValue.append(" ".repeat(actualOffset - parentValue.length()));
            updatedValue.append(newValue);
        } else {
            int endIndex = Math.min(actualOffset + length, parentValue.length());
            updatedValue.replace(actualOffset, endIndex, newValue);
        }

        parent.set(new RuntimeScalar(updatedValue.toString()));

        if (isNegativeOffset) {
            offset = parent.toString().length() + offset;
        }
    }
}
