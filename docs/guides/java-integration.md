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

### Passing Variables

```java
ScriptEngine engine = manager.getEngineByName("perl");

// Set variables
engine.put("name", "World");
engine.put("count", 42);

// Access in Perl
engine.eval("say \"Hello, $name! Count: $count\"");
```

### Getting Results

```java
// Evaluate and get result
Object result = engine.eval("2 + 2");
System.out.println("Result: " + result);  // Output: Result: 4

// Use variables
engine.put("x", 10);
engine.put("y", 20);
Object sum = engine.eval("$x + $y");
System.out.println("Sum: " + sum);  // Output: Sum: 30
```

### Using Perl Subroutines

```java
ScriptEngine engine = manager.getEngineByName("perl");

// Define subroutine
engine.eval("sub multiply { my ($a, $b) = @_; return $a * $b; }");

// Call it
engine.put("x", 5);
engine.put("y", 7);
Object result = engine.eval("multiply($x, $y)");
System.out.println("Result: " + result);  // Output: Result: 35
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

## Using PerlOnJava Directly

For more control, you can use PerlOnJava's internal API directly.

### Compiling Perl Code

```java
import org.perlonjava.runtime.runtimetypes.PerlCompiler;

public class DirectExample {
    public static void main(String[] args) {
        PerlCompiler compiler = new PerlCompiler();
        compiler.compile("say 'Hello World';");
        compiler.run();
    }
}
```

## Building with PerlOnJava

### Maven

Add PerlOnJava as a dependency:

```xml
<dependency>
    <groupId>org.perlonjava</groupId>
    <artifactId>perlonjava</artifactId>
    <version>5.42.2</version>
</dependency>
```

### Gradle

```gradle
dependencies {
    implementation 'org.perlonjava:perlonjava:5.42.2'
}
```

### Manual JAR

1. Build PerlOnJava:
   ```bash
   make
   ```

2. Find JAR in `build/libs/perlonjava-*-all.jar`

3. Add to your classpath:
   ```bash
   javac -cp perlonjava-5.42.2-all.jar YourApp.java
   java -cp .:perlonjava-5.42.2-all.jar YourApp
   ```

## Use Cases

### Configuration Scripts

Use Perl for flexible configuration:

```java
ScriptEngine engine = manager.getEngineByName("perl");
engine.eval(Files.readString(Path.of("config.pl")));
Object config = engine.get("config");
```

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

## Examples

See also:
- [Quick Start](../../QUICKSTART.md) - Basic examples
- [Architecture](../reference/architecture.md) - How it works internally

## Troubleshooting

### Engine Not Found

If `getEngineByName("perl")` returns null:
1. Ensure `perlonjava-*.jar` is in classpath
2. Check `META-INF/services/javax.script.ScriptEngineFactory` exists in JAR
3. Verify Java 21 or later is being used

### ClassNotFoundException

Make sure the PerlOnJava JAR is in your classpath when compiling and running.

### Performance

- First execution may be slow (JIT compilation)
- Subsequent executions are faster
- Consider caching compiled scripts
