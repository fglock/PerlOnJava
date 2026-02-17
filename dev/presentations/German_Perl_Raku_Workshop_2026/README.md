# PerlOnJava Presentation - German Perl/Raku Workshop 2026

This directory contains the presentation materials for a 40-minute technical talk about PerlOnJava.

## Files

- **presentation_plan.md** - Detailed speaker notes and full presentation plan
- **slides.md** - Actual slide content in Markdown format
- **index.html** - reveal.js presentation (loads slides.md)
- **README.md** - This file

## Viewing the Presentation

### Option 1: Local Web Server (Recommended)

The presentation uses reveal.js loaded from CDN, but the markdown file needs to be served via HTTP (not file://).

**Using Python:**
```bash
cd dev/presentations/German_Perl_Raku_Workshop_2026
python3 -m http.server 8000
```

Then open: http://localhost:8000/

**Using Node.js:**
```bash
cd dev/presentations/German_Perl_Raku_Workshop_2026
npx http-server -p 8000
```

Then open: http://localhost:8000/

**Using PHP:**
```bash
cd dev/presentations/German_Perl_Raku_Workshop_2026
php -S localhost:8000
```

Then open: http://localhost:8000/

### Option 2: Direct File (Limited)

You can try opening `index.html` directly in a browser, but some features may not work due to CORS restrictions. Use a local web server for best results.

## Presenting

### Keyboard Shortcuts

- **Arrow keys** or **Space** - Navigate slides
- **S** - Open speaker notes window
- **F** - Fullscreen
- **ESC** or **O** - Slide overview
- **B** or **.** - Pause/blackout
- **?** - Show help

### Speaker Notes

Press **S** to open a separate speaker notes window that shows:
- Current slide
- Next slide
- Speaker notes from the `Note:` sections
- Elapsed time

### Navigation Tips

- Use arrow keys to navigate
- Slides with vertical content use up/down arrows
- Progress bar shows overall position
- Slide numbers in bottom right

## Customization

### Themes

Change theme in `index.html` line 13:
- `black.css` (default, dark)
- `white.css` (light)
- `league.css` (gray/blue)
- `beige.css`
- `sky.css`
- `night.css`
- `serif.css`
- `simple.css`
- `solarized.css`

### Transitions

Change transition in `index.html` line 61:
- `none` - No transition
- `fade` - Fade effect
- `slide` - Slide effect (default)
- `convex` - 3D convex effect
- `concave` - 3D concave effect
- `zoom` - Zoom effect

## Editing Slides

### Slide Separators

- `---` (three dashes) = New horizontal slide
- `----` (four dashes) = New vertical slide (sub-slide)
- `Note:` = Speaker notes (not shown on slides)

### Code Blocks

Use fenced code blocks with language:

    ```perl
    my $x = 42;
    say "Hello $x";
    ```

Supported languages: perl, java, bash, javascript, and many more.

### Fragments (Step-by-step Reveal)

Add `<!-- .element: class="fragment" -->` after items:

```markdown
- First item <!-- .element: class="fragment" -->
- Second item <!-- .element: class="fragment" -->
```

### Columns

Use HTML for multi-column layouts:

```html
<div class="three-columns">
<div>
Column 1 content
</div>
<div>
Column 2 content
</div>
<div>
Column 3 content
</div>
</div>
```

## Exporting to PDF

1. Open the presentation in Chrome/Chromium
2. Add `?print-pdf` to the URL: http://localhost:8000/?print-pdf
3. Open print dialog (Ctrl+P / Cmd+P)
4. Set destination to "Save as PDF"
5. Set layout to "Landscape"
6. Save

## Live Demo Scripts

For the live demo section, you can switch to terminal and run:

```bash
cd /Users/fglock/projects/PerlOnJava

# Simple hello
./jperl -E 'say "Hello from PerlOnJava!"'

# Conway's Game of Life (visual)
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl

# Just Another Perl Hacker
./jperl examples/japh.pl
```

## Recording the Presentation

### For Video Recording

1. Use fullscreen mode (F key)
2. Disable mouse cursor in recording software
3. Consider using Presenter mode (S key) on second monitor
4. Use arrow keys for navigation (cleaner than mouse)

### Timing Guide

The presentation is designed for 40 minutes:
- Section 1 (Intro): 8 minutes (slides 1-9)
- Section 2 (Pipeline): 8 minutes (slides 10-16)
- Section 3 (Dual Model): 8 minutes (slides 17-24)
- Section 4 (Features): 8 minutes (slides 25-33)
- Section 5 (Integration): 8 minutes (slides 34-43)

Pace: ~1 minute per slide average, more time for code-heavy slides.

## Technical Details

- **reveal.js version**: 5.0.4 (loaded from CDN)
- **Plugins**: Markdown, Highlight, Notes, Zoom, Search
- **Code highlighting**: Monokai theme
- **Slide size**: 1280x720 (16:9 aspect ratio)
- **Browser compatibility**: Modern browsers (Chrome, Firefox, Safari, Edge)

## Troubleshooting

### Slides not loading
- Make sure you're using a web server (not file://)
- Check browser console for errors
- Verify internet connection (CDN resources)

### Code highlighting not working
- Check that language is specified in code block
- Verify highlight plugin is loaded

### Speaker notes not showing
- Press 'S' key (not available in file:// mode)
- Check that popup blocker isn't blocking the window

## Resources

- **reveal.js documentation**: https://revealjs.com/
- **Markdown guide**: https://revealjs.com/markdown/
- **Keyboard shortcuts**: Press '?' in presentation
