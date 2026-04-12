# Image::Magick CLI Wrapper for PerlOnJava

## Overview

Provide a working `Image::Magick` module for PerlOnJava by wrapping the
ImageMagick CLI (`magick` command) in pure Perl. This replaces the CPAN
module's XS/C layer entirely — no Java XS stub needed.

**This is NOT a typical CPAN port.** The original module's XS layer links
directly to libMagickCore. Instead of reimplementing 15,000 lines of C in
Java, we delegate to the `magick` CLI, which is already installed on most
developer machines.

---

## Architecture

### Object Model

An `Image::Magick` object is a **blessed array reference** (matching CPAN):

```perl
$img = Image::Magick->new(size => '100x100');
# $img is blessed [], each element is an image frame
```

Each element stores a path to a temp file (MIFF format, lossless).

### Deferred Execution

Operations are **not executed immediately**. Instead, CLI flags are queued
and flushed as a single `magick` invocation when output is needed:

```perl
$img->Read('photo.jpg');      # stores source path
$img->Blur(radius => 5);      # queues "-blur 5x2"
$img->Rotate(degrees => 45);  # queues "-rotate 45"
$img->Write('out.png');        # executes: magick photo.jpg -blur 5x2 -rotate 45 out.png
```

**Flush triggers:**
- `Write()` / `ImageToBlob()` — need output
- `Get()` on computed attributes (columns, rows, etc.) — need metadata
- `Clone()` — must materialize to temp file

After flush, the result is saved to a temp MIFF file that becomes the new
baseline for subsequent operations.

### CLI Detection

At `use Image::Magick` time, detect the ImageMagick CLI:

1. Try `magick --version` (IM7 — macOS/Homebrew, Windows, Ubuntu 25.04+)
2. Try `convert --version` (IM6 — Ubuntu 24.04 LTS and older)
3. Die with a clear error listing install commands if neither found

IM6 support adds minimal complexity since the CLI flags are identical —
only the command name differs.

### Command Mapping

| IM7 | IM6 | Used for |
|-----|-----|----------|
| `magick` | `convert` | Read/transform/write pipeline |
| `magick identify` | `identify` | Get image attributes (Ping, Get) |
| `magick composite` | `composite` | Composite operations |
| `magick montage` | `montage` | Montage operations |

### Temp File Management

- Use `File::Temp` for cross-platform temp files
- MIFF format preserves all metadata and supports multi-frame
- `DESTROY` cleans up temp files per object
- `END` block cleans up any remaining temp files as safety net

---

## API Coverage

### Fully Supported

| Method | Implementation |
|--------|---------------|
| `new()` / `New()` | Pure Perl constructor (blessed arrayref) |
| `Read()` / `ReadImage()` | Store source path, defer |
| `Write()` / `WriteImage()` | Flush pipeline to output file |
| `Set()` / `SetAttribute()` | Store attributes, queue as CLI flags |
| `Get()` / `GetAttribute()` | Flush if needed, run `identify` |
| `Ping()` / `PingImage()` | Run `identify` without full decode |
| `Clone()` | Flush and copy temp file |
| `Composite()` | Run `magick composite` / `composite` |
| `Montage()` | Run `magick montage` / `montage` |
| `ImageToBlob()` | Flush to stdout, capture |
| `BlobToImage()` | Write blob to temp, read |
| `Mogrify()` | Generic dispatch to operation queue |
| All transform methods | Queue as CLI flags (Blur, Crop, Rotate, etc.) |

### Not Supported (die with clear error)

| Method | Reason |
|--------|--------|
| `Display()` / `Animate()` | Requires X11 server |
| `GetPixel()` / `SetPixel()` | Per-pixel access impractical via CLI |
| `GetPixels()` / `SetPixels()` | Bulk pixel access impractical via CLI |
| `GetAuthenticPixels()` etc. | Low-level C pointer access |
| `RemoteCommand()` | X11 display control |

### Constants

Exported constants (QuantumDepth, error codes, etc.) are defined as
numeric values matching ImageMagick defaults.

---

## Return Value Convention

All methods follow the CPAN convention:

- **Success**: Return falsy value (`''` in string context)
- **Failure**: Return truthy string `"Exception NNN: reason (description)"`
- Methods returning new objects (Clone, Montage, etc.): Return blessed
  arrayref on success, error string on failure

---

## Cross-Platform Support

| Platform | IM version | Command | Install |
|----------|-----------|---------|---------|
| macOS (Homebrew) | IM7 | `magick` | `brew install imagemagick` |
| Ubuntu 25.04+ | IM7 | `magick` | `apt install imagemagick` |
| Ubuntu 24.04 LTS | IM6 | `convert` | `apt install imagemagick` |
| Windows | IM7 | `magick` | `choco install imagemagick` |

### Key cross-platform details:
- Use `File::Temp` (not `/tmp/` hardcoded) for temp files
- Use list-form `system(@cmd)` to avoid shell quoting issues
- Detect IM version at load time, cache command paths
- On Windows, avoid `convert` (conflicts with system `convert.exe`)

---

## Files

| File | Purpose |
|------|---------|
| `src/main/perl/lib/Image/Magick.pm` | CLI wrapper module |
| `src/test/resources/module/Image-Magick/t/basic.t` | Unit test (skips if `magick` not installed) |

### Removed

| File | Reason |
|------|--------|
| `src/main/java/.../ImageMagick.java` | No longer needed; `.pm` replaces it entirely |

---

## Progress Tracking

### Current Status: Implementation

### Completed
- [x] API analysis (2025-04-12)
- [x] Cross-platform CLI research (2025-04-12)
- [x] Design document (2025-04-12)

### In Progress
- [ ] Implement `Image/Magick.pm` CLI wrapper
- [ ] Write unit tests
- [ ] Remove Java stub

### Next Steps
- [ ] Run `make` to verify no regressions
- [ ] Create PR
- [ ] Test on CI (verify graceful skip when `magick` not installed)
