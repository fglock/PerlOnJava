# CLI Options Reference

Command-line options for the `jperl` command.

## Synopsis

```bash
jperl [options] [program | -e 'command'] [arguments]
```

## Common Options

### Basic Usage

- **`-e 'command'`** - Execute command
  ```bash
  ./jperl -e 'print "Hello\n"'
  ```

- **`-E 'command'`** - Execute command with all features enabled
  ```bash
  ./jperl -E 'say "Hello"'  # say requires -E
  ```

- **`-c`** - Check syntax only (no execution)
  ```bash
  ./jperl -c script.pl
  ```

- **`-v`** - Print version
  ```bash
  ./jperl -v
  ```

- **`-h`** or **`-?`** - Show help
  ```bash
  ./jperl -h
  ```

### Module and Library

- **`-I dir`** - Add directory to `@INC` (module search path)
  ```bash
  ./jperl -I/path/to/lib script.pl
  ```

- **`-M module`** - Load module (like `use module`)
  ```bash
  ./jperl -MJSON -E 'say encode_json({a => 1})'
  ```

- **`-m module`** - Load module (like `use module ()`)
  ```bash
  ./jperl -mJSON script.pl
  ```

### Input Processing

- **`-n`** - Process input line-by-line (implicit loop, no print)
  ```bash
  echo -e "foo\nbar" | ./jperl -ne 'print if /foo/'
  ```

- **`-p`** - Like `-n` but print `$_` after each iteration
  ```bash
  echo -e "foo\nbar" | ./jperl -pe 's/foo/baz/'
  ```

- **`-a`** - Autosplit mode (splits `$_` into `@F`)
  ```bash
  echo "a b c" | ./jperl -ane 'print "$F[1]\n"'  # Prints: b
  ```

- **`-F pattern`** - Split pattern for `-a` (default: whitespace)
  ```bash
  echo "a:b:c" | ./jperl -F: -ane 'print "$F[1]\n"'  # Prints: b
  ```

- **`-0[octal]`** - Input record separator (default: newline)
  ```bash
  # Read null-terminated records
  ./jperl -0 -ne 'print' file.txt

  # Read entire file as one record
  ./jperl -0777 -ne 'print length' file.txt
  ```

- **`-l[octal]`** - Enable line ending processing
  ```bash
  # Auto-chomp and add newline to print
  ./jperl -lne 'print uc' file.txt
  ```

- **`-i[extension]`** - Edit files in-place
  ```bash
  # In-place edit with backup
  ./jperl -i.bak -pe 's/foo/bar/g' file.txt

  # In-place edit without backup
  ./jperl -i -pe 's/foo/bar/g' file.txt
  ```

### Script Arguments

- **`-s`** - Enable switch parsing for script
  ```bash
  # script.pl can access -foo as $foo
  ./jperl -s script.pl -foo=bar
  ```

### Warnings and Strictness

- **`-w`** - Enable warnings
  ```bash
  ./jperl -w script.pl
  ```

- **`-W`** - Enable all warnings
  ```bash
  ./jperl -W script.pl
  ```

- **`-X`** - Disable all warnings
  ```bash
  ./jperl -X script.pl
  ```

### Other Options

- **`-x[dir]`** - Extract script from message
  ```bash
  ./jperl -x script.txt
  ```

- **`-S`** - Search for script in PATH
  ```bash
  ./jperl -S script.pl
  ```

- **`-g`** - Read all input before executing (slurp mode for `-n`/`-p`)

## Debugging Options

- **`--debug`** - Show debug information
  ```bash
  ./jperl --debug -E 'say "test"'
  ```

- **`--disassemble`** - Show disassembled bytecode
  ```bash
  ./jperl --disassemble script.pl
  ```

- **`--tokenize`** - Show lexer output
  ```bash
  ./jperl --tokenize -E '$x = 1'
  ```

- **`--parse`** - Show parser output (AST)
  ```bash
  ./jperl --parse -E 'my $x = 1'
  ```

- **`-V`** - Print detailed configuration
  ```bash
  ./jperl -V
  ```

## Environment Variables

- **`PERL5LIB`** - Directories to add to `@INC`
  ```bash
  export PERL5LIB=/path/to/lib
  ./jperl script.pl
  ```

- **`PERL5OPT`** - Default options for perl
  ```bash
  export PERL5OPT='-Mwarnings -Mstrict'
  ./jperl script.pl
  ```

## Combining Options

Options can be combined for powerful one-liners:

```bash
# Replace text in all files
./jperl -i.bak -pe 's/old/new/g' *.txt

# Process CSV with auto-split
./jperl -F, -ane 'print "$F[2]\n"' data.csv

# Count lines matching pattern
./jperl -ne 'END {print $.} /pattern/ or next' file.txt

# Sum numbers in a column
./jperl -ane '$sum += $F[2]; END {print $sum}' data.txt
```

## Not Implemented

The following standard Perl options are not yet implemented:

- **`-T`** - Taint checks
- **`-t`** - Taint checks with warnings
- **`-u`** - Dumps core after compiling
- **`-U`** - Allows unsafe operations
- **`-d[t][:debugger]`** - Run under debugger
- **`-D[number/list]`** - Set debugging flags
- **`-C [number/list]`** - Control Unicode features

## See Also

- [Quick Start](../../QUICKSTART.md) - Getting started guide
- [One-liners](../getting-started/oneliners.md) - Practical examples
- [Feature Matrix](feature-matrix.md) - Supported features
