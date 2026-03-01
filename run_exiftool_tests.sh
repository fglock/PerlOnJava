#!/bin/bash
# Run all ExifTool test files with a 60-second timeout per test
JAR=target/perlonjava-3.0.0.jar
EXIFTOOL_DIR=Image-ExifTool-13.44
TIMEOUT=60

cd "$EXIFTOOL_DIR" || exit 1

for t in t/*.t; do
    name=$(basename "$t" .t)
    printf "%-20s " "$name"
    output=$(timeout $TIMEOUT java -jar "../$JAR" -Ilib "$t" 2>&1)
    exit_code=$?
    if [ $exit_code -eq 124 ]; then
        echo "TIMEOUT"
    else
        # Count ok/not ok lines
        total=$(echo "$output" | grep -cE '^(not )?ok ')
        pass=$(echo "$output" | grep -cE '^ok ')
        fail=$(echo "$output" | grep -cE '^not ok ')
        # Check for plan
        plan=$(echo "$output" | grep -oE '^1\.\.[0-9]+' | head -1)
        if [ -n "$plan" ]; then
            planned=${plan#1..}
        else
            planned="?"
        fi
        if [ $fail -gt 0 ] || [ $exit_code -ne 0 ]; then
            echo "FAIL (pass=$pass fail=$fail planned=$planned exit=$exit_code)"
        else
            echo "PASS (pass=$pass/$planned)"
        fi
    fi
done
