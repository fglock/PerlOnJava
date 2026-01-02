# Dependency Management Guide

This project uses **Gradle Version Catalogs** for centralized dependency management. All dependencies are defined in `gradle/libs.versions.toml`.

## Benefits of Version Catalogs

- **Centralized Management**: All versions in one place (`gradle/libs.versions.toml`)
- **Type-Safe**: IDE autocomplete for dependencies
- **Configuration Cache Compatible**: Works with Gradle's configuration cache for faster builds
- **Easy Updates**: Simple commands to check and update dependencies

## Available Tasks

### Check and Update Dependencies

```bash
# Check for updates and automatically update the version catalog
./gradlew versionCatalogUpdate
```

This will:
- Check all dependencies for newer versions
- Update `gradle/libs.versions.toml` with the latest versions
- Maintain proper formatting

### Interactive Mode

```bash
# Review each update interactively before applying
./gradlew versionCatalogUpdate --interactive
```

### Format Version Catalog

```bash
# Format the libs.versions.toml file (fully configuration cache compatible)
./gradlew versionCatalogFormat
```

## Version Catalog Location

All dependencies are defined in: `gradle/libs.versions.toml`

Structure:
- `[versions]` - Version numbers
- `[libraries]` - Library dependencies
- `[plugins]` - Gradle plugins

## Adding New Dependencies

1. Add the version to `[versions]` section
2. Add the library reference to `[libraries]` section
3. Use in `build.gradle` with: `implementation libs.library.name`

Example:
```toml
[versions]
gson = "2.10.1"

[libraries]
gson = { module = "com.google.code.gson:gson", version.ref = "gson" }
```

Then in `build.gradle`:
```groovy
dependencies {
    implementation libs.gson
}
```

## Important Notes

- **Configuration Cache**: Fully compatible! No need to disable features
- **Review Changes**: Always review updates before committing
- **Test After Updates**: Run `./gradlew clean build test` after updating
- **Pin Versions**: Add `# @pin` comment in TOML to prevent auto-updates

## Migration Complete

✅ Migrated from `ben-manes.versions` + `use-latest-versions` to version catalogs
✅ Configuration cache now works with all tasks
✅ Cleaner, more maintainable dependency management
