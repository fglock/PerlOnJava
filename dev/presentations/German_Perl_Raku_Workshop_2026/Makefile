.PHONY: help serve open clean

# Default target
help:
	@echo "PerlOnJava Presentation - Available Commands:"
	@echo ""
	@echo "  make serve    - Start web server and view presentation"
	@echo "  make open     - Just open browser (server must be running)"
	@echo "  make pdf      - Instructions for PDF export"
	@echo "  make clean    - Clean temporary files"
	@echo ""

# Start web server and open browser
serve:
	@echo "Starting web server on http://localhost:8000"
	@echo "Press Ctrl+C to stop"
	@echo ""
	@echo "Keyboard shortcuts:"
	@echo "  Arrow keys - Navigate slides"
	@echo "  S - Speaker notes"
	@echo "  F - Fullscreen"
	@echo "  O - Overview"
	@echo "  ? - Help"
	@echo ""
	@command -v open >/dev/null 2>&1 && (sleep 2 && open http://localhost:8000) & true
	@command -v xdg-open >/dev/null 2>&1 && (sleep 2 && xdg-open http://localhost:8000) & true
	@if command -v python3 >/dev/null 2>&1; then \
		python3 -m http.server 8000; \
	elif command -v python >/dev/null 2>&1; then \
		python -m http.server 8000; \
	elif command -v php >/dev/null 2>&1; then \
		php -S localhost:8000; \
	else \
		echo "Error: No suitable web server found."; \
		echo "Install Python 3 or PHP to run the presentation."; \
		exit 1; \
	fi

# Just open browser
open:
	@echo "Opening presentation in browser..."
	@command -v open >/dev/null 2>&1 && open http://localhost:8000 || true
	@command -v xdg-open >/dev/null 2>&1 && xdg-open http://localhost:8000 || true
	@echo "If browser didn't open, go to: http://localhost:8000"

# PDF export instructions
pdf:
	@echo "To export to PDF:"
	@echo "1. Start presentation: make serve"
	@echo "2. Add ?print-pdf to URL: http://localhost:8000/?print-pdf"
	@echo "3. Print (Ctrl+P or Cmd+P)"
	@echo "4. Set destination: Save as PDF"
	@echo "5. Set layout: Landscape"
	@echo "6. Save file"

# Clean temporary files
clean:
	@echo "No temporary files to clean"
