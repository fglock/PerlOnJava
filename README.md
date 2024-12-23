# PerlOnJava Perl Compiler

This project presents a Perl compiler that compiles Perl into Java bytecode and runs it, providing a method to integrate Perl with Java-based ecosystems.

## Table of Contents

1. [Introduction](#introduction)
2. [Features and Limitations](#features-and-limitations)
3. [Target Audience](#target-audience)
4. [Build Instructions](#build-instructions)
5. [Running the JAR File](#running-the-jar-file)
6. [Debugging Tools](#debugging-tools)
7. [Internal Modules](#internal-modules)
8. [Milestones](#milestones)
9. [License](#license)
10. [Additional Information and Resources](#additional-information-and-resources)

## Introduction

This project aims to develop a Perl compiler that translates Perl code into Java bytecode and executes it on the Java Virtual Machine (JVM). It provides a platform for running Perl scripts in a JVM environment, facilitating integration between Perl and Java.

## Features and Limitations

### Features

- **Closures**: Supports anonymous functions and lexical variable closures.
- **Eval-string**: Executes Perl code dynamically.
- **Statements, Data Types, and Call Context**: Handles common Perl statements, data types, and maintains Perl's context sensitivity.
- **Java Scripting API (JSR 223)**: Enables Perl scripts to be executed within Java applications using the `ScriptEngine` interface.

For a detailed feature list, see the [FEATURE_MATRIX.md](FEATURE_MATRIX.md).

### Current Limitations

- **Limited Module Support**: Does not support XS modules or many CPAN libraries.
- **Syntax Compatibility**: May not fully support advanced syntax-changing modules or esoteric Perl features.

### What This Project Is

- **A Complement to Perl**: This project complements Perl by enabling its integration with Java environments.

## Target Audience

- **Java Developers with Perl Knowledge**: Provides a method for integrating Perl scripts into Java applications.
- **Compiler and Language Enthusiasts**: Offers insights into translating high-level languages into JVM bytecode.
- **Experimenters and Tinkerers**: A tool for experimenting with language interoperability.

## Build Instructions

### Compile and Package with Maven

1. **Ensure you have Maven installed**:
   - You can download and install Maven from [Maven's official website](https://maven.apache.org/).

2. **Compile and Package the Project**:
   - Run the following Maven command to compile and package the project into a shaded JAR:

    ```bash
    mvn clean package
    ```

3. **Run the JAR**:
   - After packaging, you can run the JAR file with:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar
    ```

### Compile and Package with Gradle

1. **Ensure you have Gradle installed**:
   - You can download and install Gradle from [Gradle's official website](https://gradle.org/).

2. **Compile and Package the Project**:
   - Run the following Gradle command to compile and package the project into a shaded JAR:

    ```bash
    gradle clean build
    ```

3. **Run the JAR**:
   - After packaging, you can run the JAR file with:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar
    ```

### Dependencies

- **JUnit**: For testing.
- **ASM**: For bytecode manipulation.
- **ICU4J**: For Unicode support.
- **FASTJSON v2**: For JSON support.

### Adding JDBC Drivers

JDBC Database drivers can be added in two ways:

1. Using the `Configure.pl` script to add the drivers to the Perl jar file:

  The Configure script updates the build configuration to install the JDBC database drivers in the PerlOnJava jar file.

  - Run `./Configure.pl --search my-driver-name` to register the drivers - for example:

  ```bash
  ./Configure.pl --search aws-mysql-jdbc
  ```

  - Configure will locate the driver name using Maven Central search: https://search.maven.org/, and prompt you to confirm the driver name if there are multiple matches.
  - Run the usual `mvn clean package` or `gradle clean build` to build with the drivers included
  - Run your Perl script the usual way:

  ```bash
  java -jar target/perlonjava-1.0-SNAPSHOT.jar myscript.pl
  ```

2. Using the Java classpath to load the drivers dynamically:

  - Download the JDBC database driver jar file.
  - Run your Perl script with the full class path including the JDBC driver:

  ```bash
  java -cp "jdbc-drivers/h2-2.2.224.jar:target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main myscript.pl
  ```


### Notes

- The project uses the `maven-shade-plugin` for Maven to create a shaded JAR.
- The project uses the `com.github.johnrengelman.shadow` plugin for Gradle to create a shaded JAR.
- Both Maven and Gradle configurations are set to use Java 21.

## Running the JAR File

1. **Show Instructions**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --help
    ```

2. **Execute Something**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -E ' print 123 '
    ```

Setting `lib` path with `-I` to access Perl modules is optional. Standard modules are included in the jar file.

## Debugging Tools

1. **Execute Emitting Debug Information**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -E ' print 123 '
    ```

2. **Compile Only; Can Be Combined with --debug**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -c -E ' print 123 '
    ```

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -c -E ' print 123 '
    ```

3. **Execute and Emit Disassembled ASM Code**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --disassemble -E ' print 123 '
    ```

4. **Run the Lexer Only**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --tokenize -E ' print 123 '
    ```

5. **Run the Parser Only**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --parse -E ' print 123 '
    ```

## Internal Modules

### Project Structure

```
/
├── src/
│   ├── main/
│   │   └── perl/
│   │   │   └── lib/
│   │   │       └── Perl modules (strict.pm, etc)
│   │   └── java/
│   │       └── org/
│   │           └── perlonjava/
│   │               ├── Main.java
│   │               ├── ArgumentParser.java
│   │               ├── scriptengine/
│   │               │   ├── PerlScriptEngine.java
│   │               │   └── other script engine classes
│   │               ├── astnode/
│   │               │   ├── Node.java
│   │               │   └── other AST node classes
│   │               ├── lexer/
│   │               │   ├── Lexer.java
│   │               │   └── other lexer classes
│   │               ├── parser/
│   │               │   ├── Parser.java
│   │               │   └── other parser classes
│   │               ├── perlmodule/
│   │               │   ├── Universal.java
│   │               │   └── other internalized Perl module classes
│   │               ├── operators/
│   │               |   ├── OperatorHandler.java
│   │               |   ├── ArithmeticOperators.java
│   │               |   └── other operator handling classes
│   │               ├── io/
│   │               |   ├── SocketIO.java
│   │               |   └── other io classes
│   │               └── runtime/
│   │                   ├── RuntimeScalar.java
│   │                   └── other runtime classes
│   └── test/
│       ├── java/
│       │   └── org/
│       │       └── perlonjava/
│       │           └── PerlLanguageProviderTest.java
│       └── resources/
│           └── Perl test files
├── build.gradle
├── pom.xml
├── settings.gradle
├── examples/
│   └── Perl example files
└── misc/
    └── project notes
```


### Lexer and Parser

- **Lexer**: Used to split the code into symbols like space, identifier, operator.
- **Parser**: Picks up the symbols and organizes them into an Abstract Syntax Tree (AST) of objects like block, subroutine.
- **StringParser**: Used to parse domain-specific languages within Perl, such as Regex and string interpolation.

### Code Generation

- **EmitterVisitor**: Used to generate the bytecode for the operations within the method. It traverses the AST and generates the corresponding ASM bytecode.
- **EmitterContext**: Holds the current state of the Symbol Table and calling context (void, scalar, list).
- **PrinterVisitor**: Provides pretty-print stringification for the AST.
- **EmitterMethodCreator**: Used to generate the bytecode for the class. The user code is translated into a method, then the generated bytecode is loaded using a custom class loader.

### AST Nodes: *Node*

- Representations of AST nodes for code blocks, variable declarations, and operations.

### Runtime packages: *Runtime* and *Operators*

- **Runtime**: Provides the implementation of the behavior of a Perl scalar variable, Code, Array, Hash.
- **ScopedSymbolTable**: Manage variable names and their corresponding local variable indices.

### Perl Module classes

- **perlmodule**: Provides ports of Perl classes implemented in Java, such as `UNIVERSAL` and `Symbol`.

### Main Method

- The main method generates the bytecode for the program body.
- The generated method is loaded into a variable as a code reference and executed.

### PerlScriptEngine

- `PerlScriptEngine` is a Java class that allows you to execute Perl scripts using the Java Scripting API (JSR 223).

## Milestones

For detailed information on completed and upcoming milestones, see the [MILESTONES.md](MILESTONES.md).

## License

This project is licensed under the Perl Artistic License 2.0 - see the [LICENSE](LICENSE.md) file for details.

## Additional Information and Resources


- Brian Jepson in TPJ, 1997 [JPL: The Java-Perl Library](https://www.foo.be/docs/tpj/issues/vol2_4/tpj0204-0003.html).

- Kuhn, Bradley M. (January 2001). [Considerations on Porting Perl to the Java Virtual Machine](http://www.ebb.org/bkuhn/writings/technical/thesis/) (MS thesis). University of Cincinnati.

- Bradley Kuhn, 2002 [perljvm: Using B to Facilitate a Perl Port To the Java Virtual Machine](https://www.ebb.org/bkuhn/articles/perljvm.html).

- Ehud Lamm, 2004 [Tim Bray: Sun & Dynamic Java](http://lambda-the-ultimate.org/node/426).

- Ben Evans in perl5.porters, 2009 [p5 on the JVM](https://www.nntp.perl.org/group/perl.perl5.porters/2009/08/msg150099.html).

- Nikos Vaggalis, 2013 [All about Perl 6 – interview of Flávio Glock](http://www.josetteorama.com/all-about-perl-6-interview-of-flavio-glock/).

- Andrew Shitov, 2015 [Interview with Flávio Glock](https://andrewshitov.com/2015/05/15/interview-with-flavio-glock/).

- Nikos Vaggalis, 2024 [Flavio Glock On Perl, Java, Compilers And Virtual Machines](https://www.i-programmer.info/professional-programmer/103-i-programmer/17491-flavio-glock-on-perl-java-compilers-and-virtual-machines.html).

- For more details on the relationship with the ["Perlito5" Perl to Java compiler and Perl to JavaScript compiler](https://github.com/fglock/Perlito), including its influence on this project and how it complements the PerlOnJava Perl Compiler, see [RELATION_WITH_PERLITO_COMPILER.md](misc/RELATION_WITH_PERLITO_COMPILER.md). This document provides insights into the historical context and technical decisions that shaped the development of this compiler.


![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)

