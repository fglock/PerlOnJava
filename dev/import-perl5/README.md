# Perl5 Import Tools

This directory contains tools for importing and synchronizing Perl modules and tests from the perl5/ repository into PerlOnJava.

## Overview

The import system helps maintain modules that are (nearly) identical to their perl5/ counterparts, allowing easy updates when perl5 changes.

## Files

- **config.yaml** - Configuration file listing all imports
- **sync.pl** - Main synchronization script
- **add_module.pl** - Interactive tool to add new modules
- **add_similar_modules.sh** - Batch script to add all similar modules
- **patches/** - Directory containing patches for modified files
- **SIMILAR_MODULES.md** - Analysis of modules similar to perl5/ sources

## Quick Start

### Synchronize All Configured Imports

```bash
perl dev/import-perl5/sync.pl
```

This copies all files listed in config.yaml from perl5/ to their target locations and applies any patches.

### Add a New Module

```bash
# Preview what would be added (dry run - default)
perl dev/import-perl5/add_module.pl Text::Wrap

# Actually add to config.yaml
perl dev/import-perl5/add_module.pl --apply Text::Wrap

# Then sync
perl dev/import-perl5/sync.pl
```

### Add Multiple Similar Modules

```bash
# Add all modules that match their perl5/ sources
bash dev/import-perl5/add_similar_modules.sh
perl dev/import-perl5/sync.pl
```

## Tools

### sync.pl

Main synchronization script that imports files from perl5/ based on config.yaml.

**Features:**
- Copies individual files or entire directories
- Applies patches automatically
- Creates necessary directories
- Validates sources exist
- Reports success/failure summary

**Usage:**
```bash
perl dev/import-perl5/sync.pl
```

### add_module.pl

Interactive tool to add new modules to the sync configuration.

**Features:**
- Finds module in src/main/perl/lib
- Locates original source in perl5/
- Calculates similarity percentage
- Detects duplicates automatically
- Finds and suggests test files
- Categorizes source location
- Dry-run by default for safety

**Usage:**
```bash
# Dry run (preview)
perl dev/import-perl5/add_module.pl Module::Name
perl dev/import-perl5/add_module.pl File/Path.pm

# Apply changes
perl dev/import-perl5/add_module.pl --apply Module::Name

# Help
perl dev/import-perl5/add_module.pl --help
```

**Examples:**
```bash
perl dev/import-perl5/add_module.pl Digest::MD5
perl dev/import-perl5/add_module.pl File/Basename.pm
perl dev/import-perl5/add_module.pl --apply Text::Wrap
```

### add_similar_modules.sh

Batch script to add all modules identified as similar (95%+ match) to their perl5/ sources.

**Usage:**
```bash
bash dev/import-perl5/add_similar_modules.sh
```

See SIMILAR_MODULES.md for the complete list of modules this will add.

## Configuration Format

config.yaml uses a simple YAML structure:

```yaml
imports:
  # Individual file
  - source: perl5/lib/Module.pm
    target: src/main/perl/lib/Module.pm

  # File with patch
  - source: perl5/lib/Module.pm
    target: src/main/perl/lib/Module.pm
    patch: Module.pm.patch

  # Directory import
  - source: perl5/cpan/Some-Module/lib
    target: src/main/perl/lib
    type: directory

  # Test files
  - source: perl5/cpan/Some-Module/t
    target: perl5_t/Some-Module
    type: directory
```

## Patches

When a file needs modifications for PerlOnJava compatibility:

1. Make your changes to the target file
2. Create a patch:
   ```bash
   diff -u original modified > patches/filename.patch
   ```
3. Add patch reference in config.yaml:
   ```yaml
   - source: perl5/path/to/file
     target: target/path
     patch: filename.patch
   ```

## Workflow

### Adding a New Module from perl5/

1. Copy the module to `src/main/perl/lib/`:
   ```bash
   cp perl5/lib/Module.pm src/main/perl/lib/
   ```

2. Test it works in PerlOnJava

3. Add to sync configuration:
   ```bash
   perl dev/import-perl5/add_module.pl --apply Module.pm
   ```

4. Verify:
   ```bash
   perl dev/import-perl5/sync.pl
   ```

### Updating Modules from perl5/

When perl5/ is updated:

```bash
# Just run sync
perl dev/import-perl5/sync.pl

# This will update all configured modules
```

### Adding Tests

Tests go to `perl5_t/` directory:

```yaml
- source: perl5/lib/Module.t
  target: perl5_t/Module.t

- source: perl5/cpan/Some-Module/t
  target: perl5_t/Some-Module
  type: directory
```

The `add_module.pl` script automatically suggests test locations.

## Directory Structure

```
perl5/                    # Upstream perl5 repository
src/main/perl/lib/       # PerlOnJava modules
perl5_t/                 # Test files (external, not in git)
dev/import-perl5/
  ├── config.yaml        # Import configuration
  ├── sync.pl            # Sync script
  ├── add_module.pl      # Module addition tool
  ├── add_similar_modules.sh
  ├── patches/           # Patch files
  ├── README.md          # This file
  └── SIMILAR_MODULES.md # Analysis document
```

## Tips

1. **Always use add_module.pl** - It prevents duplicates and finds tests automatically

2. **Dry run first** - The default mode is `--dry-run`, so you can preview changes

3. **Check similarity** - If similarity is < 95%, the module may have significant changes

4. **Update regularly** - Run `sync.pl` after updating the perl5/ directory

5. **Keep patches minimal** - Try to minimize differences from upstream perl5/

6. **Test after sync** - Always test after synchronizing to catch any breaking changes

## Example Session

```bash
# Find similar modules
cd /Users/fglock/projects/PerlOnJava
perl dev/import-perl5/add_module.pl Text::Wrap

# Output shows 100% similarity
# Add it
perl dev/import-perl5/add_module.pl --apply Text::Wrap

# Sync to copy the file
perl dev/import-perl5/sync.pl

# Test it
./jperl -e 'use Text::Wrap; print "OK\n"'
```

## Troubleshooting

**"Module not found" error:**
- Make sure the module exists in `src/main/perl/lib/`
- Use the correct path format (Module::Name or File/Path.pm)

**"Module is already configured" message:**
- The module is already in config.yaml
- Just run `sync.pl` to update it

**"No good match found" error:**
- The module differs significantly from perl5/ version
- May need manual porting or custom implementation
- Consider not adding to sync.pl

**Patch fails to apply:**
- The source file changed in perl5/
- Need to regenerate the patch
- Or remove the patch if no longer needed

## See Also

- SIMILAR_MODULES.md - List of modules identified as similar to perl5/
- config.yaml - Current import configuration
- perl5_t/ - Test file directory structure
