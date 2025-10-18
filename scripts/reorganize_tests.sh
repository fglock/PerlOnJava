#!/bin/bash
# Reorganize test directory structure to separate PerlOnJava unit tests
# from standard Perl module tests

set -e

echo "=========================================="
echo "Test Directory Reorganization Script"
echo "=========================================="
echo ""

# Check we're in the right directory
if [ ! -d "src/test/resources" ]; then
    echo "Error: Must run from PerlOnJava root directory"
    exit 1
fi

cd src/test/resources

# Count existing tests
BEFORE_COUNT=$(find . -maxdepth 2 -name "*.t" -type f | wc -l)
echo "Tests found before migration: $BEFORE_COUNT"
echo ""

# Create new directories
echo "Creating new directory structure..."
mkdir -p unit
mkdir -p lib
mkdir -p ext
mkdir -p dist
mkdir -p cpan
echo "  Created: unit/, lib/, ext/, dist/, cpan/"
echo ""

# Move README.md temporarily
if [ -f README.md ]; then
    mv README.md README.md.backup
    echo "Backed up README.md"
fi

# Move all .t files to unit/
echo "Moving test files to unit/..."
MOVED_FILES=0
for file in *.t; do
    if [ -f "$file" ]; then
        git mv "$file" unit/ 2>/dev/null || mv "$file" unit/
        echo "  ✓ $file"
        MOVED_FILES=$((MOVED_FILES + 1))
    fi
done
echo "Moved $MOVED_FILES test files"
echo ""

# Move subdirectories to unit/ (except the new ones)
echo "Moving test subdirectories to unit/..."
MOVED_DIRS=0
for dir in */; do
    dirname="${dir%/}"
    case "$dirname" in
        unit|lib|ext|dist|cpan)
            echo "  ⊘ Skipping $dirname (new directory)"
            ;;
        *)
            if [ -d "$dirname" ]; then
                git mv "$dirname" unit/ 2>/dev/null || mv "$dirname" unit/
                echo "  ✓ $dirname/"
                MOVED_DIRS=$((MOVED_DIRS + 1))
            fi
            ;;
    esac
done
echo "Moved $MOVED_DIRS subdirectories"
echo ""

# Restore README.md
if [ -f README.md.backup ]; then
    mv README.md.backup README.md
    echo "Restored README.md"
fi

# Count tests after migration
AFTER_COUNT=$(find unit -name "*.t" -type f | wc -l)
echo ""
echo "=========================================="
echo "Migration Summary"
echo "=========================================="
echo "Tests before: $BEFORE_COUNT"
echo "Tests after:  $AFTER_COUNT"
echo ""

if [ "$BEFORE_COUNT" -eq "$AFTER_COUNT" ]; then
    echo "✓ SUCCESS: All tests accounted for"
else
    echo "✗ WARNING: Test count mismatch!"
    echo "  Please verify manually"
fi

echo ""
echo "Directory structure:"
ls -la | grep "^d" | awk '{print "  " $9}'

echo ""
echo "=========================================="
echo "Next Steps"
echo "=========================================="
echo "1. Review the changes:"
echo "   git status"
echo ""
echo "2. Update perl_test_runner.pl to scan unit/ directory"
echo ""
echo "3. Test that tests still run:"
echo "   ./perl_test_runner.pl"
echo ""
echo "4. Update README.md files"
echo ""
echo "5. Commit the changes:"
echo "   git add -A"
echo "   git commit -m 'Reorganize tests: move to unit/ subdirectory'"
echo ""
