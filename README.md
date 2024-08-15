# Perl Compiler Under Development

This is a Perl compiler under development. It compiles Perl into Java bytecode and runs it.

## Table of Contents

1. [Introduction](#introduction)
2. [Compile and Package](#compile-and-package)
    - [Using Maven](#using-maven)
    - [Using javac (Manual Compilation)](#using-javac-manual-compilation)
3. [Running the Script Engine](#running-the-script-engine)
    - [Using jrunscript](#using-jrunscript)
    - [Running with Main Class](#running-with-main-class)
4. [Debugging Tools](#debugging-tools)
5. [Modules](#modules)

## Introduction

This project aims to develop a Perl compiler that translates Perl code into Java bytecode and executes it.
The compiler is still under development and is intended to provide a way to run Perl scripts on the Java
Virtual Machine (JVM). The project leverages the ASM library to generate Java bytecode dynamically.

The primary goals of this project are:
- To provide a seamless way to run Perl scripts on the JVM.
- To explore the integration of Perl and Java, allowing for interoperability between the two languages.
- To offer a platform for experimenting with compiler design and bytecode generation.

The project is structured into several modules, including a lexer, parser, and bytecode generator. Each
module plays a crucial role in the compilation process, from tokenizing the Perl script to generating the
corresponding Java bytecode.

## Compile and Package

### Using Maven

1. **Ensure you have Maven installed**:
    - You can download and install Maven from [Maven's official website](https://maven.apache.org/).

2. **Add the ASM dependency and Maven Shade Plugin**:
    - Ensure your `pom.xml` includes the ASM dependency and the Maven Shade Plugin as shown below:

    ```xml
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <groupId>org.perlonjava</groupId>
        <artifactId>perlonjava</artifactId>
        <version>1.0-SNAPSHOT</version>

        <dependencies>
            <dependency>
                <groupId>org.ow2.asm</groupId>
                <artifactId>asm</artifactId>
                <version>9.2</version>
            </dependency>
            <!-- Other dependencies -->
        </dependencies>

        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.2.4</version>
                    <executions>
                        <execution>
                            <phase>package</phase>
                            <goals>
                                <goal>shade</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </project>
    ```

3. **Compile and Package the Project**:
    - Run the following Maven command to compile and package the project into a shaded JAR:
      ```sh
      mvn clean package
      ```

4. **Locate the Shaded JAR**:
    - After the build process completes, the shaded JAR file will be located in the `target` directory, typically named `perlonjava-1.0-SNAPSHOT-shaded.jar`.

### Using javac (Manual Compilation)

If you prefer to compile the project manually using `javac`, follow these steps:

1. **Download ASM Library**:
    - Ensure you have the ASM library (e.g., `asm-9.7.jar`) downloaded and available.

2. **Compile the Java Files**:
    - Use the following command to compile the Java files, updating the path to `asm-9.7.jar` as necessary:
      ```sh
      javac -cp ./asm-9.7.jar -d . src/main/java/org/perlonjava/*.java src/main/java/org/perlonjava/node/*.java
      ```

## Running the Script Engine

### Using jrunscript

1. **Run the Perl Script Engine**:
    - After compiling and packaging, you can run the Perl script engine using `jrunscript`:
      ```sh
      jrunscript -cp target/perlonjava-1.0-SNAPSHOT-shaded.jar -l perl
      ```

2. **Example Usage**:
    - Once `jrunscript` is running, you can execute Perl scripts directly in the interactive shell. Note that the CLI creates a new context every time, so it doesn't keep lexical variables from one line to the next.

      ```sh
      $ jrunscript -cp target/perlonjava-1.0-SNAPSHOT.jar -l perl 
      Perl5> my $sub = sub { say $_[0] }; $sub->($_) for 4,5,6;
      4
      5
      6
      []
      Perl5>
      ```

    - `jrunscript` accepts Perl compiler debugging options, but only if a filename is provided:

      ```
      jrunscript -cp target/perlonjava-1.0-SNAPSHOT.jar -l perl test.pl --tokenize
      ```

      ```
      jrunscript -cp target/perlonjava-1.0-SNAPSHOT.jar -l perl test.pl --parse
      ```

### Running with Main Class

1. **Show Instructions**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main --help
    ```

2. **Execute Something**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main -e ' print 123 '
    ```

## Debugging Tools

1. **Run Emitting Debug Information**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main --debug -e ' print 123 '
    ```

2. **Compile Only; Can Be Combined with --debug**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main -c -e ' print 123 '
    ```

    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main --debug -c -e ' print 123 '
    ```

3. **Run the Lexer Only**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main --tokenize -e ' print 123 '
    ```

4. **Run the Parser Only**:
    ```sh
    java -cp ./asm-9.7.jar:. org.perlonjava.Main --parse -e ' print 123 '
    ```

## Modules

### Lexer and Parser
- **Lexer**: Used to split the code into symbols like space, identifier, operator.
- **Parser**: Picks up the symbols and organizes them into an Abstract Syntax Tree (AST) of objects like block, subroutine.

### ClassWriter
- **ClassWriter**: Used to generate the bytecode for the class.
- The user code is translated into a method.
- The generated bytecode is loaded using a custom class loader.

### EmitterVisitor and EmitterContext
- **EmitterVisitor**: Used to generate the bytecode for the operations within the method.
- It traverses the AST and generates the corresponding ASM bytecode.
- **EmitterContext**: Holds the current state of the Symbol Table and calling context (void, scalar, etc).
- **PrinterVisitor**: Provides pretty-print stringification for the AST.

### AST Nodes: *Node
- Representations of AST nodes for code blocks, variable declarations, and operations.

### Symbol Table
- **SymbolTable** and **ScopedSymbolTable**: Manage variable names and their corresponding local variable indices.

### Runtime classes: Runtime*
- **Runtime**: Provides the implementation of the behavior of a Perl scalar variable, Code, Array, Hash.

### Main Method
- The main method generates the bytecode for the program body.
- The generated method is loaded into a variable as a code reference and executed.


## PerlScriptEngine

`PerlScriptEngine` is a Java class that allows you to execute Perl scripts using the Java Scripting API (JSR 223).

### Features

- Execute Perl scripts from Java.
- Supports script execution with various configurations.
- Handles script execution errors gracefully.

### Installation

To use `PerlScriptEngine`, include the necessary dependencies in your project. For example, if you are using Maven, add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>org.perlonjava</groupId>
    <artifactId>perl-script-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Usage

#### Basic Usage

Here is an example of how to use `PerlScriptEngine` to execute a simple Perl script:

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public class Main {
    public static void main(String[] args) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        String script = "print 'Hello, Perl from Java!';";

        try {
            engine.eval(script);
        } catch (ScriptException e) {
            e.printStackTrace();
        }
    }
}
```

#### Using ScriptContext

You can also pass a `ScriptContext` to the `eval` method to configure the script execution:

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;
import javax.script.ScriptException;

public class Main {
    public static void main(String[] args) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        String script = "print 'Hello, Perl with context!';";

        ScriptContext context = new SimpleScriptContext();
        context.setAttribute("fileName", "example.pl", ScriptContext.ENGINE_SCOPE);
        context.setAttribute("debugEnabled", true, ScriptContext.ENGINE_SCOPE);

        try {
            engine.eval(script, context);
        } catch (ScriptException e) {
            e.printStackTrace();
        }
    }
}
```

#### Reading Script from a Reader

You can also read the script from a `Reader`:

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;
import javax.script.ScriptException;
import java.io.StringReader;

public class Main {
    public static void main(String[] args) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        String script = "print 'Hello, Perl from Reader!';";
        StringReader reader = new StringReader(script);

        ScriptContext context = new SimpleScriptContext();
        context.setAttribute("fileName", "example.pl", ScriptContext.ENGINE_SCOPE);
        context.setAttribute("debugEnabled", true, ScriptContext.ENGINE_SCOPE);

        try {
            engine.eval(reader, context);
        } catch (ScriptException e) {
            e.printStackTrace();
        }
    }
}
```

### Error Handling

`PerlScriptEngine` handles errors gracefully by wrapping exceptions in `ScriptException` and providing meaningful error messages.

## License

This project is licensed under the Perl License - see the [LICENSE](LICENSE.md) file for details.

