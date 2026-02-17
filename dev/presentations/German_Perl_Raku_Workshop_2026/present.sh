#!/bin/bash
# Quick presentation launcher script

echo "PerlOnJava Presentation Launcher"
echo "================================="
echo ""

# Find available web server
if command -v python3 >/dev/null 2>&1; then
    SERVER="python3 -m http.server 8000"
    echo "Using: Python 3"
elif command -v python >/dev/null 2>&1; then
    SERVER="python -m http.server 8000"
    echo "Using: Python 2"
elif command -v php >/dev/null 2>&1; then
    SERVER="php -S localhost:8000"
    echo "Using: PHP"
else
    echo "Error: No suitable web server found"
    echo "Please install Python 3 or PHP"
    exit 1
fi

echo ""
echo "Starting web server on http://localhost:8000"
echo "Press Ctrl+C to stop"
echo ""
echo "Keyboard shortcuts:"
echo "  Arrow keys - Navigate slides"
echo "  S - Speaker notes"
echo "  F - Fullscreen"
echo "  ESC/O - Overview"
echo "  ? - Help"
echo ""

# Open browser after delay
(
    sleep 2
    if command -v open >/dev/null 2>&1; then
        open http://localhost:8000
    elif command -v xdg-open >/dev/null 2>&1; then
        xdg-open http://localhost:8000
    else
        echo "Browser not auto-opened. Go to: http://localhost:8000"
    fi
) &

# Start server
cd "$(dirname "$0")"
$SERVER
