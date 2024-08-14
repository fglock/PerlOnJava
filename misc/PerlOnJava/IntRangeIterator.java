import java.util.Iterator;
import java.util.NoSuchElementException;

public class IntRangeIterator implements Iterator<Integer> {
    private int current; // Current integer in the iteration
    private final int end; // End value (exclusive)

    // Constructor to initialize the start and end values
    public IntRangeIterator(int start, int end) {
        this.current = start; // Initialize current to start
        this.end = end; // Set the end value
    }

    // Check if there are more elements in the iteration
    @Override
    public boolean hasNext() {
        return current < end; // Return true if current is less than end
    }

    // Get the next element in the iteration
    @Override
    public Integer next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more elements in the range."); // Throw exception if no next element
        }
        return current++; // Return the current value and increment it
    }
}
