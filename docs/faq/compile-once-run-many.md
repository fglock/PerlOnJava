# Compile-Once, Run-Many Pattern in PerlOnJava

## Your Question

You're asking about a pattern like this:

```java
RuntimeCode code = (RuntimeCode) PerlLanguageProvider.compilePerlCode(parsedArgs); // once
// ... then many times with different args:
parsedArgs = ArgumentParser.parseArguments(newargs);
PerlLanguageProvider.executeCode();
// read output from some stream
```

This is a reasonable request, and we investigated whether this pattern can work.

## What Already Exists

PerlOnJava already supports a compile-once/run-many pattern via the **JSR 223 ScriptEngine API**:

```java
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.app.scriptengine.PerlScriptEngineFactory;
import org.perlonjava.runtime.runtimetypes.*;
import javax.script.*;

// Initialize
PerlLanguageProvider.resetAll();
ScriptEngine engine = new PerlScriptEngineFactory().getScriptEngine();

// Compile once
CompiledScript compiled = ((Compilable) engine).compile(scriptContent);

// Execute many times with different @ARGV
for (String[] args : listOfArguments) {
    // Set @ARGV before each execution
    RuntimeArray argv = GlobalVariable.getGlobalArray("main::ARGV");
    argv.setFromList(new RuntimeList());  // clear
    for (String arg : args) {
        RuntimeArray.push(argv, new RuntimeScalar(arg));
    }
    
    // Execute
    compiled.eval();
}
```

## What Works

We tested this pattern and confirmed:

| Feature | Status |
|---------|--------|
| Compile script once | ✅ Works |
| Set `@ARGV` per execution | ✅ Works |
| Multiple executions | ✅ Works |

Here's a working example:

```java
// Script that uses @ARGV
String script = """
    print "ARGV has " . scalar(@ARGV) . " elements:\n";
    for my $arg (@ARGV) {
        print "  $arg\n";
    }
    """;

CompiledScript compiled = ((Compilable) engine).compile(script);

// Run 1: with "foo", "bar"
RuntimeArray argv = GlobalVariable.getGlobalArray("main::ARGV");
argv.setFromList(new RuntimeList());
RuntimeArray.push(argv, new RuntimeScalar("foo"));
RuntimeArray.push(argv, new RuntimeScalar("bar"));
compiled.eval();
// Output: ARGV has 2 elements: foo, bar

// Run 2: with different args
argv.setFromList(new RuntimeList());
RuntimeArray.push(argv, new RuntimeScalar("hello"));
RuntimeArray.push(argv, new RuntimeScalar("world"));
compiled.eval();
// Output: ARGV has 2 elements: hello, world
```

## The Problem: Script State Persistence

When we tested with ExifTool's CLI script, we found a fundamental issue:

```
=== Processing: Canon.jpg ===
Make: Canon
Model: Canon EOS DIGITAL REBEL

=== Processing: Nikon.jpg ===
Make: Canon              <-- WRONG! Should be Nikon
Model: Canon EOS DIGITAL REBEL  <-- Cached from previous run
```

**The problem**: Perl scripts are designed to run once. They typically:
- Initialize global state in the main body
- Cache data during execution
- Assume a fresh environment on each run

When you run a compiled script multiple times without reset:
- Global variables retain their values
- Cached data persists
- Internal state accumulates

## Why `resetAll()` Doesn't Solve It

We tried calling `PerlLanguageProvider.resetAll()` between runs:

```java
for (String image : images) {
    PerlLanguageProvider.resetAll();  // Reset state
    // ... set @ARGV and @INC ...
    compiled.eval();
}
```

**Result**: "Can't locate object method 'new' via package 'Image::ExifTool'"

The problem is that `resetAll()` clears **everything**, including loaded modules. The `use Image::ExifTool` statement ran during compilation (in a BEGIN block), but after reset, that module code is gone.

| Reset Level | Clears Variables | Clears Modules | Works? |
|-------------|-----------------|----------------|--------|
| None | ❌ | ❌ | State accumulates |
| `resetAll()` | ✅ | ✅ | Breaks module loading |
| Targeted reset | ✅ | ❌ | Doesn't exist yet |

## Recommendations for ExifTool

### Option 1: Use the Module API (Recommended)

The `ExifToolExample.java` pattern is the right approach for ExifTool because **Image::ExifTool module** is designed for repeated calls, unlike the CLI script:

```java
// Load module ONCE
String initScript = """
    use Image::ExifTool;
    our $exif = Image::ExifTool->new();
    
    sub process_image {
        my ($file, @tags) = @_;
        return $exif->ImageInfo($file, @tags);
    }
    """;
PerlLanguageProvider.executePerlCode(options, true);

// Call MANY TIMES - this is fast and correct!
RuntimeScalar processImage = GlobalVariable.getGlobalCodeRef("main::process_image");
for (String image : images) {
    RuntimeArray args = new RuntimeArray();
    RuntimeArray.push(args, new RuntimeScalar(image));
    RuntimeArray.push(args, new RuntimeScalar("Make"));
    RuntimeArray.push(args, new RuntimeScalar("Model"));
    RuntimeCode.apply(processImage, args, RuntimeContextType.SCALAR);
}
```

If you're seeing comparable speed to command-line execution, there might be something else going on - the module approach should be significantly faster because there's no process startup overhead.

### Option 2: ExifTool's `-stay_open` Mode

If you prefer working with command lines, ExifTool has a built-in daemon mode designed for batch processing:

```bash
exiftool -stay_open True -@ commands.txt
```

You send commands via stdin and read results from stdout, keeping one ExifTool process alive.

### Option 3: Simple Scripts Without State

The compile-once/run-many pattern works well for scripts that:
- Don't accumulate global state
- Have all logic in subroutines
- Don't rely on one-time initialization in the main body

## Summary

Your proposed API makes sense conceptually, but there's a fundamental mismatch between how scripts work and the compile-once/run-many pattern:

1. **Scripts assume fresh state** on each run
2. **Compiled code shares state** across executions
3. **Full reset breaks modules** that were loaded at compile time

For ExifTool specifically, the **module-based approach** (`Image::ExifTool`) is the correct solution because it's designed for repeated calls. The CLI script is designed for single execution.
