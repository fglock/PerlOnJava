# Similar Modules Analysis

This document lists Perl modules in `src/main/perl/lib` that have 95%+ similarity with their original sources in `perl5/`.

## Summary

Found **70 modules** that are essentially identical to their perl5/ sources and should be kept in sync.

**Status: ✅ COMPLETED** - All similar modules and their tests have been added to config.yaml and synced.

## Current Stats

- **76 imports** configured in config.yaml
- **23 test directories** in perl5_t/
- **827 test files** ready to run
- **All sources verified** present in perl5/

## Usage

### Add Individual Module

```bash
# Dry run (preview what would be added)
perl dev/import-perl5/add_module.pl Module::Name

# Apply (actually add to config.yaml)
perl dev/import-perl5/add_module.pl --apply Module::Name
```

### Add All Similar Modules

```bash
bash dev/import-perl5/add_similar_modules.sh
```

## Categories

### Core Library Modules (perl5/lib/) - 11 modules

- File/Basename.pm
- Tie/Array.pm
- Tie/Handle.pm
- Tie/Hash.pm
- Tie/Scalar.pm
- Unicode/UCD.pm
- _charnames.pm
- charnames.pm
- integer.pm
- locale.pm
- vmsish.pm

### CPAN Modules - 42 modules

**Digest Distribution:**
- Digest.pm
- Digest/base.pm
- Digest/file.pm

**Locale Distribution:**
- Locale/Maketext/Simple.pm

**Params Distribution:**
- Params/Check.pm

**Perl Distribution:**
- Perl/OSType.pm

**Pod-Checker Distribution:**
- Pod/Checker.pm

**Pod-Escapes Distribution:**
- Pod/Escapes.pm

**Pod-Simple Distribution:**
- Pod/Simple.pm
- Pod/Simple/BlackBox.pm
- Pod/Simple/Checker.pm
- Pod/Simple/Debug.pm
- Pod/Simple/DumpAsText.pm
- Pod/Simple/DumpAsXML.pm
- Pod/Simple/HTML.pm
- Pod/Simple/HTMLBatch.pm
- Pod/Simple/HTMLLegacy.pm
- Pod/Simple/JustPod.pm
- Pod/Simple/LinkSection.pm
- Pod/Simple/Methody.pm
- Pod/Simple/Progress.pm
- Pod/Simple/PullParser.pm
- Pod/Simple/PullParserEndToken.pm
- Pod/Simple/PullParserStartToken.pm
- Pod/Simple/PullParserTextToken.pm
- Pod/Simple/PullParserToken.pm
- Pod/Simple/RTF.pm
- Pod/Simple/Search.pm
- Pod/Simple/SimpleTree.pm
- Pod/Simple/Text.pm
- Pod/Simple/TextContent.pm
- Pod/Simple/TiedOutFH.pm
- Pod/Simple/Transcode.pm
- Pod/Simple/TranscodeDumb.pm
- Pod/Simple/TranscodeSmart.pm
- Pod/Simple/XHTML.pm
- Pod/Simple/XMLOutStream.pm

**Pod-Usage Distribution:**
- Pod/Usage.pm

**Text Distributions:**
- Text/ParseWords.pm
- Text/Tabs.pm
- Text/Wrap.pm

**podlators Distribution:**
- Pod/Man.pm
- Pod/ParseLink.pm
- Pod/Text.pm
- Pod/Text/Color.pm
- Pod/Text/Overstrike.pm
- Pod/Text/Termcap.pm

**Test Modules:**
- Test/Podlators.pm
- Test/RRA.pm
- Test/RRA/Config.pm
- Test/RRA/ModuleVersion.pm

**version Distribution:**
- version/regex.pm

### Distribution Modules (perl5/dist/) - 6 modules

- Attribute/Handlers.pm (Attribute-Handlers)
- Env.pm (Env)
- FindBin.pm (FindBin)
- Test.pm (Test)
- Unicode/Normalize.pm (Unicode-Normalize)
- if.pm (if)

### Extension Modules (perl5/ext/) - 1 module

- File/Find.pm (File-Find)

## Already Configured

The following modules were already in config.yaml:
- Benchmark.pm
- Pod/* (various Pod-Simple modules)
- Text/Tabs.pm
- Text/Wrap.pm

## Notes

- All Pod::Simple modules are already configured as directory imports
- All Test helper modules (Test::Podlators, Test::RRA) are already configured
- Most modules have 100% similarity after normalization
- Test files are automatically detected and suggested by add_module.pl
- The script prevents duplicate entries

## Recommendation

Run the batch script to add all similar modules at once:

```bash
bash dev/import-perl5/add_similar_modules.sh
perl dev/import-perl5/sync.pl
```

This will ensure these modules stay in sync with their upstream perl5/ sources.

