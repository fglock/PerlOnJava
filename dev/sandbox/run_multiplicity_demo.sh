#!/usr/bin/env bash
#
# Compile and run the Multiplicity Demo.
#
# Usage:
#   ./dev/sandbox/run_multiplicity_demo.sh [script1.pl script2.pl ...]
#
# If no scripts are given, runs the three bundled demo scripts.
#
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

DEMO_SRC="dev/sandbox/MultiplicityDemo.java"
DEMO_DIR="dev/sandbox"

# Find the fat JAR the same way jperl does
if [ -f "target/perlonjava-5.42.0.jar" ]; then
    JAR="target/perlonjava-5.42.0.jar"
elif [ -f "perlonjava-5.42.0.jar" ]; then
    JAR="perlonjava-5.42.0.jar"
else
    echo "Fat JAR not found. Run 'make dev' first."
    exit 1
fi

# Compile the demo against the fat JAR
echo "Compiling MultiplicityDemo.java..."
javac -d "$DEMO_DIR" -cp "$JAR" "$DEMO_SRC"

# Default scripts if none provided
if [ $# -eq 0 ]; then
    set -- dev/sandbox/multiplicity_script1.pl \
           dev/sandbox/multiplicity_script2.pl \
           dev/sandbox/multiplicity_script3.pl
fi

echo ""
# Run with the demo class prepended to the classpath
java -cp "$DEMO_DIR:$JAR" org.perlonjava.demo.MultiplicityDemo "$@"
