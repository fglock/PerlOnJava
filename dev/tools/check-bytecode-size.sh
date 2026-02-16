#!/bin/bash
# Check that interpreter methods stay under JIT compilation limit (~8000 bytes)
#
# The JVM refuses to JIT-compile methods larger than ~8000 bytes (controlled by
# -XX:DontCompileHugeMethods flag). When methods run in interpreted mode instead
# of JIT-compiled, performance degrades 5-10x.
#
# This script verifies that critical interpreter methods stay under the size limit.

set -e

MAX_SIZE=7500  # Safe limit with margin (actual JVM limit is ~8000)
FAILED=0

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_method() {
    local CLASS=$1
    local METHOD=$2
    local DISPLAY_NAME="${CLASS}.${METHOD}"

    # Get bytecode size using javap (include private methods)
    BYTECODE_FILE=$(mktemp)
    if ! javap -c -private -classpath build/classes/java/main "$CLASS" > "$BYTECODE_FILE" 2>/dev/null; then
        echo -e "${RED}ERROR: Could not find class $CLASS${NC}"
        echo "       Make sure to run 'make build' first"
        rm -f "$BYTECODE_FILE"
        return 1
    fi

    # Extract method bytecode and find last instruction offset
    # Look for the method, then find the last numbered instruction before the next method/end
    METHOD_SIZE=$(awk -v method="$METHOD" '
        BEGIN { in_method=0; last_offset=0 }
        $0 ~ method "\\(" { in_method=1; next }
        in_method && /^[[:space:]]+[0-9]+:/ {
            offset = $1;
            gsub(/:/, "", offset);
            if (offset+0 > last_offset+0) last_offset = offset;
        }
        in_method && (/^  [a-zA-Z]/ || /^}/) { exit }
        END { print last_offset }
    ' "$BYTECODE_FILE")

    rm -f "$BYTECODE_FILE"

    if [ -z "$METHOD_SIZE" ]; then
        echo -e "${RED}ERROR: Could not determine size of $DISPLAY_NAME${NC}"
        return 1
    fi

    # Display result
    printf "%-60s %5d bytes  " "$DISPLAY_NAME:" "$METHOD_SIZE"

    if [ "$METHOD_SIZE" -gt "$MAX_SIZE" ]; then
        echo -e "${RED}FAIL (exceeds ${MAX_SIZE})${NC}"
        return 1
    elif [ "$METHOD_SIZE" -gt 7000 ]; then
        echo -e "${YELLOW}WARN (close to limit)${NC}"
        return 0
    else
        echo -e "${GREEN}OK${NC}"
        return 0
    fi
}

echo "Checking BytecodeInterpreter method sizes..."
echo "Target: under $MAX_SIZE bytes for reliable JIT compilation"
echo ""

# Check main execute() method
check_method "org.perlonjava.interpreter.BytecodeInterpreter" "execute" || FAILED=1

# Check secondary methods
check_method "org.perlonjava.interpreter.BytecodeInterpreter" "executeComparisons" || FAILED=1
check_method "org.perlonjava.interpreter.BytecodeInterpreter" "executeArithmetic" || FAILED=1
check_method "org.perlonjava.interpreter.BytecodeInterpreter" "executeCollections" || FAILED=1
check_method "org.perlonjava.interpreter.BytecodeInterpreter" "executeTypeOps" || FAILED=1

echo ""

if [ "$FAILED" -eq 1 ]; then
    echo -e "${RED}FAILURE: Some methods exceed size limits${NC}"
    echo ""
    echo "Solution: Move more opcodes from main execute() switch to secondary methods"
    echo "See: dev/interpreter/SKILL.md for architecture details"
    exit 1
fi

echo -e "${GREEN}SUCCESS: All methods within size limits!${NC}"
exit 0
