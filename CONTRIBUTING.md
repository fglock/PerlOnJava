# Contributing to PerlOnJava

We welcome contributions! This guide will help you get started.

## Ways to Contribute

- **Report bugs** - Open issues on GitHub
- **Fix bugs** - Submit pull requests
- **Add features** - Implement missing Perl features
- **Improve docs** - Fix typos, add examples, clarify explanations
- **Port modules** - Help port CPAN modules to PerlOnJava
- **Write tests** - Add test coverage

## Quick Start for Contributors

### 1. Set Up Development Environment

```bash
# Clone repository
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava

# Build and run tests
make        # Build + fast unit tests
make dev    # Force clean rebuild

# Run comprehensive tests
make test-all
```

### 2. Development Workflow

**Before making changes:**
1. Create a feature branch: `git checkout -b feature-name`
2. Read the [Architecture Guide](docs/reference/architecture.md)
3. Check [Roadmap](docs/about/roadmap.md) for planned features

**While coding:**
1. Follow existing code style
2. Run `make` frequently to ensure tests pass
3. Add tests for new features in `src/test/resources/unit/`

**Before committing:**
1. Ensure `make` passes (fast unit tests)
2. Run `make test-all` for comprehensive testing
3. Write clear commit messages

### 3. Submit Pull Request

1. Push your branch: `git push origin feature-name`
2. Open a PR on GitHub
3. Describe what your PR does and why
4. Link related issues

**The #1 priority is keeping `make` working.** All unit tests must pass.

## Code Organization

### Source Code Structure

```
src/main/java/org/perlonjava/
├── astnode/         # AST node representations
├── parser/          # Parser implementation
├── lexer/           # Tokenization
├── codegen/         # Bytecode generation and class creation
├── astvisitor/      # AST traversal (EmitterVisitor, PrinterVisitor)
├── runtime/         # Runtime implementations (RuntimeScalar, RuntimeArray, etc.)
├── operators/       # Operator implementations
├── regex/           # Regex engine integration
├── perlmodule/      # Java implementations of Perl modules
└── scriptengine/    # JSR 223 scripting API

src/main/perl/lib/   # Perl module implementations
src/test/resources/  # Test files
```

### Key Components

- **Lexer** (`lexer/Lexer.java`) - Tokenizes Perl source
- **Parser** (`parser/Parser.java`) - Builds AST from tokens
- **EmitterVisitor** (`astvisitor/`) - Traverses AST and coordinates bytecode generation
- **Emit classes** (`codegen/`) - Generate JVM bytecode using ASM library
- **Runtime** (`runtime/`) - RuntimeScalar, RuntimeArray, RuntimeHash, RuntimeCode
- **Operators** (`operators/`) - Perl operator implementations

### Test Organization

```
src/test/resources/
└── unit/            # Fast unit tests (run in seconds)

perl5_t/ (at project root)
├── t/               # Perl 5 core test suite
└── [Module]/        # Perl 5 module tests
```

## Development Guidelines

### Building and Testing

```bash
# Build with incremental compilation
make build

# Force clean rebuild (during active development)
make dev

# Run fast unit tests (default)
make test

# Run all tests including Perl 5 core tests
make test-all

# Run single test
./jperl path/to/test.t
```

### Adding Tests

1. Create `.t` file in `src/test/resources/unit/`
2. Use TAP format (Test::More compatible)
3. Test should complete in < 1 second
4. Run with `./jperl path/to/test.t`

Example test:
```perl
print "1..3\n";  # Plan
print "ok 1 - basic test\n";
my $x = 1 + 1;
print $x == 2 ? "ok 2\n" : "not ok 2\n";
print "ok 3 - done\n";
```

### Debugging

```bash
# Show debug information
./jperl --debug -E 'code'

# Check syntax only
./jperl -c script.pl

# Show disassembled bytecode
./jperl --disassemble script.pl

# Run lexer only
./jperl --tokenize -E 'code'

# Run parser only
./jperl --parse -E 'code'
```

## Documentation

### User Documentation

User-facing documentation is in `docs/`:
- **getting-started/** - Installation, quick start, Docker
- **guides/** - How-to guides (database, Java integration, modules)
- **reference/** - Technical reference (features, testing, architecture)
- **about/** - Project information (why, roadmap, support)

### Developer Documentation

Developer documentation is in `dev/`:
- **architecture/** - Internal architecture docs
- **implementation/** - Implementation notes (regex, tie, overload)
- **maintenance/** - Maintenance procedures

## Feature Development

### Before Adding a Feature

1. Check if it's in [Feature Matrix](docs/reference/feature-matrix.md)
2. Review [Roadmap](docs/about/roadmap.md)
3. Open an issue to discuss the approach
4. Read relevant design docs in `dev/`

### High-Risk Areas

When modifying these, test extensively:
- **String parsing** (`parser/StringDoubleQuoted.java`)
- **Lexer** (`lexer/Lexer.java`)
- **Unicode handling** - Surrogate pairs, identifier parsing
- **Control flow** - die/eval, loop control
- **Bytecode generation** (`codegen/`, `astvisitor/EmitterVisitor.java`)

### Adding Operators

1. Implement in `operators/` package
2. Add parser support in `parser/`
3. Add tests in `src/test/resources/unit/`
4. Update [Feature Matrix](docs/reference/feature-matrix.md)

### Porting Modules

See **[Module Porting Guide](docs/guides/module-porting.md)** for:
- How to port pure-Perl modules
- Implementing XS modules in Java
- Using XSLoader mechanism

## Community

- **GitHub Issues** - Bug reports and feature requests
- **Pull Requests** - Code contributions
- **Discussions** - Questions and design discussions

## Code of Conduct

- Be respectful and constructive
- Focus on technical merit
- Help newcomers
- Keep discussions on-topic

## Getting Help

- Read the [Architecture Guide](docs/reference/architecture.md)
- Check existing [Issues](https://github.com/fglock/PerlOnJava/issues)
- Ask in [Discussions](https://github.com/fglock/PerlOnJava/discussions)
- See [Support](docs/about/support.md) for more resources

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
