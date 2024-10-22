# Perl Compiler Under Development

This is a Perl compiler under development. It compiles Perl into Java bytecode and runs it.

## Table of Contents

1. [Introduction](#introduction)
2. [Features](#features)
2. [Build](#build)
3. [Running the jar file](#running-the-jar-file)
4. [Debugging Tools](#debugging-tools)
5. [Internal Modules](#internal-modules)
6. [Milestones](#milestones)
7. [License](#license)

## Introduction

This project aims to develop a Perl compiler that translates Perl code into Java bytecode and executes it on the Java
Virtual Machine (JVM). While the project is still under active development, it provides an experimental platform for
running Perl scripts in a JVM environment.

### Key Goals

* **Seamless Execution of Perl Scripts on the JVM**: The primary focus is on enabling the execution of small to
  medium-sized Perl scripts on the JVM, offering a unique way to integrate Perl with Java-based ecosystems.
* **Exploring Perl and Java Interoperability**: This project serves as a bridge between Perl and Java, allowing
  developers to experiment with interactions between the two languages.
* **Compiler Design and Bytecode Generation**: The project offers a platform for those interested in the intricacies of
  compiler design and bytecode generation, utilizing the ASM library for dynamic Java bytecode creation.

### Current Limitations

This project is an ambitious attempt to bring Perl to the JVM, but it's important to set realistic expectations
regarding its current state and scope:

* **Limited Module Support**: The compiler does not support XS modules or many CPAN libraries, particularly those that
  rely heavily on C extensions (e.g., `Moose`, `DBI`, `List::Util`, and `Socket`). This restricts its ability to run
  complex Perl applications that depend on such modules.

* **Focus on Small to Medium Scripts**: The compiler is best suited for simpler Perl scripts that do not rely on
  extensive module dependencies or deep Perl features. Large-scale Perl applications, especially those involving heavy
  module usage, are beyond its current capabilities.

* **Syntax Compatibility**: While the compiler handles many of Perl's core features, it may not fully support advanced
  syntax-changing modules or some of the more esoteric features of Perl.

### What This Project Is Not

* **A Drop-in Replacement for Perl**: This is not intended to replace Perl or to be a full Perl interpreter. Instead,
  it’s an experimental tool that provides a Perl-like environment within the JVM. Users should not expect full backwards
  compatibility with native Perl.

### Who Might Find This Useful?

* **Java Developers with Perl Knowledge**: If you're a Java developer familiar with Perl, this project offers an
  interesting way to integrate Perl scripts into Java applications.
* **Compiler and Language Enthusiasts**: Those interested in the process of translating high-level languages into JVM
  bytecode might find the project’s approach and methodology enlightening.
* **Experimenters and Tinkerers**: If you enjoy experimenting with language interoperability or are looking for a unique
  way to run Perl scripts on the JVM, this project might be a fun tool to explore.

### Future Directions

The project is evolving, and while it currently supports a subset of Perl features, future development may expand its
capabilities. Community feedback and contributions are welcome to help guide its direction.

The project is structured into several modules, including a lexer, parser, and bytecode generator. Each module plays a
crucial role in the compilation process, from tokenizing the Perl script to generating the corresponding Java bytecode.

### Relation with the Perlito compiler

The key difference between PerlOnJava and Perlito (https://github.com/fglock/Perlito) is in their compilation approach. Perlito is a bootstrapped Perl compiler, written in Perl, which compiles Perl code to Java and then to bytecode. PerlOnJava, on the other hand, is a native Perl compiler for the JVM that directly generates Java bytecode using the ASM library. This approach makes PerlOnJava more efficient, particularly in terms of eval execution speed, and results in smaller jar files, leading to faster startup times.

From an architectural standpoint, PerlOnJava is more mature. However, Perlito is currently more feature-rich due to its longer development history. PerlOnJava, however, doesn't support JavaScript like Perlito does.

Both compilers share certain limitations imposed by the JVM, such as the lack of support for DESTROY, XS modules, and auto-closing filehandles, among others.

## Features

This compiler currently supports several key Perl features:

- **Closures**: Supports anonymous functions and lexical variable closures, allowing for encapsulation of code.
- **Eval-string**: Executes Perl code dynamically, supporting basic expressions and statements.
- **Statements, Data Types, and Call Context**: Handles common Perl statements (e.g., `if`, `foreach`), data types (
  e.g., scalars, arrays, hashes), and maintains Perl’s context sensitivity (e.g., scalar vs list context).

Additionally, it supports the Java Scripting API (JSR 223), enabling Perl scripts to be executed within Java
applications using the `ScriptEngine` interface. Note that while this provides integration with Java, there may be
limitations compared to native Perl execution.

However, some areas present challenges:

- **CPAN Modules and XS Code**: Compiling CPAN modules and XS (C-extensions) is challenging due to dependencies on
  native code and system libraries. Support for these features is limited and require additional development.

For the most up-to-date information on features and limitations, please refer to the [FEATURE_MATRIX](FEATURE_MATRIX.md)
file.

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

### Dependencies

- **JUnit**: For testing.
- **ASM**: For bytecode manipulation.
- **ICU4J**: For Unicode support.

### Notes

- The project uses the `maven-shade-plugin` for Maven to create a shaded JAR.
- The project uses the `com.github.johnrengelman.shadow` plugin for Gradle to create a shaded JAR.
- Both Maven and Gradle configurations are set to use Java 21.

## Running the jar file

1. **Show Instructions**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --help
    ```

2. **Execute Something**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -e ' print 123 '
    ```

    Setting `lib` path with `-I` to access Perl modules is optional. Standard modules are included in the jar file.

## Debugging Tools

1. **Execute Emitting Debug Information**:
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
3. **Execute and Emit Disassembled ASM Code**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --disassemble -e ' print 123 '
    ```

4. **Run the Lexer Only**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --tokenize -e ' print 123 '
    ```

5. **Run the Parser Only**:
    ```sh
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --parse -e ' print 123 '
    ```

## Internal Modules

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

### Runtime classes: *Runtime*

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

### Completed Milestones

- **v1.0.0**: Initial proof of concept for the parser and execution engine.
- **v1.1.0**: Established architecture and added key features. The system now supports benchmarks and tests.
    - JSR 223 integration
    - Support for closures
    - Eval-string functionality
    - Enhanced statements, data types, and call context
- **v1.2.0**: Added Namespaces and named subroutines.
    - Added typeglobs
    - Added more operators
- **v1.3.0**: Added Objects.
    - Objects and object operators, UNIVERSAL class
    - Array and List related operators
    - More tests and various bug fixes
- **v1.4.0**: I/O operators
    - File i/o operators, STDOUT, STDERR, STDIN
    - TAP (Perl standard) tests
- **v1.5.0**: Regex operators
    - Added Regular expressions and pattern matching: m//, pos, qr//, quotemeta, s///, split
    - More complete set of operations on strings, numbers, arrays, hashes, lists
    - More special variables
    - More tests and various bug fixes
- **v1.6.0**: Module System and Standard Library Enhancements
    - Module system for improved code organization and reuse
    - Core Perl module operators: `do FILE`, `require`, `caller`, `use`, `no`
    - Module special subroutines: `import`, `unimport`
    - Environment and special variables: `PERL5LIB`, `@INC`, `%INC`, `@ARGV`, `%ENV`, `$0`, `$$`
    - Additional operators: `die`, `warn`, `time`, `times`, `localtime`, `gmtime`, `index`, `rindex`
    - Standard library ported modules: `Data::Dumper`, `Symbol`, `strict`
    - Expanded documentation and usage examples
- **v1.7.0**: Performance Improvements
    - Focus on optimizing the execution engine for better performance.
    - Improve error handling and debugging tools to make development easier. More detailed debugging symbols added to the bytecode. Added `Carp` module.
    - Moved Perl standard library modules into the jar file.
    - More tests and various bug fixes
- **v1.8.0**: Operators
    - Added `continue` blocks and loop operators `next`, `last`, `redo`; a bare-block is a loop
    - Added bitwise operators `vec`, `pack`, `unpack`
    - Added `srand`, `crypt`, `exit`, ellipsis statement (`...`)
    - Added `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`
    - Added file test operators like `-d`, `-f`
    - Added the variants of diamond operator `<>` and special cases of `while`
    - Completed `chomp` operator; fixed `qw//` operator, `defined-or` and `x=`
    - Added modules: `parent`, `Test::More`

### Upcoming Milestones

- **v1.9.0**: Concurrency and Security Features
    - Planned release date: 2024-12-10
    - Added bitwise string operators.
    - Added lvalue `substr`, lvalue `vec`
    - Fix `%b` specifier in `sprintf`
    - Emulate Perl behaviour with unsigned integers in bitwise operators.
    - Regex `m?pat?` match-once and the `reset()` operator are implemented.
    - Regex `\G` and the `pos` operator are implemented.
    - Regex `@-`, `@+`, `%+`, `%-` special variables are implemented.
    - Regex `` $` ``, `$&`, `$'` special variables are implemented.
    - Regex performance comparable to Perl; optimized regex variables.
    - Regex matching plain strings: `$var =~ "Test"`.
    - Added `__SUB__` keyword; `readpipe`.
    - Added `&$sub` call syntax.
    - Added `local` dynamic variables.
    - Tests in `src/test/resources` are executed automatically.
    - Work in progress: 
        - `socket` and related operators
    - Stretch goals
        - Add support for concurrency and parallelism, such as threads and async/await.
        - Enhance security features, including sandboxing and input validation.
        - Increase test coverage and introduce automated testing tools.

- **v1.10.0**: External Integration and Advanced Data Manipulation
    - Integrate with external libraries and APIs for tasks like HTTP requests and database access.
    - Add advanced data manipulation features, such as JSON/XML parsing and data transformation.
    - Allow users to define their own operators and macros for greater flexibility.

- **v2.0.0**: Major Release with Breaking Changes
    - Perform comprehensive refactoring and optimization.
    - Introduce significant new features and improvements.
    - Ensure full compliance with relevant standards and best practices.

## License

This project is licensed under the Perl Artistic License 2.0 - see the [LICENSE](LICENSE.md) file for details.

![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)

