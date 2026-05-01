# Implementation Notes

Focused notes on implementing specific Perl features in PerlOnJava.

## Purpose

Each file in this directory is a **per-feature implementation reference** that
describes:

- What the feature does (brief Perl semantics summary)
- How it is (or will be) implemented in PerlOnJava
- Known gaps, edge cases, or workarounds
- Test files that exercise the feature

These are more narrowly scoped than the broad design documents in `dev/design/`
and are intended as quick references when working on a specific area.

## Contents

| File | Feature |
|------|---------|
| [alarm.md](alarm.md) | `alarm()` / `SIGALRM` implementation |
| [overload.md](overload.md) | Operator overloading (`use overload`) |
| [pack-unpack.md](pack-unpack.md) | `pack`/`unpack` implementation and format coverage |
| [regex.md](regex.md) | Regex engine integration and Perl-specific extensions |
| [signal-handling.md](signal-handling.md) | `%SIG` handler dispatch and signal safety |
| [tie.md](tie.md) | `tie`/`untie`/`tied` implementation |

## See Also

- `dev/architecture/` — Higher-level architecture docs for the compiler and runtime
- `dev/design/` — Broader design documents and feature specifications
