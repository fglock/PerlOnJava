# PerlOnJava Development Workspace

This directory contains development resources, tools and experimental code for PerlOnJava that are not part of the production distribution.

## Directory Structure

- **bench/** - Performance benchmarks and comparison tests
    - Java benchmarks for numification, lookup operations
    - Perl benchmark scripts for various operations (regex, strings, typing)

- **design/** - Technical documentation and specifications
    - Implementation guides (debugger, threading, optimization)
    - Architecture decisions and planning documents
    - Feature specifications and roadmaps

- **examples/** - Sample code and usage demonstrations

  Mix of working examples and feature concepts for design exploration. Not all examples are guaranteed to run.

  - GUI applications (GuiHelloWorld.pl)
  - Network programming (GameServer.pl, Http.pl)
  - Threading examples (TestThread.pl)
  - Type system ideas (Types.pl, Types2.pl)
  - Advanced threading patterns (TestThreadEval.pl)
  - Core language extensions (core_global_override.pl)

- **interpreter/** - Interpreter mode development resources
    - Comprehensive documentation (SKILL.md, STATUS.md, TESTING.md, BYTECODE_DOCUMENTATION.md)
    - Architecture design documents (architecture/ subdirectory)
    - Test files for interpreter-specific features (tests/ subdirectory)
    - Performance: ~47M ops/sec (1.75x slower than compiler, within 2-5x target)
    - Register-based bytecode interpreter with tableswitch optimization
    - Variable sharing between interpreted and compiled code
    - Supports closures, bidirectional calling, and context detection

- **prompts/** - LLM prompts for development assistance
  - Code generation and conversion
  - Documentation improvements
  - Debugging and troubleshooting
  - Refactoring patterns

- **sandbox/** - Quick tests and proof of concepts
    - IO operations testing
    - Command line handling
    - Regular expression experiments
    - Compile-time optimizations
    - Lambda expression handling
    - Stack management prototypes

- **tools/** - Development utilities and helpers
    - Java implementation utilities
    - Vim configuration for development
    - Parser and lexer generation tools

## Contributing

When adding new code or tools:

1. Place it in the appropriate category directory
2. Include clear documentation and usage examples
3. Add relevant tests where applicable
4. Reference related design documents from the `design/` directory

## Development Guidelines

- Keep experimental code separate from production code
- Document design decisions in the `design/` directory
- Use the `bench/` directory for performance testing
- Check `examples/` for implementation patterns
- Store reusable LLM prompts in `prompts/` directory
