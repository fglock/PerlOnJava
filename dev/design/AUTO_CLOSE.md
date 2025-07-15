# Alternatives for DESTROY and auto-close


The key problem is about reference tracking:

```
my $file = IO::File->new("test.txt");
push @global_array, $file;  # Now $file lives beyond its scope
```

Even though $file goes out of scope, it's still alive in @global_array. Java's garbage collection doesn't provide a way to make this visible (old JVMs had a way, but it was removed).

Static analysis can only handle straightforward cases - it can't promise to handle DESTROY all the time.



# Local

org.perlonjava.codegen.Local.localTeardown() can run a method on a variable when it leaves the scope.

# Try-with-Resources

```
public class MyResource implements AutoCloseable {
    public MyResource() {
        // Initialize resource
    }

    @Override
    public void close() {
        // Cleanup logic here
        System.out.println("Resource cleaned up");
    }
}

public class Main {
    public static void main(String[] args) {
        try (MyResource resource = new MyResource()) {
            // Use the resource
        } // Automatically calls resource.close() here
    }
}
```

# Phantom References

## Initial Approach (Incorrect)

```
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public class FileResource {
    private FileWriter fileWriter;

    public FileResource(String filePath) throws IOException {
        File file = new File(filePath);
        this.fileWriter = new FileWriter(file);
    }

    public void writeData(String data) throws IOException {
        fileWriter.write(data);
    }

    public void close() throws IOException {
        if (fileWriter != null) {
            fileWriter.close();
            System.out.println("File closed.");
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ReferenceQueue<FileResource> referenceQueue = new ReferenceQueue<>();
        FileResource fileResource = new FileResource("example.txt");
        PhantomReference<FileResource> phantomRef = new PhantomReference<>(fileResource, referenceQueue);

        // Use the file resource
        fileResource.writeData("Hello, World!");

        // Remove the strong reference
        fileResource = null;

        // Request garbage collection
        System.gc();

        // Simulate some delay
        Thread.sleep(1000);

        // Check if the object is enqueued
        if (referenceQueue.poll() != null) {
            // Perform cleanup actions here
            System.out.println("Performing cleanup before object is collected.");
            phantomRef.get().close(); // WRONG: This won't work - get() always returns null!
        }
    }
}
```

## Corrected Approach Using openHandles Map

The key insight is that `PhantomReference.get()` always returns `null`, so we cannot directly access the object to close it. Instead, we can leverage RuntimeIO's existing `openHandles` map to track and close file handles.

### Implementation Strategy

1. **Track PhantomReferences with their IOHandles**:

```java
// Add to RuntimeIO class
private static final ReferenceQueue<RuntimeIO> referenceQueue = new ReferenceQueue<>();
private static final Map<PhantomReference<RuntimeIO>, IOHandle> phantomToHandle = new ConcurrentHashMap<>();

// When opening a file
public static RuntimeIO open(String fileName, String mode) {
    RuntimeIO fh = new RuntimeIO();
    // ... existing open logic ...
    
    // Create phantom reference for cleanup
    PhantomReference<RuntimeIO> phantomRef = new PhantomReference<>(fh, referenceQueue);
    phantomToHandle.put(phantomRef, fh.ioHandle);
    
    return fh;
}
```

2. **Monitor the ReferenceQueue for cleanup**:

```java
// Cleanup thread or periodic check
private static void processPhantomReferences() {
    PhantomReference<? extends RuntimeIO> ref;
    while ((ref = (PhantomReference<? extends RuntimeIO>) referenceQueue.poll()) != null) {
        IOHandle handle = phantomToHandle.remove(ref);
        if (handle != null && openHandles.containsKey(handle)) {
            try {
                handle.close();
                openHandles.remove(handle);
            } catch (Exception e) {
                // Log cleanup failure
            }
        }
        ref.clear(); // Important: clear the phantom reference
    }
}
```

3. **Alternative: WeakReference in openHandles**:

```java
// Instead of Map<IOHandle, Boolean>, use:
private static final Map<WeakReference<RuntimeIO>, IOHandle> weakToHandle = 
    new LinkedHashMap<>(MAX_OPEN_HANDLES, 0.75f, true);

// This allows the RuntimeIO to be garbage collected while still tracking its IOHandle
```

### Considerations

1. **Thread Safety**: The phantom reference processing must be synchronized with normal file operations
2. **Timing**: Cleanup happens when GC runs, which is non-deterministic
3. **Resource Limits**: Still need the LRU cache to prevent resource exhaustion before GC
4. **Performance**: Need to balance frequency of reference queue polling
5. **Memory**: Phantom references add memory overhead until cleared

### Hybrid Approach

Combine multiple strategies:
- Use `Local.localTeardown()` for deterministic cleanup in simple cases
- Use PhantomReferences for complex cases where static analysis fails
- Keep the LRU cache as a safety net for resource limits
- Provide explicit `close()` methods for user control

This approach gets closer to Perl's DESTROY semantics while working within Java's garbage collection constraints.
