`t/`

This directory is a placeholder for the original Perl test suite.

## Setup

To import Perl test files and verify their behavior under PerlOnJava:

1. Clone the Perl5 repository (if not already done):
   ```bash
   rm -rf perl5  # if it exists
   git clone https://github.com/Perl/perl5.git
   ```

2. Run the import script to copy tests and apply patches:
   ```bash
   perl dev/import-perl5/sync.pl
   ```

This will copy all files from `perl5/t/` to `t/` and apply any necessary patches for PerlOnJava compatibility.

See `dev/import-perl5/README.md` for more details on the import system and how to add patches.

## Running Tests

To run the tests, use the following commands:

    perl dev/tools/perl_test_runner.pl --output out.json t

