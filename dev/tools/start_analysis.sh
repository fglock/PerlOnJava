#!/bin/bash
# PerlOnJava Test Analysis Gateway
# This ensures proper workflow is followed

set -e

echo "========================================="
echo "   PerlOnJava Test Analysis Gateway"
echo "========================================="
echo

# Step 1: Check for environment setup
if [ ! -f .perlonjava_env_ready ]; then
    echo "❌ Environment not set up!"
    echo
    echo "Setting up environment now..."
    echo "----------------------------------------"
    ./dev/tools/safe_analysis_setup.sh
    if [ $? -ne 0 ]; then
        echo "❌ Setup failed! Please fix issues and try again."
        exit 1
    fi
else
    # Check if setup is stale (>4 hours)
    if [ $(find . -name ".perlonjava_env_ready" -mmin +240 | wc -l) -gt 0 ]; then
        echo "⚠️  Setup is stale (>4 hours old)"
        echo "Running fresh setup..."
        echo "----------------------------------------"
        ./dev/tools/safe_analysis_setup.sh
        if [ $? -ne 0 ]; then
            echo "❌ Setup failed! Please fix issues and try again."
            exit 1
        fi
    else
        SETUP_TIME=$(head -1 .perlonjava_env_ready)
        echo "✅ Environment ready (setup at $SETUP_TIME)"
    fi
fi

echo
echo "========================================="
echo "        ANALYSIS QUICK MENU"
echo "========================================="
echo
echo "What would you like to do?"
echo
echo "  1) Find blocked/incomplete tests (highest ROI)"
echo "  2) Analyze test with 70-95% pass rate"
echo "  3) Run specific test file"
echo "  4) Check current test statistics"
echo "  5) Create minimal test case"
echo "  6) Check git status"
echo "  7) Run pre-commit check"
echo "  8) Exit"
echo
read -p "Enter choice [1-8]: " choice

case $choice in
    1)
        echo -e "\n📊 Finding blocked tests..."
        perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/blocked_$(date +%Y%m%d_%H%M%S).log | grep -A 10 "incomplete test files"
        echo -e "\nFull results saved to logs/"
        ;;
    2)
        echo -e "\n📊 Finding tests with 70-95% pass rate..."
        if [ -f out.json ]; then
            jq -r '.results | to_entries[] | select(.value.ok_count > 50 and .value.not_ok_count > 15 and .value.not_ok_count < 100) | "\(.value.not_ok_count) failures / \(.value.ok_count) passing (\(.value.ok_count * 100 / .value.total_tests | floor)%) - \(.key)"' out.json | sort -rn | head -20
        else
            echo "out.json not found. Run: perl dev/tools/perl_test_runner.pl t --json out.json"
        fi
        ;;
    3)
        read -p "Enter test file path (e.g., t/op/pack.t): " testfile
        if [ -f "$testfile" ]; then
            echo -e "\n🧪 Running $testfile..."
            ./jperl "$testfile" 2>&1 | tee logs/test_$(basename $testfile)_$(date +%Y%m%d_%H%M%S).log
        else
            echo "❌ File not found: $testfile"
        fi
        ;;
    4)
        echo -e "\n📈 Test Statistics..."
        if [ -f out.json ]; then
            echo "Total tests:"
            jq '.total_ok + .total_not_ok' out.json
            echo "Passing:"
            jq '.total_ok' out.json
            echo "Failing:"
            jq '.total_not_ok' out.json
            echo "Pass rate:"
            jq '(.total_ok * 100 / (.total_ok + .total_not_ok)) | floor' out.json
            echo "%"
        else
            echo "out.json not found. Run: perl dev/tools/perl_test_runner.pl t --json out.json"
        fi
        ;;
    5)
        echo -e "\n✏️  Creating minimal test case..."
        TESTFILE="test_minimal_$(date +%Y%m%d_%H%M%S).pl"
        cat > $TESTFILE << 'EOF'
#!/usr/bin/perl
use strict;
use warnings;

# Minimal test case
# TODO: Add your test code here

my $result = "actual";
my $expected = "expected";

if ($result eq $expected) {
    print "PASS\n";
} else {
    print "FAIL: got '$result', expected '$expected'\n";
}
EOF
        chmod +x $TESTFILE
        echo "Created: $TESTFILE"
        echo "Edit it and run with: ./jperl $TESTFILE"
        ;;
    6)
        echo -e "\n📁 Git Status..."
        git status --short
        TEST_FILES=$(git status --porcelain | grep -E "test_.*\.pl|debug_.*\.pl" || true)
        if [ ! -z "$TEST_FILES" ]; then
            echo
            echo "⚠️  WARNING: Test files detected!"
            echo "Clean them with: rm -f test_*.pl debug_*.pl"
        fi
        ;;
    7)
        echo -e "\n✅ Running pre-commit check..."
        ./dev/tools/pre_commit_check.sh
        ;;
    8)
        echo -e "\n👋 Exiting. Remember to:"
        echo "  • Clean up test files: rm -f test_*.pl debug_*.pl"
        echo "  • Run pre-commit check before committing"
        echo "  • Never use 'git add .' or 'git add -A'"
        exit 0
        ;;
    *)
        echo "Invalid choice"
        ;;
esac

echo
echo "----------------------------------------"
read -p "Press Enter to return to menu, or Ctrl+C to exit..."
exec "$0"
