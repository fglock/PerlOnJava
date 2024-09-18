# Debugging PerlOnJava with JPDA

Note: untested!

Run the compiled code with the JPDA options, e.g.:

```
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=1044 -jar your-compiled-perl.jar
```

Connect a JPDA-compatible debugger (like jdb or an IDE debugger) to port 1044.

Then we need a debug client that understands the mapping between the Java bytecode and the original Perl source.
This client would use JPDA to communicate with the JVM, but would present the debug information in terms of the original Perl code.

