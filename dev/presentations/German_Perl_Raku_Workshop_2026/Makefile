.PHONY: help serve open clean slides part1 part2

# Which slides file to present (default: original combined deck)
SLIDES ?= slides.md

# Default target
help:
	@echo "PerlOnJava Presentation - Available Commands:"
	@echo ""
	@echo "  make slides   - Present original combined deck (slides.md)"
	@echo "  make part1    - Present Part 1: Introduction & Demo (20 min)"
	@echo "  make part2    - Present Part 2: Technical Deep-Dive (40 min)"
	@echo "  make serve    - Present with custom file: make serve SLIDES=myfile.md"
	@echo "  make open     - Just open browser (server must be running)"
	@echo "  make stats    - Collect statistics and benchmarks for slide numbers"
	@echo "  make pdf      - Instructions for PDF export"
	@echo "  make clean    - Clean temporary files"
	@echo ""

# Convenience targets for each deck
slides:
	@$(MAKE) serve SLIDES=slides.md

part1:
	@$(MAKE) serve SLIDES=slides-part1-intro.md

part2:
	@$(MAKE) serve SLIDES=slides-part2-technical.md

# Start web server and open browser (use SLIDES variable to choose deck)
serve:
	@echo "Presenting: $(SLIDES)"
	@sed 's|data-markdown="[^"]*"|data-markdown="$(SLIDES)"|' index.html > index_tmp.html
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
	@command -v open >/dev/null 2>&1 && (sleep 2 && open http://localhost:8000/index_tmp.html) & true
	@command -v xdg-open >/dev/null 2>&1 && (sleep 2 && xdg-open http://localhost:8000/index_tmp.html) & true
	@trap 'rm -f index_tmp.html' EXIT INT TERM; \
	if command -v python3 >/dev/null 2>&1; then \
		python3 -m http.server 8000; \
	elif command -v python >/dev/null 2>&1; then \
		python -m http.server 8000; \
	elif command -v php >/dev/null 2>&1; then \
		php -S localhost:8000; \
	else \
		echo "Error: No suitable web server found."; \
		echo "Install Python 3 or PHP to run the presentation."; \
		rm -f index_tmp.html; \
		exit 1; \
	fi

# Just open browser
open:
	@echo "Opening presentation in browser..."
	@command -v open >/dev/null 2>&1 && open http://localhost:8000 || true
	@command -v xdg-open >/dev/null 2>&1 && xdg-open http://localhost:8000 || true
	@echo "If browser didn't open, go to: http://localhost:8000"

# Collect statistics and benchmarks for slide numbers
stats:
	@perl ./collect_slide_numbers.pl

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
	@rm -f index_tmp.html
	@echo "Cleaned temporary files"
