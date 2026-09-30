package org.perlonjava.runtime.runtimetypes;

/** An lvalue for a sparse array slot returned by list-context reverse. */
public final class RuntimeArrayReverseHoleProxy extends RuntimeArrayProxyEntry {
    private final boolean sourceWasHole;

    public RuntimeArrayReverseHoleProxy(RuntimeArray parent, int index) {
        super(parent, index);
        this.sourceWasHole = index >= parent.elements.size() || parent.elements.get(index) == null;
    }

    @Override
    public void addToArray(RuntimeArray array) {
        if (preservesSourceHole(array)) {
            array.elements.add(null);
        } else {
            super.addToArray(array);
        }
    }

    @Override
    public RuntimeArray setArrayOfAlias(RuntimeArray array) {
        if (preservesSourceHole(array)) {
            array.elements.add(null);
            return array;
        }
        return super.setArrayOfAlias(array);
    }

    private boolean preservesSourceHole(RuntimeArray target) {
        return target.type == RuntimeArray.PLAIN_ARRAY
                && isUnmaterializedSourceHole();
    }

    public boolean isUnmaterializedSourceHole() {
        return sourceWasHole && !hasLvalue();
    }
}
