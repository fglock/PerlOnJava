import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class RuntimeList extends RuntimeBase implements RuntimeDataProvider {
    private List<RuntimeBase> elements;
    private boolean isLazy = false;
    private Iterator<RuntimeScalar> lazyIterator; // Store the iterator for lazy generation

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    public RuntimeList(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeArray value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeHash value) {
        this.elements = value.entryArray().elements;
    }

    // Method to generate a lazy RuntimeList
    public static RuntimeList generateLazyList(RuntimeScalar startValue, RuntimeScalar endValue) {
        RuntimeList list = new RuntimeList();
        list.isLazy = true;
        list.lazyIterator = createLazyIterator(startValue, endValue);
        return list;
    }

    // Create a lazy iterator for RuntimeScalar objects
    private static Iterator<RuntimeScalar> createLazyIterator(RuntimeScalar startValue, RuntimeScalar endValue) {
        int start = startValue.getInt();
        int end = endValue.getInt();

        return new Iterator<RuntimeScalar>() {
            private int current = start;

            @Override
            public boolean hasNext() {
                return current <= end;
            }

            @Override
            public RuntimeScalar next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new RuntimeScalar(current++);
            }
        };
    }

    // Method to materialize the list if it's in a lazy state
    private void materializeIfNeeded() {
        if (isLazy) {
            while (lazyIterator.hasNext()) {
                this.elements.add(lazyIterator.next());
            }
            isLazy = false; // The list has been materialized, no longer lazy
        }
    }

    // Override methods that require the list elements
    public int size() {
        materializeIfNeeded();
        return elements.size();
    }

    public RuntimeBase get(int index) {
        materializeIfNeeded();
        return elements.get(index);
    }

    public void add(RuntimeBase value) {
        materializeIfNeeded();
        this.elements.add(value);
    }

    // Other methods that rely on accessing elements will also call materializeIfNeeded()
    ...
}

