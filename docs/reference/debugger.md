# Perl Debugger Reference

The PerlOnJava debugger provides an interactive debugging environment similar to Perl's built-in debugger.

## Starting the Debugger

```bash
./jperl -d script.pl [arguments]
```

The debugger stops at the first executable statement and displays:

```
main::(script.pl:5):
5:  my $x = 10;
  DB<1>
```

## Debugger Commands

### Execution Control

| Command | Description |
|---------|-------------|
| `s` | **Step into** - Execute one statement, stepping into subroutine calls |
| `n` | **Next** - Execute one statement, stepping over subroutine calls |
| `r` | **Return** - Execute until current subroutine returns |
| `c [line]` | **Continue** - Run until breakpoint, end, or specified line (one-time) |
| `q` | **Quit** - Exit the debugger and program |

Press **Enter** to repeat the last command (default: `n`).

### Breakpoints

| Command | Description |
|---------|-------------|
| `b [line]` | Set breakpoint at current or specified line |
| `b file:line` | Set breakpoint at line in specified file |
| `B [line]` | Delete breakpoint at current or specified line |
| `B *` | Delete all breakpoints |
| `L` | List all breakpoints |

### Source Display

| Command | Description |
|---------|-------------|
| `l [range]` | List source code (e.g., `l 10-20`, `l 15`) |
| `.` | Show current line |
| `T` | Show stack trace (call stack) |

### Expression Evaluation

| Command | Description |
|---------|-------------|
| `p expr` | Print expression result |
| `x expr` | Dump expression with Data::Dumper formatting |

### Help

| Command | Description |
|---------|-------------|
| `h` or `?` | Show help |

## Debug Variables

The debugger provides access to standard Perl debug variables:

| Variable | Description |
|----------|-------------|
| `$DB::single` | Single-step mode (1 = enabled) |
| `$DB::trace` | Trace mode (1 = enabled) |
| `$DB::signal` | Signal flag |
| `$DB::filename` | Current filename |
| `$DB::line` | Current line number |
| `%DB::sub` | Subroutine locations (`subname => "file:start-end"`) |
| `@DB::args` | Arguments of current subroutine |

## Examples

### Basic Stepping

```
$ ./jperl -d script.pl
main::(script.pl:5):
5:  my $x = 10;
  DB<1> n
main::(script.pl:6):
6:  my $y = foo($x);
  DB<2> s
main::(script.pl:2):
2:  my ($arg) = @_;
  DB<3>
```

### Setting Breakpoints

```
  DB<1> b 15
Breakpoint set at script.pl:15
  DB<1> b other.pl:20
Breakpoint set at other.pl:20
  DB<1> L
Breakpoints:
  script.pl:15
  other.pl:20
  DB<1> c
```

### Evaluating Expressions

```
  DB<1> p $x + $y
42
  DB<1> p "@DB::args"
10 20 hello
  DB<1> x \@array
$VAR1 = [
          1,
          2,
          3
        ];
```

### Inspecting Subroutine Locations

```
  DB<1> p $DB::sub{"main::foo"}
script.pl:10-15
  DB<1> x \%DB::sub
$VAR1 = {
          'main::foo' => 'script.pl:10-15',
          'main::bar' => 'script.pl:20-25'
        };
```

## Limitations

The following Perl debugger features are not yet implemented:

- Watchpoints (`w` command)
- Actions (`a` command)
- Conditional breakpoints (`b line condition`)
- Custom debugger modules (`-d:Module`)
- Restart (`R` command)
- History and command editing
- Lexical variable inspection (expressions evaluate in package scope)

## See Also

- [CLI Options](cli-options.md) - All command-line options
- [perldebug](https://perldoc.perl.org/perldebug) - Perl's debugger documentation
