# XSLoader in PerlOnJava

Perl XSLoader became the preferred choice for most modules, with DynaLoader reserved for complex cases that need its full feature set.

In Perl, there are two ways to load compiled (XS) extensions:

1. **DynaLoader** - The original, full-featured dynamic loader
2. **XSLoader** - A lightweight, faster alternative introduced later

## The Difference in Perl

```perl
# Old way with DynaLoader
package MyModule;
use DynaLoader;
our @ISA = qw(DynaLoader);
bootstrap MyModule $VERSION;

# New way with XSLoader  
package MyModule;
use XSLoader;
XSLoader::load('MyModule', $VERSION);
```

## Why XSLoader is Preferred

1. **Faster** - Doesn't need inheritance, less overhead
2. **Simpler** - Just a single function call
3. **Smaller memory footprint** - Fewer features means less code
4. **Backward compatible** - Falls back to DynaLoader if needed

## For PerlOnJava: JavaXSLoader

We could implement a similar pattern with a lightweight loader for common cases:

```java:src/main/java/org/perlonjava/perlmodule/JavaXSLoader.java
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight Java class loader for PerlOnJava modules.
 * Similar to XSLoader in Perl - simpler and faster than JavaDynaLoader.
 */
public class JavaXSLoader extends PerlModuleBase {
    
    private static final Map<String, Boolean> loaded = new ConcurrentHashMap<>();
    
    @Override
    public void initialize() {
        exportSub("load");
        exportSub("load_file");
    }
    
    /**
     * Load a Java-based Perl module by name
     * This is the simplified, fast path for built-in modules
     */
    public static RuntimeList load(RuntimeArray args, RuntimeContext ctx) {
        String moduleName = args.get(0).toString();
        String version = args.size() > 1 ? args.get(1).toString() : null;
        
        // Check if already loaded
        if (loaded.containsKey(moduleName)) {
            return new RuntimeList(scalarTrue);
        }
        
        try {
            // Fast path for known modules
            String className = getJavaClassName(moduleName);
            if (className != null) {
                // Direct load without full DynaLoader features
                Class<?> clazz = Class.forName(className);
                Object module = clazz.getDeclaredConstructor().newInstance();
                
                if (module instanceof InitializableModule) {
                    ((InitializableModule) module).initialize();
                }
                
                loaded.put(moduleName, true);
                return new RuntimeList(scalarTrue);
            }
            
            // Fall back to JavaDynaLoader for unknown modules
            return JavaDynaLoader.load_class(args, ctx);
            
        } catch (Exception e) {
            throw new RuntimeException("Can't load module " + moduleName + ": " + e.getMessage());
        }
    }
    
    /**
     * Load a specific .class or .jar file
     * Simplified version without full JAR inspection
     */
    public static RuntimeList load_file(RuntimeArray args, RuntimeContext ctx) {
        String filename = args.get(0).toString();
        
        try {
            if (filename.endsWith(".jar")) {
                // Simple JAR loading
                DynamicClassLoader.loadJar(filename);
            } else if (filename.endsWith(".class")) {
                // Direct class file loading would go here
                throw new RuntimeException("Direct .class loading not yet implemented");
            }
            
            return new RuntimeList(scalarTrue);
        } catch (Exception e) {
            throw new RuntimeException("Can't load file " + filename + ": " + e.getMessage());
        }
    }
    
    /**
     * Fast mapping of Perl module names to Java classes
     * This could be generated at build time for speed
     */
    private static String getJavaClassName(String perlModule) {
        // This mapping could be loaded from a properties file generated at build time
        switch (perlModule) {
            case "Cwd": return "org.perlonjava.perlmodule.Cwd";
            case "File::Basename": return "org.perlonjava.perlmodule.FileBasename";
            case "Data::Dumper": return "org.perlonjava.perlmodule.DataDumper";
            case "DBI": return "org.perlonjava.perlmodule.Dbi";
            // ... etc
            default: return null;
        }
    }
}
```

## Usage in Perl Modules

```perl:lib/File/Basename.pm
package File::Basename;

use strict;
use warnings;

our $VERSION = '2.85';

# Modern, lightweight approach
use JavaXSLoader;
JavaXSLoader::load('File::Basename', $VERSION);

1;
```

Compare to the heavier JavaDynaLoader approach:

```perl:lib/Some/Complex/Module.pm
package Some::Complex::Module;

use JavaDynaLoader;
our @ISA = qw(JavaDynaLoader);

# Load multiple JARs
JavaDynaLoader::load_jar('lib/dependency1.jar');
JavaDynaLoader::load_jar('lib/dependency2.jar');

# Import specific methods
JavaDynaLoader::import_methods('com.example.SomeClass', 'Some::Complex::Module');

1;
```

## Build-Time Optimization

Generate the module mappings at build time:

```java:src/build/java/org/perlonjava/build/GenerateXSLoaderMappings.java
public class GenerateXSLoaderMappings {
    public static void main(String[] args) throws IOException {
        Properties mappings = new Properties();
        
        // Scan for modules
        Reflections reflections = new Reflections("org.perlonjava.perlmodule");
        Set<Class<? extends InitializableModule>> modules = 
            reflections.getSubTypesOf(InitializableModule.class);
        
        for (Class<?> moduleClass : modules) {
            String perlName = extractPerlModuleName(moduleClass);
            mappings.put(perlName, moduleClass.getName());
        }
        
        // Write to resources
        try (FileWriter writer = new FileWriter("src/main/resources/xsloader-mappings.properties")) {
            mappings.store(writer, "Generated module mappings for JavaXSLoader");
        }
    }
}
```

## Benefits of This Approach

1. **Performance**: Direct module loading without scanning
2. **Simplicity**: Most modules just need simple loading
3. **Compatibility**: Falls back to full JavaDynaLoader when needed
4. **Memory**: Smaller footprint for simple cases
5. **Build-time optimization**: Module discovery happens at build time

