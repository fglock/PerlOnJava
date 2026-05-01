# Known Bugs

Minimal reproduction scripts for confirmed PerlOnJava bugs.

## Purpose

Each file here is a **minimal Perl script that demonstrates a known bug**.
Files are kept here (rather than deleted) so that:

- A future fix can be verified immediately by running the script
- The bug report context is preserved alongside the reproduction case
- Other contributors understand which areas need work

**Do not file a workaround here** — if you have a fix, implement it and add or
update a unit test under `src/test/resources/`. The files here are for bugs
that have not yet been fixed.

## Contents

| File | Bug description | Related doc |
|------|----------------|-------------|
| `local_list_assign_eval_string.pl` | `local (HASH_OR_ARRAY_ELEMENT) = value` inside an `eval STRING`-compiled sub is a no-op for the assignment (scope restoration still works). | `dev/modules/dbi_test_parity.md` |

## Running a Reproduction

```bash
# Run under PerlOnJava (should show wrong output)
./jperl dev/known-bugs/<file>.pl

# Run under system Perl (reference / expected output)
perl dev/known-bugs/<file>.pl
```

Always compare the two outputs — a fix is confirmed when they match.

## Adding a New Known Bug

1. Create a minimal `.pl` reproduction script that fails on `./jperl` but passes on `perl`.
2. Add a header comment explaining the bug and pointing to any related design doc.
3. Add a row to the table above.
4. Open or reference a GitHub issue if applicable.

## See Also

- `src/test/resources/` — Unit tests (add a test here when a bug is fixed)
- `dev/design/` — Detailed fix plans for some known bugs
