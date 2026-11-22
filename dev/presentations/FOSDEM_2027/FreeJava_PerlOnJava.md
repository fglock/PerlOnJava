# PerlOnJava: Compiling Perl 5 to JVM Bytecode — Dynamic Scoping, eval, and Closures

## Abstract

PerlOnJava compiles Perl 5 to JVM bytecode and ships as a single JAR for Linux, macOS, and Windows, targeting blead (Perl's latest development branch) and Java 24 or later. It brings Perl and a growing portion of CPAN, Perl's module ecosystem, to the Java platform as an independent free software project.

The talk covers bytecode generation with ASM, a register-based interpreter that sits alongside compiled execution for eval STRING, dynamic scoping implemented as a save-and-restore stack (the same shape as try-with-resources), and closures that capture mutable variables across compilation units. It also covers replacing C extension libraries with Java-backed implementations — connecting Perl's DBI to JDBC, and making Perl available inside Java applications through JSR-223.

All selected core test files from blead now pass, and thousands of CPAN modules pass their own test suites under PerlOnJava. The main remaining challenge is fork emulation without OS processes. We will discuss this and the performance work ahead.

In the spirit of "Revivify," this is a look at opening new possibilities for existing software through the JVM. No prior Perl knowledge is required.

https://github.com/fglock/PerlOnJava

