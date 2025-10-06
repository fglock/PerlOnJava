#!/bin/bash
# Safe Analysis Setup Script
# This ensures a clean, safe environment for PerlOnJava test analysis

set -e  # Exit on any error

echo "========================================="
echo "PerlOnJava Safe Analysis Environment Setup"
echo "========================================="

# Step 1: Kill old processes
echo -e "\n[1/7] Killing old Java processes..."
pkill -f "java.*org.perlonjava" 2>/dev/null || true
ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || true
sleep 1
if ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep > /dev/null 2>&1; then
    echo "⚠️  Warning: Some processes may still be running"
else
    echo "✅ All old processes killed"
fi

# Step 2: Check git status
echo -e "\n[2/7] Checking git status..."
UNTRACKED=$(git status --porcelain | grep -E "^\?\?" | grep -E "test_.*\.pl|debug_.*\.pl" || true)
if [ ! -z "$UNTRACKED" ]; then
    echo "⚠️  Warning: Test files found that should not be committed:"
    echo "$UNTRACKED"
    read -p "Remove these files? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -f test_*.pl debug_*.pl
        echo "✅ Test files removed"
    fi
else
    echo "✅ No test files in working directory"
fi

# Step 3: Clean build artifacts
echo -e "\n[3/7] Cleaning build artifacts..."
./gradlew clean > /dev/null 2>&1
rm -rf logs/*.log 2>/dev/null || true
echo "✅ Build cleaned"

# Step 4: Check disk space
echo -e "\n[4/7] Checking resources..."
DISK_USAGE=$(df -h . | awk 'NR==2 {print $5}' | sed 's/%//')
if [ "$DISK_USAGE" -gt 90 ]; then
    echo "⚠️  Warning: Disk usage is high ($DISK_USAGE%)"
    echo "   Consider running: rm -rf ~/.gradle/caches/"
else
    echo "✅ Disk space OK ($DISK_USAGE% used)"
fi

# Step 5: Build
echo -e "\n[5/7] Building PerlOnJava..."
if ./gradlew shadowJar > /dev/null 2>&1; then
    echo "✅ Build successful"
else
    echo "❌ Build failed!"
    exit 1
fi

# Step 6: Verify build
echo -e "\n[6/7] Verifying build..."
if echo 'print "OK\n"' | ./jperl 2>/dev/null | grep -q "OK"; then
    echo "✅ jperl executable works"
else
    echo "❌ jperl not working properly!"
    exit 1
fi

# Step 7: Create logs directory
echo -e "\n[7/7] Setting up workspace..."
mkdir -p logs
echo "✅ Logs directory ready"

# Create verification file
echo "$(date +%Y-%m-%d_%H:%M:%S)" > .perlonjava_env_ready
echo "Environment verified and ready" >> .perlonjava_env_ready

# Final status
echo -e "\n========================================="
echo "✅ Environment is READY for analysis!"
echo "========================================="
echo
echo "Quick commands:"
echo "  Find blocked tests:  perl dev/tools/perl_test_runner.pl t 2>&1 | grep -A 5 'incomplete'"
echo "  Test a file:        ./jperl t/op/test.t"
echo "  Check git status:    git status --short"
echo "  Run tests:          ./gradlew test"
echo
echo "Remember:"
echo "  • NEVER use 'git add .' or 'git add -A'"
echo "  • Always test before committing"
echo "  • Clean up test files after debugging"
echo
echo "⚠️  This setup is valid for THIS SESSION ONLY"
echo "    Run this script again after:"
echo "    - System restart"
echo "    - Opening new terminal"
echo "    - Git pull/merge"
echo "    - Any errors or crashes"
