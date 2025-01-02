# Debugging PerlOnJava with JPDA

Note: untested!

Run the compiled code with the JPDA options, e.g.:

```
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=1044 -jar your-compiled-perl.jar
```

Connect a JPDA-compatible debugger (like jdb or an IDE debugger) to port 1044.

Then we need a debug client that understands the mapping between the Java bytecode and the original Perl source.
This client would use JPDA to communicate with the JVM, but would present the debug information in terms of the original Perl code.

# Debugging Perl Code with IntelliJ

## Setup Remote Debug Configuration

1. In IntelliJ IDEA:
   - Go to Run -> Edit Configurations
   - Click + to add new configuration
   - Select "Remote JVM Debug"
   - Set Name: "Debug Perl"
   - Port: 5005
   - Click Apply and OK

## Run PerlOnJava with Debug Flags

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 -jar target/perlonjava-1.0-SNAPSHOT.jar myscript.pl
```

## Start Debugging

1. In your Perl source file:
   - Set breakpoints by clicking in the left gutter
   - Run the "Debug Perl" configuration
   - The debugger connects and stops at breakpoints

## Debug Commands

- Step Over: F8
- Step Into: F7
- Step Out: Shift+F8
- Resume: F9

## View Debug Information

- Variables window shows current values
- Stack trace shows execution path
- Breakpoint window manages break conditions


