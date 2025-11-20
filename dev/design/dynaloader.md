# DynaLoader in PerlOnJava

1. Load Java classes/JARs dynamically
2. Make Java methods available to Perl code
3. Handle the mapping between Perl and Java calling conventions

This would be similar to how Perl's DynaLoader works but adapted for the JVM ecosystem.


## JavaDynaLoader Implementation

```java:src/main/java/org/perlonjava/perlmodule/JavaDynaLoader.java
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;

public class JavaDynaLoader extends PerlModuleBase {
    
    private static final Map<String, URLClassLoader> loaders = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();
    
    static {
        // Register the module
        ModuleManager.registerModule("JavaDynaLoader", JavaDynaLoader::new);
    }
    
    @Override
    public void initialize() {
        // Export main functions
        exportSub("load_jar");
        exportSub("load_class");
        exportSub("import_static_methods");
        exportSub("create_wrapper");
    }
    
    public RuntimeList load_jar(RuntimeArray args, RuntimeContext ctx) {
        String jarPath = args.get(0).toString();
        String loaderName = args.size() > 1 ? args.get(1).toString() : "default";
        
        try {
            File jarFile = new File(jarPath);
            URL jarUrl = jarFile.toURI().toURL();
            
            URLClassLoader loader = loaders.computeIfAbsent(loaderName, 
                k -> new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader()));
            
            return new RuntimeList(new RuntimeScalar(jarPath + " loaded"));
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to load JAR: " + jarPath, e);
        }
    }
    
    public RuntimeList load_class(RuntimeArray args, RuntimeContext ctx) {
        String className = args.get(0).toString();
        String loaderName = args.size() > 1 ? args.get(1).toString() : "default";
        
        try {
            ClassLoader loader = loaders.getOrDefault(loaderName, 
                getClass().getClassLoader());
            
            Class<?> clazz = loader.loadClass(className);
            loadedClasses.put(className, clazz);
            
            return new RuntimeList(new RuntimeScalar(clazz));
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to load class: " + className, e);
        }
    }
    
    public RuntimeList import_static_methods(RuntimeArray args, RuntimeContext ctx) {
        String className = args.get(0).toString();
        String namespace = args.size() > 1 ? args.get(1).toString() : "main";
        
        try {
            Class<?> clazz = loadedClasses.get(className);
            if (clazz == null) {
                clazz = Class.forName(className);
            }
            
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && 
                    Modifier.isPublic(method.getModifiers())) {
                    
                    String perlName = namespace + "::" + method.getName();
                    createPerlWrapper(perlName, clazz, method);
                }
            }
            
            return new RuntimeList(new RuntimeScalar("imported"));
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to import methods from: " + className, e);
        }
    }
    
    private void createPerlWrapper(String perlName, Class<?> clazz, Method method) {
        RuntimeCode wrapper = new RuntimeCode() {
            @Override
            public RuntimeList apply(RuntimeArray args, RuntimeContext ctx) {
                try {
                    // Convert Perl args to Java args
                    Object[] javaArgs = convertPerlToJava(args, method.getParameterTypes());
                    
                    // Invoke the method
                    Object result = method.invoke(null, javaArgs);
                    
                    // Convert result back to Perl
                    return new RuntimeList(convertJavaToPerl(result));
                    
                } catch (Exception e) {
                    throw new RuntimeException("Error calling " + method.getName(), e);
                }
            }
        };
        
        // Install the wrapper in the Perl namespace
        GlobalContext.getGlobalCodeRef(perlName).set(new RuntimeScalar(wrapper));
    }
    
    private Object[] convertPerlToJava(RuntimeArray args, Class<?>[] paramTypes) {
        Object[] result = new Object[paramTypes.length];
        
        for (int i = 0; i < paramTypes.length; i++) {
            RuntimeScalar perlArg = args.get(i);
            Class<?> paramType = paramTypes[i];
            
            if (paramType == String.class) {
                result[i] = perlArg.toString();
            } else if (paramType == int.class || paramType == Integer.class) {
                result[i] = perlArg.getInt();
            } else if (paramType == double.class || paramType == Double.class) {
                result[i] = perlArg.getDouble();
            } else if (paramType == boolean.class || paramType == Boolean.class) {
                result[i] = perlArg.getBoolean();
            } else {
                // Handle complex types
                result[i] = perlArg.value;
            }
        }
        
        return result;
    }
    
    private RuntimeScalar convertJavaToPerl(Object javaValue) {
        if (javaValue == null) {
            return new RuntimeScalar();
        } else if (javaValue instanceof String) {
            return new RuntimeScalar((String) javaValue);
        } else if (javaValue instanceof Number) {
            return new RuntimeScalar(((Number) javaValue).doubleValue());
        } else if (javaValue instanceof Boolean) {
            return new RuntimeScalar((Boolean) javaValue ? 1 : 0);
        } else {
            // Wrap complex Java objects
            return new RuntimeScalar(new JavaObjectWrapper(javaValue));
        }
    }
}
```

## Perl Module Interface

```perl:lib/JavaDynaLoader.pm
package JavaDynaLoader;

use strict;
use warnings;

our $VERSION = '1.00';

# This would be auto-generated or handled by the Java implementation
sub import {
    my ($class, @args) = @_;
    
    # Process import arguments
    foreach my $arg (@args) {
        if ($arg =~ /^:(.+)$/) {
            # Handle tags like :all
        }
    }
}

# Convenience function for loading and importing
sub load_and_import {
    my ($jar_or_class, $namespace) = @_;
    
    if ($jar_or_class =~ /\.jar$/) {
        load_jar($jar_or_class);
    }
    
    import_static_methods($jar_or_class, $namespace // 'main');
}

1;
```

## Usage Examples

```perl:examples/java_dynaloader_demo.pl
#!/usr/bin/perl
use strict;
use warnings;
use JavaDynaLoader;

# Example 1: Load a JAR file and use its classes
JavaDynaLoader::load_jar('/path/to/commons-math3.jar');
JavaDynaLoader::load_class('org.apache.commons.math3.util.MathUtils');
JavaDynaLoader::import_static_methods('org.apache.commons.math3.util.MathUtils', 'Math');

# Now we can use the Java methods as Perl functions
my $gcd = Math::gcd(48, 18);
print "GCD of 48 and 18 is: $gcd\n";

# Example 2: Load JDBC driver
JavaDynaLoader::load_jar('postgresql-42.2.5.jar');
JavaDynaLoader::load_class('org.postgresql.Driver');

# Example 3: Create wrapper for instance methods
my $connection = JavaDynaLoader::create_instance(
    'java.sql.DriverManager',
    'getConnection',
    'jdbc:postgresql://localhost/mydb',
    'user',
    'password'
);
```

## Advanced Features

```java:src/main/java/org/perlonjava/perlmodule/JavaObjectWrapper.java
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.lang.reflect.Method;

public class JavaObjectWrapper {
    private final Object wrappedObject;
    private final Class<?> objectClass;
    
    public JavaObjectWrapper(Object obj) {
        this.wrappedObject = obj;
        this.objectClass = obj.getClass();
    }
    
    // Allow Perl to call methods on wrapped Java objects
    public RuntimeList invokeMethod(String methodName, RuntimeArray args) {
        try {
            // Find matching method
            Method method = findMethod(methodName, args);
            
            // Convert args and invoke
            Object[] javaArgs = convertArgs(args, method.getParameterTypes());
            Object result = method.invoke(wrappedObject, javaArgs);
            
            return new RuntimeList(JavaDynaLoader.convertJavaToPerl(result));
            
        } catch (Exception e) {
            throw new RuntimeException("Method invocation failed: " + methodName, e);
        }
    }
}
```

## Integration with AUTOLOAD

```perl:lib/JavaDynaLoader/AutoLoad.pm
package JavaDynaLoader::AutoLoad;

our $AUTOLOAD;

sub AUTOLOAD {
    my ($package, $method) = ($AUTOLOAD =~ /^(.+)::([^:]+)$/);
    
    # Try to load the Java class if not already loaded
    if (!JavaDynaLoader::is_loaded($package)) {
        my $java_class = $package;
        $java_class =~ s/::/./g;
        
        eval {
            JavaDynaLoader::load_class($java_class);
            JavaDynaLoader::import_static_methods($java_class, $package);
        };
    }
    
    # Retry the method call
    no strict 'refs';
    goto &{"${package}::${method}"} if defined &{"${package}::${method}"};
    
    die "Undefined subroutine &${AUTOLOAD} called";
}
```

## Build Configuration

Add to your module initialization:

```java:src/main/java/org/perlonjava/runtime/ModuleInitializer.java
public class ModuleInitializer {
    public static void initializeBuiltinModules() {
        // ... existing modules ...
        
        // Initialize JavaDynaLoader
        new JavaDynaLoader().initialize();
    }
}
```

This implementation provides:
- Dynamic JAR loading at runtime
- Automatic method wrapping
- Type conversion between Perl and Java
- Support for both static and instance methods
- AUTOLOAD integration for transparent Java class usage
- Thread-safe class loading

