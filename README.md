# Perl Compiler Under Development

This is a Perl compiler under development. It compiles Perl into Java bytecode and runs it.

## Table of Contents

1. [Introduction](#introduction)
2. [Features](#features)
2. [Build](#build)
3. [Running the jar file](#running-the-jar-file)
4. [Debugging Tools](#debugging-tools)
5. [Modules](#modules)
6. [Using Java Scripting API](#using-java-scripting-api)
7. [Milestones](#milestones)
8. [Benchmarks](#benchmarks)
9. [License](#license)

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

## Features

This compiler currently supports several key Perl features:

- **Closures**: Supports anonymous functions and lexical variable closures, allowing for encapsulation of code.
- **Eval-string**: Executes Perl code dynamically, supporting basic expressions and statements.
- **Statements, Data Types, and Call Context**: Handles common Perl statements (e.g., `if`, `foreach`), data types (e.g., scalars, arrays, hashes), and maintains Perl’s context sensitivity (e.g., scalar vs list context).

Additionally, it supports the Java Scripting API (JSR 223), enabling Perl scripts to be executed within Java applications using the `ScriptEngine` interface. Note that while this provides integration with Java, there may be limitations compared to native Perl execution.

However, some areas present challenges:

- **CPAN Modules and XS Code**: Compiling CPAN modules and XS (C-extensions) is challenging due to dependencies on native code and system libraries. Support for these features is limited and require additional development.

For the most up-to-date information on features and limitations, please refer to the [FEATURE_MATRIX](FEATURE_MATRIX.md) file.

## Build


### Compile and Package with Maven

1. **Ensure you have Maven installed**:
    - You can download and install Maven from [Maven's official website](https://maven.apache.org/).

2. **Compile and Package the Project**:
    - Run the following Maven command to compile and package the project into a shaded JAR:
      ```sh
      mvn clean package
      ```

3. **Run the JAR**:
    - After packaging, you can run the JAR file with:
      ```sh
      java -jar target/perlonjava-1.0-SNAPSHOT.jar
      ```

### Compile and Package with Gradle

1. **Ensure you have Gradle installed**:
    - You can download and install Gradle from [Gradle's official website](https://gradle.org/).

2. **Compile and Package the Project**:
    - Run the following Gradle command to compile and package the project into a shaded JAR:
      ```sh
      gradle clean build
      ```

3. **Run the JAR**:
    - After packaging, you can run the JAR file with:
      ```sh
      java -jar target/perlonjava-1.0-SNAPSHOT.jar
      ```

### Project Structure

```
/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── org/
│   │           └── perlonjava/
│   │               ├── Main.java
│   │               ├── other Java classes
│   │               ├── astnode/
│   │               │   ├── Node.java
│   │               │   └── other AST node classes
│   │               ├── lexer/
│   │               │   ├── Lexer.java
│   │               │   └── other lexer classes
│   │               ├── parser/
│   │               │   ├── Parser.java
│   │               │   └── other parser classes
│   │               └── runtime/
│   │                   ├── RuntimeScalar.java
│   │                   └── other runtime classes
│   └── test/
│       └── java/
│           └── org/
│               └── perlonjava/
│                   └── PerlLanguageProviderTest.java
├── build.gradle
├── pom.xml
├── settings.gradle
├── examples/
└── misc/
    └── project notes
```

### Dependencies

- **JUnit**: For testing.
- **ASM**: For bytecode manipulation.

### Notes

- The project uses the `maven-shade-plugin` for Maven to create a shaded JAR.
- The project uses the `com.github.johnrengelman.shadow` plugin for Gradle to create a shaded JAR.
- Both Maven and Gradle configurations are set to use Java 11.



## Running the jar file

1. **Show Instructions**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --help
    ```

2. **Execute Something**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -e ' print 123 '
    ```

## Debugging Tools

1. **Run Emitting Debug Information**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -e ' print 123 '
    ```

2. **Compile Only; Can Be Combined with --debug**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -c -e ' print 123 '
    ```

    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -c -e ' print 123 '
    ```

3. **Run the Lexer Only**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --tokenize -e ' print 123 '
    ```

4. **Run the Parser Only**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --parse -e ' print 123 '
    ```

## Modules

### Lexer and Parser
- **Lexer**: Used to split the code into symbols like space, identifier, operator.
- **Parser**: Picks up the symbols and organizes them into an Abstract Syntax Tree (AST) of objects like block, subroutine.

### StringParser
- StringParser is used to parse domain-specific languages within Perl, such as Regex and string interpolation.

### ClassWriter
- **ClassWriter**: Used to generate the bytecode for the class.
- The user code is translated into a method.
- The generated bytecode is loaded using a custom class loader.

### EmitterVisitor and EmitterContext
- **EmitterVisitor**: Used to generate the bytecode for the operations within the method.
- It traverses the AST and generates the corresponding ASM bytecode.
- **EmitterContext**: Holds the current state of the Symbol Table and calling context (void, scalar, list).
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

### PerlScriptEngine

- `PerlScriptEngine` is a Java class that allows you to execute Perl scripts using the Java Scripting API (JSR 223).

## Using Java Scripting API

### Using jrunscript

- jrunscript implements a generic interactive shell using Java Scripting API.

- Note that `jrunscript` creates a new scope every time, so it doesn't keep lexical variables from one line to the next.

  ```sh
  $ jrunscript -cp target/perlonjava-1.0-SNAPSHOT.jar -l perl 
  Perl5> my $sub = sub { say $_[0] }; $sub->($_) for 4,5,6;
  4
  5
  6
  []
  Perl5>
  ```

### PerlScriptEngine installation

To use `PerlScriptEngine`, include the necessary dependencies in your project. For example, if you are using Maven, add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>org.perlonjava</groupId>
    <artifactId>perl-script-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

### PerlScriptEngine usage

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

## Milestones

### Completed Milestones
- **v1.0.0**: Initial proof of concept for the parser and execution engine.
- **v1.1.0**: Established architecture and added key features. The system now supports benchmarks and tests.
   - JSR 223 integration
   - Support for closures
   - Eval-string functionality
   - Enhanced statements, data types, and call context

### Upcoming Milestones
- **v1.2.0**: Planned release date: 2024-10-01
   - Addition of 20 new operators
   - Various bug fixes and performance improvements

## Benchmarks

### Performance Benchmarks

The following benchmarks provide an order of magnitude comparison with Perl:

- **v1.0.0**:
   - Lexer and Parser: Processes 50k lines per second; direct comparison with Perl is not applicable.

- **v1.1.0**:
   - Numeric operations: 2x faster than Perl
   - String operations: Comparable to Perl
   - Eval-string: 10x slower than Perl

## License

This project is licensed under the Perl License - see the [LICENSE](LICENSE.md) file for details.


![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)

