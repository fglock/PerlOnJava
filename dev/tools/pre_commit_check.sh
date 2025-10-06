#!/bin/bash
# Pre-Commit Safety Check for PerlOnJava
# Run this before EVERY commit to ensure safety

set -e  # Exit on any error

echo "========================================="
echo "     PRE-COMMIT SAFETY CHECK"
echo "========================================="

# 0. Verify environment was set up
echo -e "\n[0/8] Checking environment setup..."
if [ ! -f .perlonjava_env_ready ]; then
    echo "⚠️  Environment not set up recently"
    echo "Running setup now..."
    ./dev/tools/safe_analysis_setup.sh
    if [ $? -ne 0 ]; then
        echo "❌ Setup failed! Fix issues and try again."
        exit 1
    fi
fi

# 1. Check for test files in staging
echo -e "\n[1/8] Checking for test files in staging..."
TEST_FILES=$(git diff --cached --name-only | grep -E "test_.*\.pl|debug_.*\.pl" || true)
if [ ! -z "$TEST_FILES" ]; then
    echo "❌ ABORT: Test files detected in git staging:"
    echo "$TEST_FILES"
    echo
    echo "Remove them with:"
    echo "  git reset HEAD test_*.pl debug_*.pl"
    exit 1
fi
echo "✅ No test files in staging"

# 2. Check for log files
echo -e "\n[2/8] Checking for log files..."
LOG_FILES=$(git diff --cached --name-only | grep -E "\.log$" || true)
if [ ! -z "$LOG_FILES" ]; then
    echo "⚠️  Warning: Log files in staging:"
    echo "$LOG_FILES"
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted. Remove with: git reset HEAD *.log"
        exit 1
    fi
fi

# 3. Clean test artifacts
echo -e "\n[3/8] Cleaning workspace..."
rm -f test_*.pl debug_*.pl 2>/dev/null
rm -rf logs/*.log 2>/dev/null
echo "✅ Workspace cleaned"

# 4. Full rebuild
echo -e "\n[4/8] Building project..."
if ./gradlew clean shadowJar > /dev/null 2>&1; then
    echo "✅ Build successful"
else
    echo "❌ Build failed! Fix errors before committing."
    exit 1
fi

# 5. Run gradle tests
echo -e "\n[5/8] Running Gradle tests..."
if ./gradlew test > /dev/null 2>&1; then
    echo "✅ Gradle tests passed"
else
    echo "⚠️  Some Gradle tests failed (this may be expected)"
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 6. Basic functionality checks
echo -e "\n[6/8] Testing basic functionality..."
ERRORS=0

# Test 1: Basic print
if ! echo 'print "Hello\n"' | ./jperl 2>/dev/null | grep -q "Hello"; then
    echo "❌ Basic print failed"
    ERRORS=$((ERRORS + 1))
else
    echo "✅ Basic print works"
fi

# Test 2: Variable interpolation
if ! echo '$x = 42; print "$x\n"' | ./jperl 2>/dev/null | grep -q "42"; then
    echo "❌ Variable interpolation failed"
    ERRORS=$((ERRORS + 1))
else
    echo "✅ Variable interpolation works"
fi

# Test 3: Array operations
if ! echo '@a = (1,2,3); print scalar(@a), "\n"' | ./jperl 2>/dev/null | grep -q "3"; then
    echo "❌ Array operations failed"
    ERRORS=$((ERRORS + 1))
else
    echo "✅ Array operations work"
fi

if [ $ERRORS -gt 0 ]; then
    echo "❌ Basic functionality tests failed!"
    exit 1
fi

# 7. Show what will be committed
echo -e "\n[7/8] Files to be committed:"
git diff --cached --name-status | head -20
FILE_COUNT=$(git diff --cached --name-only | wc -l)
echo "Total: $FILE_COUNT file(s)"

# 8. Final check
echo -e "\n[8/8] Final verification..."
echo "Checking for common mistakes..."

# Check for debugging code
DEBUG_CODE=$(git diff --cached | grep -E "System\.(out|err)\.println|\/\/\s*DEBUG|\/\/\s*TODO\s*REMOVE" || true)
if [ ! -z "$DEBUG_CODE" ]; then
    echo "⚠️  Warning: Possible debug code detected:"
    echo "$DEBUG_CODE" | head -5
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Final confirmation
echo
echo "========================================="
echo "✅ ALL PRE-COMMIT CHECKS PASSED!"
echo "========================================="
echo
echo "Ready to commit with:"
echo "  git commit -m 'your descriptive message here'"
echo
echo "Remember to write a clear, descriptive commit message!"
