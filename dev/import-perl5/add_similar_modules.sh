#!/bin/bash
# Add all modules from src/main/perl that match perl5/ sources
# Generated from similarity analysis

set -e

cd "$(dirname "$0")/../.."

echo "Adding similar modules to sync configuration..."
echo "This will add modules that are 95%+ similar to their perl5/ sources"
echo ""

# Core library modules (perl5/lib)
perl dev/import-perl5/add_module.pl --apply File/Basename.pm || true
perl dev/import-perl5/add_module.pl --apply Tie/Array.pm || true
perl dev/import-perl5/add_module.pl --apply Tie/Handle.pm || true
perl dev/import-perl5/add_module.pl --apply Tie/Hash.pm || true
perl dev/import-perl5/add_module.pl --apply Tie/Scalar.pm || true
perl dev/import-perl5/add_module.pl --apply Unicode/UCD.pm || true
perl dev/import-perl5/add_module.pl --apply _charnames.pm || true
perl dev/import-perl5/add_module.pl --apply charnames.pm || true
perl dev/import-perl5/add_module.pl --apply integer.pm || true
perl dev/import-perl5/add_module.pl --apply locale.pm || true
perl dev/import-perl5/add_module.pl --apply vmsish.pm || true

# Digest modules (CPAN)
perl dev/import-perl5/add_module.pl --apply Digest.pm || true
perl dev/import-perl5/add_module.pl --apply Digest/base.pm || true
perl dev/import-perl5/add_module.pl --apply Digest/file.pm || true

# Locale (CPAN)
perl dev/import-perl5/add_module.pl --apply Locale/Maketext/Simple.pm || true

# Params (CPAN)
perl dev/import-perl5/add_module.pl --apply Params/Check.pm || true

# Perl (CPAN)
perl dev/import-perl5/add_module.pl --apply Perl/OSType.pm || true

# Text modules (CPAN)
perl dev/import-perl5/add_module.pl --apply Text/ParseWords.pm || true
perl dev/import-perl5/add_module.pl --apply Text/Tabs.pm || true
perl dev/import-perl5/add_module.pl --apply Text/Wrap.pm || true

# Version (CPAN)
perl dev/import-perl5/add_module.pl --apply version/regex.pm || true

# Distribution modules (perl5/dist)
perl dev/import-perl5/add_module.pl --apply Attribute/Handlers.pm || true
perl dev/import-perl5/add_module.pl --apply Env.pm || true
perl dev/import-perl5/add_module.pl --apply FindBin.pm || true
perl dev/import-perl5/add_module.pl --apply Test.pm || true
perl dev/import-perl5/add_module.pl --apply Unicode/Normalize.pm || true
perl dev/import-perl5/add_module.pl --apply if.pm || true

# Extension modules (perl5/ext)
perl dev/import-perl5/add_module.pl --apply File/Find.pm || true

echo ""
echo "Done! Check config.yaml for the new entries."
echo "Run: perl dev/import-perl5/sync.pl"

