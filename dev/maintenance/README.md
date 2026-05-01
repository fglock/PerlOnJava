# Maintenance

Ongoing maintenance tracking for PerlOnJava.

## Purpose

This directory contains notes and guides for routine maintenance tasks that
do not belong in the main source tree — things like dependency management,
version-bump procedures, and periodic housekeeping checklists.

## Contents

| File | Description |
|------|-------------|
| [dependency-updates.md](dependency-updates.md) | How to check for and apply Gradle dependency updates using Version Catalogs |

## Common Tasks

### Update dependencies

```bash
# Check for newer versions and auto-update gradle/libs.versions.toml
./gradlew versionCatalogUpdate

# Review each update interactively
./gradlew versionCatalogUpdate --interactive
```

After updating, always run `make` to verify nothing broke.

### Verify build health

```bash
make           # Full build + unit tests
make test-bundled-modules   # Bundled CPAN module tests
```

## See Also

- `gradle/libs.versions.toml` — Canonical dependency version catalog
- `AGENTS.md` — Build and test workflow rules
