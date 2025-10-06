#!/bin/bash
# Install Git Hooks for PerlOnJava Safety

echo "Installing PerlOnJava Git safety hooks..."

# Create hooks directory if it doesn't exist
mkdir -p .git/hooks

# Create pre-commit hook
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
# PerlOnJava Pre-Commit Hook
# Prevents accidentally committing test files

# Check for test files
TEST_FILES=$(git diff --cached --name-only | grep -E "test_.*\.pl|debug_.*\.pl" || true)
if [ ! -z "$TEST_FILES" ]; then
    echo "❌ ERROR: Attempting to commit test/debug files!"
    echo "Files detected:"
    echo "$TEST_FILES"
    echo
    echo "Remove them with:"
    echo "  git reset HEAD test_*.pl debug_*.pl"
    echo
    echo "If you REALLY need to commit these (you probably don't), use:"
    echo "  git commit --no-verify"
    exit 1
fi

# Warn about log files
LOG_FILES=$(git diff --cached --name-only | grep -E "\.log$" || true)
if [ ! -z "$LOG_FILES" ]; then
    echo "⚠️  WARNING: Log files detected in commit"
    echo "$LOG_FILES"
    echo "Consider removing with: git reset HEAD *.log"
    echo "(Press Ctrl+C to abort, or wait 3 seconds to continue)"
    sleep 3
fi

# Check for common debug code
if git diff --cached | grep -q "System\.err\.println\|DEBUG.*=.*true\|\/\/\s*TEMP\|\/\/\s*HACK"; then
    echo "⚠️  WARNING: Possible debug code detected"
    echo "Check your changes carefully!"
    echo "(Press Ctrl+C to abort, or wait 3 seconds to continue)"
    sleep 3
fi

exit 0
EOF

# Make hook executable
chmod +x .git/hooks/pre-commit

echo "✅ Git pre-commit hook installed successfully!"
echo
echo "The hook will:"
echo "  • Block commits containing test_*.pl or debug_*.pl files"
echo "  • Warn about log files"
echo "  • Warn about debug code patterns"
echo
echo "To bypass the hook in emergency (NOT RECOMMENDED):"
echo "  git commit --no-verify"
echo
echo "To uninstall the hook:"
echo "  rm .git/hooks/pre-commit"
