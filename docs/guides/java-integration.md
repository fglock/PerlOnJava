# Java Integration Guide

How to use PerlOnJava from Java applications.

## JSR-223 Scripting API

PerlOnJava implements the Java Scripting API (JSR-223), allowing you to execute Perl code from Java.

### Basic Usage

```java
import javax.script.*;

public class PerlExample {
    public static void main(String[] args) throws Exception {
        // Get Perl engine
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        // Execute Perl code
        engine.eval("print 'Hello from Perl!\\n'");
    }
}
```

### Getting Results

```java
// Evaluate and get result
Object result = engine.eval("2 + 2");
System.out.println("Result: " + result);  // Output: Result: 4
```

`ScriptEngine.put()` and other Java `Bindings` are not yet bridged to Perl
globals. Put values into the Perl source explicitly (with appropriate quoting
or serialization), or expose a purpose-built Java-backed Perl module through
the documented `XSLoader` integration. Binding bridging is tracked as remaining
JSR-223 work in the [roadmap](../about/roadmap.md#jsr-223-compliance-improvements).

### Compiling Once and Running Repeatedly

```java
import javax.script.Compilable;
import javax.script.CompiledScript;

CompiledScript script = ((Compilable) engine).compile("40 + 2");
System.out.println(script.eval());  // Output: 42
System.out.println(script.eval());  // Reuses the compiled script
```

### Error Handling

```java
ScriptEngine engine = manager.getEngineByName("perl");

try {
    engine.eval("die 'Something went wrong';");
} catch (ScriptException e) {
    System.err.println("Perl error: " + e.getMessage());
}
```

### Handling Script Exit

When a Perl script calls `exit()`, PerlOnJava throws a `PerlExitException` instead of
terminating the JVM. This allows your Java application to handle script completion
gracefully and continue execution.

Note: Like in standard Perl, `exit()` is not caught by Perl's `eval{}` blocks - it
always propagates to the Java caller.

```java
import org.perlonjava.runtime.runtimetypes.PerlExitException;

ScriptEngine engine = manager.getEngineByName("perl");

try {
    engine.eval("print 'Processing...'; exit 0;");
} catch (ScriptException e) {
    if (e.getCause() instanceof PerlExitException exitEx) {
        System.out.println("Script exited with code: " + exitEx.getExitCode());
        // Continue with other work...
    } else {
        throw e;
    }
}
```

This is particularly important when running scripts like ExifTool that call `exit()`
after completing their work:

```java
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.PerlExitException;

String[] scriptArgs = new String[]{"path/to/exiftool", "-ver"};
CompilerOptions options = ArgumentParser.parseArguments(scriptArgs);

try {
    PerlLanguageProvider.executePerlCode(options, true);
} catch (PerlExitException e) {
    System.out.println("ExifTool exited with code: " + e.getExitCode());
    // Script completed successfully, continue processing...
}
```

## Building with PerlOnJava

PerlOnJava is not yet published to Maven Central. You can use the runnable JAR
directly. When embedding a source checkout, build and test it with the project
Makefile:

```bash
make
```

### Runnable JAR from a source build

1. Build PerlOnJava:
   ```bash
   make
   ```

2. Find the runnable JAR in `target/perlonjava-*.jar`

3. Add to your classpath:
   ```bash
   javac -cp target/perlonjava-5.44.0.jar YourApp.java
   java --enable-native-access=ALL-UNNAMED -cp .:target/perlonjava-5.44.0.jar YourApp
   ```

## Use Cases

### Configuration Scripts

Use Perl for flexible configuration:

```java
ScriptEngine engine = manager.getEngineByName("perl");
Object configText = engine.eval(Files.readString(Path.of("config.pl")));
```

Have the script return a string value (for example JSON) and decode it in Java;
`engine.get()` does not currently read Perl globals.

### Data Processing

```java
ScriptEngine engine = manager.getEngineByName("perl");

// Process CSV with Perl
engine.eval("""
    use Text::CSV;
    my $csv = Text::CSV->new();
    # ... process CSV data
    """);
```

### Legacy Perl Integration

Run existing Perl scripts from Java:

```java
ScriptEngine engine = manager.getEngineByName("perl");
String perlScript = Files.readString(Path.of("legacy.pl"));
engine.eval(perlScript);
```

### Repeated Execution / Batch Processing

For processing multiple items (e.g., files), don't run a CLI script repeatedly.
Instead, use the Perl module directly and call methods in a loop:

```java
// Load module once, call methods repeatedly
String initCode = """
    use Image::ExifTool;
    our $exif = Image::ExifTool->new();
    
    sub process_file {
        my ($file) = @_;
        return $exif->ImageInfo($file, qw(Make Model));
    }
    1;
    """;

engine.eval(initCode);

// Now call the subroutine for each file - no recompilation needed
for (String file : files) {
    engine.eval("process_file('" + file + "')");
}
```

See `examples/ExifToolExample.java` and `examples/ExifToolExample.pl` for a complete
working example using Image::ExifTool.

## Examples

- `examples/ExifToolExample.pl` - Batch image processing with Image::ExifTool
- `examples/ExifToolExample.java` - Java integration example

See also:
- [Quick Start](../../QUICKSTART.md) - Basic examples
- [Architecture](../reference/architecture.md) - How it works internally

## Troubleshooting

### Engine Not Found

If `getEngineByName("perl")` returns null:
1. Ensure `perlonjava-*.jar` is in classpath
2. Check `META-INF/services/javax.script.ScriptEngineFactory` exists in JAR
3. Verify Java 24 or later is being used

### ClassNotFoundException

Make sure the PerlOnJava JAR is in your classpath when compiling and running.

### Performance

- First execution may be slow (JIT compilation)
- Subsequent executions are faster
- Consider caching compiled scripts
