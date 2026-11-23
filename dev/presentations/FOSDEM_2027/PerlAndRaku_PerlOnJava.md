# PerlOnJava: Perl 5 on the JVM With Moose, DBI, and Threads

## Abstract

The hard part of running Perl 5 on the JVM is preserving the language's behaviour and making CPAN modules work on a different runtime.

PerlOnJava compiles Perl 5 to JVM bytecode. It targets blead, Perl's latest development branch, and runs on Java 24 or later. It ships as a single JAR for Linux, macOS, and Windows, including more than 150 modules and a full toolchain: jperl, jcpan, jprove, jperldoc. The license is the same as Perl's.

The talk covers Perl features that are hard to map onto the JVM and how Perl modules implemented in Java connect the two ecosystems. Practical examples cover running existing Perl scripts, accessing databases with DBI over JDBC, and embedding Perl in Java applications through the Java Scripting API (JSR-223).

All 575 selected imported core test files from blead now pass. Continuous testing covers a rolling sample of 10,000 randomly selected CPAN modules; of those whose tests pass under standard Perl, more than two-thirds also pass under PerlOnJava.

The main gap remaining is fork emulation without OS processes.

