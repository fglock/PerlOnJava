#!/bin/bash
# Scan all compiled Java classes to find methods approaching JIT compilation limit
#
# The JVM refuses to JIT-compile methods larger than ~8000 bytes, causing 5-10x
# performance degradation. This script proactively identifies methods at risk.

set -e

CRITICAL_LIMIT=8000  # JVM hard limit
WARNING_LIMIT=7000   # Warn when getting close
BUILD_DIR="build/classes/java/main"

# Color output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

if [ ! -d "$BUILD_DIR" ]; then
    echo -e "${RED}ERROR: Build directory not found: $BUILD_DIR${NC}"
    echo "Run 'make build' first"
    exit 1
fi

echo "Scanning all compiled classes for large methods..."
echo "Critical limit: $CRITICAL_LIMIT bytes (JVM won't JIT-compile)"
echo "Warning limit: $WARNING_LIMIT bytes (getting close)"
echo ""

# Temporary files
RESULTS=$(mktemp)
CRITICAL=$(mktemp)
WARNING=$(mktemp)
ALL_METHODS=$(mktemp)

# Find all .class files and analyze them
find "$BUILD_DIR" -name "*.class" | while read -r CLASS_FILE; do
    # Convert path to class name
    CLASS_NAME=$(echo "$CLASS_FILE" | \
                 sed "s|^$BUILD_DIR/||" | \
                 sed 's|/|.|g' | \
                 sed 's|.class$||')

    # Skip inner classes with $ in name (analyze outer class instead)
    if [[ "$CLASS_NAME" == *'$'* ]]; then
        continue
    fi

    # Get bytecode with javap
    BYTECODE=$(javap -c -private -classpath "$BUILD_DIR" "$CLASS_NAME" 2>/dev/null || continue)

    # Extract method sizes
    echo "$BYTECODE" | awk -v class="$CLASS_NAME" '
        BEGIN {
            current_method = ""
            max_offset = 0
        }

        # Match method signature
        /^  (public|private|protected|static|final|abstract|synchronized).*[({]/ {
            # Save previous method if it had bytecode
            if (current_method != "" && max_offset > 0) {
                print class "." current_method ":" max_offset
            }

            # Extract method name (everything before the opening paren)
            current_method = $0
            gsub(/^[[:space:]]+/, "", current_method)  # trim leading space
            gsub(/\{.*$/, "", current_method)           # remove { and after
            gsub(/;.*$/, "", current_method)            # remove ; and after
            max_offset = 0
        }

        # Match bytecode offset
        # Real offsets: "         0: aload_1" (number: instruction)
        # Skip switch case labels: "             12345: 42" (number: number)
        /^[[:space:]]+[0-9]+:[[:space:]]+[a-z_]/ {
            offset = $1
            gsub(/:/, "", offset)
            if (offset+0 > max_offset+0) {
                max_offset = offset
            }
        }

        # Method ended
        /^  }$/ || /^[[:space:]]*$/ {
            if (current_method != "" && max_offset > 0) {
                print class "." current_method ":" max_offset
                current_method = ""
                max_offset = 0
            }
        }

        END {
            # Handle last method
            if (current_method != "" && max_offset > 0) {
                print class "." current_method ":" max_offset
            }
        }
    ' >> "$ALL_METHODS"
done

# Sort all methods by size
sort -t: -k2 -rn "$ALL_METHODS" > "$RESULTS"

# Find critical and warning methods
while IFS=: read -r method size; do
    if [ "$size" -ge "$CRITICAL_LIMIT" ]; then
        echo "$method:$size" >> "$CRITICAL"
    elif [ "$size" -ge "$WARNING_LIMIT" ]; then
        echo "$method:$size" >> "$WARNING"
    fi
done < "$RESULTS"

# Display critical methods (won't JIT compile)
if [ -s "$CRITICAL" ]; then
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${RED}CRITICAL: Methods exceeding JIT compilation limit (>= $CRITICAL_LIMIT bytes)${NC}"
    echo -e "${RED}These methods will NOT be JIT-compiled and will run 5-10x slower!${NC}"
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    while IFS=: read -r method size; do
        printf "${RED}%6d bytes${NC}  %s\n" "$size" "$method"
    done < "$CRITICAL"
    echo ""
fi

# Display warning methods (close to limit)
if [ -s "$WARNING" ]; then
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}WARNING: Methods close to JIT compilation limit ($WARNING_LIMIT-$CRITICAL_LIMIT bytes)${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    while IFS=: read -r method size; do
        printf "${YELLOW}%6d bytes${NC}  %s\n" "$size" "$method"
    done < "$WARNING"
    echo ""
fi

# Display top 20 largest methods
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BLUE}Top 20 largest methods (for monitoring)${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
head -20 "$RESULTS" | while IFS=: read -r method size; do
    if [ "$size" -ge "$CRITICAL_LIMIT" ]; then
        COLOR=$RED
    elif [ "$size" -ge "$WARNING_LIMIT" ]; then
        COLOR=$YELLOW
    else
        COLOR=$GREEN
    fi
    printf "${COLOR}%6d bytes${NC}  %s\n" "$size" "$method"
done
echo ""

# Summary statistics
TOTAL_METHODS=$(wc -l < "$RESULTS")
CRITICAL_COUNT=$(wc -l < "$CRITICAL" 2>/dev/null || echo 0)
WARNING_COUNT=$(wc -l < "$WARNING" 2>/dev/null || echo 0)
SAFE_COUNT=$((TOTAL_METHODS - CRITICAL_COUNT - WARNING_COUNT))

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo "Summary:"
echo "  Total methods analyzed: $TOTAL_METHODS"
echo -e "  ${RED}Critical (>= $CRITICAL_LIMIT bytes): $CRITICAL_COUNT${NC}"
echo -e "  ${YELLOW}Warning ($WARNING_LIMIT-$CRITICAL_LIMIT bytes): $WARNING_COUNT${NC}"
echo -e "  ${GREEN}Safe (< $WARNING_LIMIT bytes): $SAFE_COUNT${NC}"
echo ""

# Cleanup
rm -f "$RESULTS" "$CRITICAL" "$WARNING" "$ALL_METHODS"

# Exit code
if [ "$CRITICAL_COUNT" -gt 0 ]; then
    echo -e "${RED}FAILED: Found $CRITICAL_COUNT method(s) exceeding JIT compilation limit!${NC}"
    echo "These methods will cause significant performance degradation."
    echo ""
    echo "Solutions:"
    echo "  1. Split large methods into smaller helper methods"
    echo "  2. Use delegation pattern (like BytecodeInterpreter.execute)"
    echo "  3. Move cold-path code to separate methods"
    echo ""
    exit 1
elif [ "$WARNING_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}WARNING: Found $WARNING_COUNT method(s) close to JIT compilation limit${NC}"
    echo "Consider refactoring these methods before they exceed the limit."
    echo ""
    exit 0
else
    echo -e "${GREEN}SUCCESS: All methods are within safe JIT compilation limits!${NC}"
    exit 0
fi
