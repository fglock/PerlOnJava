#!/bin/bash
# Run command with timeout (macOS compatible)
TIMEOUT=$1
shift
COMMAND="$@"

# Run command in background
$COMMAND &
PID=$!

# Wait for timeout
(sleep $TIMEOUT && kill -KILL $PID 2>/dev/null) &
KILLER=$!

# Wait for command to finish
wait $PID 2>/dev/null
EXIT_CODE=$?

# Kill the killer if command finished
kill -KILL $KILLER 2>/dev/null
wait $KILLER 2>/dev/null

exit $EXIT_CODE
