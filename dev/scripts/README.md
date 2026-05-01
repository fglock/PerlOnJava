# Scripts

One-off development shell scripts for PerlOnJava.

## Purpose

Short shell scripts that automate ad-hoc developer tasks — things that are
too simple to warrant a full tool in `dev/tools/` but useful enough to save
rather than retype.

## Contents

| File | Description |
|------|-------------|
| `dbic_fast_check.sh` | Quick sanity check that DBIx::Class loads and runs a minimal query under `jperl`, without running the full test suite |

## Adding New Scripts

- Use `#!/usr/bin/env bash` or `#!/usr/bin/env perl` as the shebang.
- Add a brief comment at the top explaining what the script does and when to use it.
- Add a row to the table above.
- For scripts that could hang (e.g. anything launching `jperl`), include
  a `timeout` wrapper — see `AGENTS.md` for details.

## See Also

- `dev/tools/` — More substantial, documented development utilities
- `AGENTS.md` — Mandatory `timeout` rules when running `jperl`/`jcpan`
