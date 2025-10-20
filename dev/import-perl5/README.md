# Perl5 Import System

This directory contains tools for importing Perl modules and test files from standard Perl5 into PerlOnJava.

## Overview

The import system consists of:
- **`config.yaml`**: Declarative configuration listing what to import
- **`sync.pl`**: Script that performs the import and applies patches
- **`patches/`**: Directory containing patch files for imported files

## Quick Start

### Initial Setup

First, clone the Perl5 repository:

```bash
cd /path/to/PerlOnJava
rm -rf perl5  # if it exists
git clone https://github.com/Perl/perl5.git
```

### Importing Files

After cloning perl5, run the import script to sync all files:

```bash
perl dev/import-perl5/sync.pl
```

The script will:
1. Read `config.yaml`
2. Copy directories (using rsync) and individual files to their target locations
3. Apply patches if specified
4. Report success or errors

This replaces the manual workflow of:
```bash
rsync -a perl5/t/ t/
git checkout t
```

### Adding a New Import

1. **Add an entry to `config.yaml`:**

```yaml
imports:
  - source: perl5/lib/YourModule.pm
    target: src/main/perl/lib/YourModule.pm
    patch: null  # or YourModule.pm.patch if needed
    description: "Brief description of the module"
```

2. **If a patch is needed:**

   a. Copy the original file to a temporary location:
   ```bash
   cp perl5/lib/YourModule.pm /tmp/YourModule.pm.orig
   ```

   b. Make your modifications to the target file:
   ```bash
   # Edit the file at src/main/perl/lib/YourModule.pm
   ```

   c. Generate the patch:
   ```bash
   cd /path/to/PerlOnJava
   diff -u /tmp/YourModule.pm.orig src/main/perl/lib/YourModule.pm \
     > dev/import-perl5/patches/YourModule.pm.patch
   ```

   d. Update `config.yaml` to reference the patch:
   ```yaml
   patch: YourModule.pm.patch
   ```

3. **Run the sync script to verify:**
   ```bash
   perl dev/import-perl5/sync.pl
   ```

## Configuration Format

The `config.yaml` file uses a simple, easy-to-maintain format:

```yaml
imports:
  - source: perl5/lib/Module.pm      # Source path (relative to project root)
    target: src/main/perl/lib/Module.pm  # Target path (relative to project root)
    type: file                       # Optional: 'file' (default) or 'directory'
    patch: Module.pm.patch           # Optional: patch file name
```

### Fields

- **`source`** (required): Path to the source file/directory in the perl5 directory, relative to project root
- **`target`** (required): Path where the file/directory should be copied, relative to project root
- **`type`** (optional): Either `file` (default) or `directory` for bulk directory copies using rsync
- **`patch`** (optional): Name of patch file in `patches/` directory (applied after copying)

## Common Use Cases

### Importing a Single File Without Modifications

```yaml
- source: perl5/lib/SomeModule.pm
  target: src/main/perl/lib/SomeModule.pm
```

### Importing a Single File With Patches

```yaml
- source: perl5/lib/SomeModule.pm
  target: src/main/perl/lib/SomeModule.pm
  patch: SomeModule.pm.patch
```

### Bulk Importing a Directory

```yaml
- source: perl5/t
  target: t
  type: directory
```

This uses `rsync -a` to efficiently copy the entire directory tree.

### Patching Files After Bulk Import

To apply patches to specific files after a bulk import, add them after the directory import:

```yaml
- source: perl5/t
  target: t
  type: directory

- source: perl5/t/test.pl
  target: t/test.pl
  patch: test.pl.patch
```

The patched version will override the file copied by the directory import.

## Creating Patches

### Method 1: Using diff

```bash
# 1. Start with the original file
cp perl5/lib/Module.pm /tmp/Module.pm.orig

# 2. Make your changes to the target location
vi src/main/perl/lib/Module.pm

# 3. Generate the patch
diff -u /tmp/Module.pm.orig src/main/perl/lib/Module.pm \
  > dev/import-perl5/patches/Module.pm.patch
```

### Method 2: Using git

If the file is already in git:

```bash
# 1. Make your changes
vi src/main/perl/lib/Module.pm

# 2. Generate patch from git diff
git diff src/main/perl/lib/Module.pm > dev/import-perl5/patches/Module.pm.patch

# 3. Reset the file (patch will be applied by sync.pl)
git checkout src/main/perl/lib/Module.pm
```

## Directory Structure

```
dev/import-perl5/
├── config.yaml          # Configuration file
├── sync.pl             # Import script
├── patches/            # Patch files directory
│   ├── test.pl.patch
│   └── Module.pm.patch
└── README.md           # This file
```

## Examples

The repository includes these example imports:

1. **`Benchmark.pm`**: Core benchmarking module imported without modifications
2. **`test.pl`**: Test utilities imported with compatibility patches

See `config.yaml` for the full configuration.

## Troubleshooting

### Patch Fails to Apply

If a patch fails to apply, it usually means the source file has changed:

1. Check if the perl5 source has been updated
2. Regenerate the patch with the new source file
3. Update `config.yaml` if needed

### Source File Not Found

Ensure the `source` path in `config.yaml` is correct and relative to the project root.

### Permission Issues

Make sure the script is executable:
```bash
chmod +x dev/import-perl5/sync.pl
```

## Maintenance

### Updating Imported Files

When you want to update to a newer version of a Perl5 file:

1. Update the file in `perl5/` directory
2. Run `sync.pl` to copy and patch
3. Test to ensure compatibility
4. If new issues arise, update the patch file

### Tracking Changes

You can track what's been imported and patched by:
- Reviewing `config.yaml`
- Checking the `patches/` directory
- Running `sync.pl` with a dry-run option (future enhancement)

## Future Enhancements

Potential improvements to consider:
- Dry-run mode (`--dry-run`)
- Verbose mode (`--verbose`)
- Individual file sync (`--file=Module.pm`)
- Automatic patch generation
- Verification/checksum tracking
- Integration with build system

## Contributing

When adding new imports:
1. Keep `config.yaml` organized (group by type: modules, tests, etc.)
2. Add descriptive comments
3. Document why patches are needed
4. Test the import process
5. Commit both the config changes and patch files

## Questions?

For questions or issues with the import system, refer to:
- Project documentation in `docs/`
- Main README at project root
- Development notes in `dev/README.md`

