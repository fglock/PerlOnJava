# Patches

Minimal patches applied to third-party CPAN module source files to make them
work correctly under PerlOnJava.

## Purpose

When a CPAN module requires only a small change to work on PerlOnJava (e.g.
working around an unimplemented feature or adjusting a test expectation), the
patch is stored here so it can be:

- Reapplied after a module version upgrade
- Reviewed when the underlying limitation is eventually fixed
- Referenced from the module's porting document in `dev/modules/`

Patches here are **not** distributed with PerlOnJava itself — they are applied
locally by the developer when testing or porting the module.

## Structure

```
patches/
└── cpan/
    └── <Module-Name-Version>/
        ├── README.md          # What the patch does and why
        └── *.patch            # Unified diff(s)
```

## Contents

| Module | Patch | Description |
|--------|-------|-------------|
| `DBIx-Class-0.082844` | `t-lib-DBICTest-Util-LeakTracer.pm.patch` | Adjust `LeakTracer` to avoid an unimplemented `Scalar::Util` feature; see `README.md` in that directory |

## Applying a Patch

```bash
# From the CPAN module's extracted source directory:
patch -p1 < /path/to/PerlOnJava4/dev/patches/cpan/<Module>/<file>.patch
```

## See Also

- `dev/modules/` — Porting guides for each CPAN module
- `dev/cpan-reports/` — Automated pass/fail tracking
