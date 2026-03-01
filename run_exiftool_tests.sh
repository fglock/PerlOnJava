#!/bin/bash
cd /Users/fglock/projects/PerlOnJava2/Image-ExifTool-13.44
PASS=0
FAIL=0
PASS_LIST=""
FAIL_LIST=""
for t in t/*.t; do
    name=$(basename "$t" .t)
    output=$(timeout 60 java -jar ../target/perlonjava-3.0.0.jar -Ilib "$t" 2>&1)
    exit_code=$?
    # Check for "not ok" or non-zero exit
    if echo "$output" | grep -q "^not ok"; then
        FAIL=$((FAIL + 1))
        FAIL_LIST="$FAIL_LIST $name"
    elif [ $exit_code -ne 0 ]; then
        FAIL=$((FAIL + 1))
        FAIL_LIST="$FAIL_LIST $name"
    else
        PASS=$((PASS + 1))
        PASS_LIST="$PASS_LIST $name"
    fi
    echo "$name: exit=$exit_code"
done
echo ""
echo "PASS: $PASS"
echo "FAIL: $FAIL"
echo "Failing:$FAIL_LIST"
