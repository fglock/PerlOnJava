# GD Module for PerlOnJava

## Overview

Provide a working `GD` module for PerlOnJava by implementing the XS/C
layer in Java using `java.awt.Graphics2D` and `BufferedImage`.

GD is a Perl interface to Thomas Boutell's libgd graphics library. It
allows creation of color images using drawing primitives (lines, arcs,
polygons, text) and emitting them as PNG, JPEG, GIF, or BMP files.

**Author:** Lincoln D. Stein  
**CPAN version:** 2.84  
**License:** Perl 5 (Artistic + GPL)

---

## Why Java AWT (Not CLI Wrapper)

Unlike `Image::Magick` (which wraps the `magick` CLI), GD **cannot** use
a CLI wrapper because:

1. **libgd has no CLI tool** — only format converters (`gdtopng`, etc.),
   no drawing pipeline
2. **GD is used for programmatic image construction** — allocate colors,
   draw shapes, get/set individual pixels
3. **Per-pixel operations** (`getPixel`/`setPixel`) are core to GD usage
   (captchas, chart rendering)
4. **GD::Graph and GD::Barcode** build images from scratch, not from files

Java's `java.awt` is a natural fit:

| GD concept | Java equivalent |
|---|---|
| `gdImagePtr` (image handle) | `BufferedImage` |
| Drawing primitives | `Graphics2D` methods |
| Color palette | `IndexColorModel` / int ARGB |
| Image I/O (PNG/JPEG/GIF/BMP) | `javax.imageio.ImageIO` |
| FreeType text rendering | `java.awt.Font` (native TTF support) |
| Alpha blending | `AlphaComposite` |
| Image transforms | `AffineTransformOp` |
| Convolution filters | `ConvolveOp` |

**No external Maven dependencies needed.** All of the above are in the JDK
standard library. Headless mode (`-Djava.awt.headless=true`) works on
servers without a display.

---

## Module Structure

### What Needs Java (XS functions — ~70 core, ~147 total)

All XS code lives in `GD.xs` and wraps `libgd` C functions. Every XS
function maps to exactly one `gdImage*()` call.

### What Is Already Pure Perl (reuse as-is from CPAN)

| File | Lines | Content |
|------|-------|---------|
| `GD/Polygon.pm` | ~200 | Vertex storage, bounds, offset, transform, rotate, scale |
| `GD/Polyline.pm` | ~150 | Spline/polyline extensions |
| `GD/Simple.pm` | ~1,250 | Turtle graphics, HSV colors, color names |
| `GD/Group.pm` | ~20 | No-op for GD::SVG compatibility |
| `GD/Image.pm` | ~200 | `new()` dispatcher, `newFrom*()` wrappers, `_image_type()` magic detection, `clone()` |

These are **100% pure Perl** and need only the `bootstrap GD` line
changed to `XSLoader::load('GD', $VERSION)` in the main `GD.pm`.

---

## Architecture

### Object Model

A `GD::Image` object is a **blessed scalar** containing a pointer to the
native image. In PerlOnJava, the scalar holds a Java `BufferedImage`
stored as a `JAVAOBJECT`.

Additional per-image state (palette table, thickness, brush, style, clip
region) is stored in a Java-side companion object keyed by the image's
identity, since GD's C struct holds these fields internally.

```java
// Internal state per GD::Image
class GDImageState {
    BufferedImage image;
    boolean trueColor;
    List<Color> palette;       // for palette-mode images
    int transparent = -1;      // transparent color index
    int thickness = 1;
    BufferedImage brush;       // current brush image
    BufferedImage tile;        // current tile image
    int[] style;               // dash style array
    boolean interlaced;
    boolean saveAlpha;
    boolean alphaBlending = true;
}
```

### Alpha Channel Mapping

GD and Java use opposite alpha conventions:

| | Opaque | Transparent |
|---|---|---|
| **GD** | 0 | 127 (7-bit) |
| **Java** | 255 | 0 (8-bit) |

Conversion: `javaAlpha = (127 - gdAlpha) * 2`

### Palette vs Truecolor

GD supports both modes. Java handles this with:
- `BufferedImage.TYPE_INT_ARGB` for truecolor
- `BufferedImage.TYPE_BYTE_INDEXED` + `IndexColorModel` for palette

Color allocation returns palette indices in palette mode, packed ARGB
values in truecolor mode. The Java implementation must track which mode
each image uses and dispatch accordingly.

### Headless Mode

Set in `GD.java`'s `initialize()` method:

```java
System.setProperty("java.awt.headless", "true");
```

This ensures `Graphics2D` and `ImageIO` work on servers without X11.

---

## XS Function Inventory

### Image Creation (~18 functions)

| XS function | libgd call | Java mapping |
|---|---|---|
| `gd_new(x,y,truecolor)` | `gdImageCreate/TrueColor` | `new BufferedImage(w, h, type)` |
| `gd_newFromPng(fh)` | `gdImageCreateFromPng` | `ImageIO.read()` |
| `gdnewFromPngData(data)` | `gdImageCreateFromPngCtx` | `ImageIO.read(ByteArrayInputStream)` |
| `gd_newFromJpeg(fh)` | `gdImageCreateFromJpeg` | `ImageIO.read()` |
| `gdnewFromJpegData(data)` | `gdImageCreateFromJpegCtx` | `ImageIO.read(ByteArrayInputStream)` |
| `gd_newFromGif(fh)` | `gdImageCreateFromGif` | `ImageIO.read()` |
| `gdnewFromGifData(data)` | `gdImageCreateFromGifCtx` | `ImageIO.read(ByteArrayInputStream)` |
| `gd_newFromBmp(fh)` | `gdImageCreateFromBmp` | `ImageIO.read()` |
| `gdnewFromBmpData(data)` | `gdImageCreateFromBmpCtx` | `ImageIO.read(ByteArrayInputStream)` |
| `gd_newFromWBMP(fh)` | `gdImageCreateFromWBMP` | `ImageIO.read()` |
| `gd_newFromXbm(fh)` | `gdImageCreateFromXbm` | Custom parser (simple text format) |
| `gd_newFromTiff(fh)` | `gdImageCreateFromTiff` | Phase 3 — needs TwelveMonkeys plugin |
| `gd_newFromWebp(fh)` | `gdImageCreateFromWebp` | Phase 3 — needs TwelveMonkeys plugin |
| `gd_newFromHeif(fh)` | `gdImageCreateFromHeif` | Stub — no pure Java lib available |
| `gd_newFromAvif(fh)` | `gdImageCreateFromAvif` | Stub — no pure Java lib available |
| `gd_newFromGd(fh)` | `gdImageCreateFromGd` | Stub — deprecated format |
| `gd_newFromGd2(fh)` | `gdImageCreateFromGd2` | Stub — deprecated format |
| `gd_file(filename)` | `gdImageFile` | Dispatch by extension |

### Image Output (~12 functions)

| XS function | Java mapping |
|---|---|
| `gdpng(image, compression)` | `ImageIO.write(img, "png", baos)` |
| `gdjpeg(image, quality)` | `ImageIO.write()` with `JPEGImageWriteParam` |
| `gdgif(image)` | `ImageIO.write(img, "gif", baos)` |
| `gdbmp(image, compression)` | `ImageIO.write(img, "bmp", baos)` |
| `gdwbmp(image, fg)` | `ImageIO.write(img, "wbmp", baos)` |
| `gdtiff(image)` | Phase 3 — TwelveMonkeys |
| `gdwebp(image, quality)` | Phase 3 — TwelveMonkeys |
| `gdheif/gdavif` | Stub |
| `gdgd/gdgd2` | Stub — deprecated format |
| `gdgifanimbegin/add/end` | `javax.imageio.ImageWriter` GIF metadata |

### Drawing Primitives (~16 functions)

| XS function | Java mapping |
|---|---|
| `gdsetPixel(x,y,color)` | `BufferedImage.setRGB(x, y, color)` |
| `gdgetPixel(x,y)` | `BufferedImage.getRGB(x, y)` |
| `gdline(x1,y1,x2,y2,color)` | `Graphics2D.drawLine()` |
| `gddashedLine(...)` | `Graphics2D` with dashed `BasicStroke` |
| `gdrectangle(x1,y1,x2,y2,color)` | `Graphics2D.drawRect()` |
| `gdfilledRectangle(...)` | `Graphics2D.fillRect()` |
| `gdarc(cx,cy,w,h,s,e,color)` | `Graphics2D.drawArc()` |
| `gdfilledArc(...)` | `Graphics2D.fillArc()` + `Arc2D` style handling |
| `gdfilledEllipse(...)` | `Graphics2D.fillOval()` |
| `gdfill(x,y,color)` | Custom flood fill (~30 lines BFS) |
| `gdfillToBorder(x,y,border,color)` | Custom flood fill with border check |
| `gdopenPolygon(poly,color)` | `Graphics2D.drawPolygon()` |
| `gdunclosedPolygon(poly,color)` | `Graphics2D.drawPolyline()` |
| `gdfilledPolygon(poly,color)` | `Graphics2D.fillPolygon()` |
| `copy(dst,src,...)` | `Graphics2D.drawImage()` region copy |
| `copyResized/Resampled/Merge/...` | `Graphics2D.drawImage()` + compositing |

### Color Management (~15 functions)

| XS function | Notes |
|---|---|
| `colorAllocate(r,g,b)` | Palette: add to table, return index. Truecolor: pack ARGB. |
| `colorAllocateAlpha(r,g,b,a)` | Same with alpha conversion |
| `colorDeallocate(color)` | Remove from palette |
| `colorClosest(r,g,b)` | Euclidean distance search |
| `colorExact(r,g,b)` | Linear search |
| `colorResolve(r,g,b)` | Exact or closest |
| `colorsTotal` | Palette size |
| `gdtransparent(color)` | Get/set transparent index |
| `gdrgb(color)` | Return (r,g,b) triple |
| `gdalpha(color)` | Return alpha value |

### Text Rendering (~7 functions)

| XS function | Java mapping |
|---|---|
| `gdchar/gdcharUp` | Bitmap font single character |
| `gdstring/gdstringUp` | Bitmap font string |
| `gdstringFT(fg,fontname,ptsize,angle,x,y,string,opts)` | `Font.createFont(TRUETYPE_FONT)` + `Graphics2D.drawString()` with rotation |
| `gdstringFTCircle(...)` | Text along circular path |
| `gduseFontConfig(flag)` | `GraphicsEnvironment.getAvailableFontFamilyNames()` |

### Image Filters (~17 functions, libgd >= 2.1.0)

| XS function | Java mapping |
|---|---|
| `gdnegate` | `RescaleOp` / pixel inversion |
| `gdgrayscale` | `ColorConvertOp` |
| `gdbrightness` | `RescaleOp` |
| `gdcontrast` | Custom LUT |
| `gdgaussianBlur` | `ConvolveOp` with Gaussian kernel |
| `gdedgeDetectQuick` | `ConvolveOp` with Sobel kernel |
| `gdemboss` | `ConvolveOp` with emboss kernel |
| `gdsmooth` | `ConvolveOp` |
| `gdscatter/gdpixelate/...` | Custom pixel operations |
| `gdcopyGaussianBlurred(radius,sigma)` | Parameterized `ConvolveOp` |
| `gdcopyScaleInterpolated(w,h)` | `AffineTransformOp` |
| `gdcopyRotateInterpolated(angle,bg)` | `AffineTransformOp` |

### Transforms (~10 functions)

| XS function | Java mapping |
|---|---|
| `gdcopyRotate90/180/270` | `AffineTransformOp.rotate()` |
| `gdcopyFlipHorizontal/Vertical` | `AffineTransform.scale(-1,1)` / `scale(1,-1)` |
| `gdcopyTranspose/ReverseTranspose` | Custom pixel swap |
| `gdrotate180/flipHorizontal/flipVertical` (in-place) | Modify `BufferedImage` pixels |

### Style & State (~10 functions)

| XS function | Java mapping |
|---|---|
| `setThickness(n)` | `Graphics2D.setStroke(new BasicStroke(n))` |
| `setBrush(brush_img)` | Store reference, stamp at each drawn pixel |
| `setTile(tile_img)` | `TexturePaint` |
| `setStyle(colors...)` | Custom `BasicStroke` dash pattern |
| `gdsetAntiAliased(color)` | `RenderingHints.KEY_ANTIALIASING` |
| `gdalphaBlending(flag)` | `AlphaComposite` mode |
| `gdsaveAlpha(flag)` | Toggle alpha channel in output |
| `gdclip(x1,y1,x2,y2)` | `Graphics2D.setClip()` |
| `gdinterlaced(flag)` | PNG progressive mode |

### GD::Font (~8 functions)

| XS function | Notes |
|---|---|
| `gdSmall/Large/Giant/MediumBold/Tiny` | Return built-in font objects |
| `gdload(fontpath)` | Load custom GD bitmap font |
| `gdnchars/gdoffset/gdwidth/gdheight` | Font metrics accessors |

GD has 5 fixed-size bitmap fonts:

| Font | Pixel size |
|------|-----------|
| `gdTinyFont` | 5 x 8 |
| `gdSmallFont` | 6 x 12 |
| `gdMediumBoldFont` | 7 x 13 |
| `gdLargeFont` | 8 x 16 |
| `gdGiantFont` | 9 x 15 |

Java approach: map to `java.awt.Font("Monospaced", PLAIN, size)` with
appropriate sizes, or embed GD's bitmap font data as static byte arrays
for exact pixel compatibility.

### Constants

Exported via `AUTOLOAD` and `constant()` XS function:

| Category | Key constants |
|---|---|
| Special colors | `gdBrushed(-1)`, `gdStyled(-2)`, `gdStyledBrushed(-3)`, `gdTiled(-4)`, `gdTransparent(-6)`, `gdAntiAliased(-7)` |
| Arc styles | `gdArc(0)`, `gdChord(1)`, `gdPie(1)`, `gdNoFill(2)`, `gdEdged(4)` |
| Limits | `gdMaxColors(256)`, `gdDashSize(4)`, `gdAlphaMax(127)`, `gdAlphaOpaque(0)`, `gdAlphaTransparent(127)` |
| Comparison flags | `GD_CMP_IMAGE(1)`, `GD_CMP_NUM_COLORS(2)`, `GD_CMP_COLOR(4)`, `GD_CMP_SIZE_X(8)`, `GD_CMP_SIZE_Y(16)`, etc. |

---

## Implementation Plan

### Phase 1: Core MVP

**Goal:** `GD::Image->new()`, draw shapes, output PNG/JPEG/GIF. Enough
for `GD::Graph`, `GD::Barcode`, and `PDF::API2::...::GD`.

**Files to create:**

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/perlmodule/GD.java` | Java XS for `GD` + `GD::Image` + `GD::Font` |
| `src/main/perl/lib/GD.pm` | Adapted from CPAN: `bootstrap GD` -> `XSLoader::load` |
| `src/main/perl/lib/GD/Image.pm` | Copy from CPAN as-is |
| `src/main/perl/lib/GD/Polygon.pm` | Copy from CPAN as-is |
| `src/main/perl/lib/GD/Polyline.pm` | Copy from CPAN as-is |
| `src/main/perl/lib/GD/Simple.pm` | Copy from CPAN as-is |
| `src/main/perl/lib/GD/Group.pm` | Copy from CPAN as-is |
| `src/test/resources/module/GD/t/basic.t` | Core functionality tests |

**Functions to implement (~35):**

Image lifecycle:
- `_new(x, y, truecolor)` — create blank image
- `_newFromPng(fh)`, `_newFromPngData(data)` — read PNG
- `_newFromJpeg(fh)`, `_newFromJpegData(data)` — read JPEG
- `_newFromGif(fh)`, `_newFromGifData(data)` — read GIF
- `DESTROY(image)` — cleanup
- `png(image, compression)`, `jpeg(image, quality)`, `gif(image)` — output

Color management:
- `colorAllocate(r,g,b)`, `colorAllocateAlpha(r,g,b,a)`
- `colorDeallocate(color)`
- `colorClosest(r,g,b)`, `colorExact(r,g,b)`, `colorResolve(r,g,b)`
- `colorsTotal`, `transparent(color)`, `rgb(color)`, `alpha(color)`

Drawing:
- `setPixel(x,y,color)`, `getPixel(x,y)`
- `line(x1,y1,x2,y2,color)`
- `rectangle(x1,y1,x2,y2,color)`, `filledRectangle(...)`
- `arc(cx,cy,w,h,s,e,color)`, `filledArc(...)`
- `fill(x,y,color)`, `fillToBorder(x,y,border,color)`
- `openPolygon(poly,color)`, `filledPolygon(poly,color)`

Info:
- `getBounds(image)` — returns (width, height)
- `isTrueColor(image)`
- `boundsSafe(x,y)`

Text:
- `string(font,x,y,s,color)`, `stringUp(...)`
- `stringFT(fg,fontname,ptsize,angle,x,y,string)`

Style:
- `setThickness(n)`
- `alphaBlending(flag)`, `saveAlpha(flag)`

Constants:
- `constant(name)` — AUTOLOAD dispatch

Built-in fonts:
- `Small()`, `Large()`, `Giant()`, `MediumBold()`, `Tiny()`
- `nchars()`, `offset()`, `width()`, `height()`

**Verify:**
```bash
make dev
./jperl -e '
    use GD;
    my $im = GD::Image->new(100, 100);
    my $white = $im->colorAllocate(255, 255, 255);
    my $black = $im->colorAllocate(0, 0, 0);
    my $red = $im->colorAllocate(255, 0, 0);
    $im->rectangle(0, 0, 99, 99, $black);
    $im->arc(50, 50, 95, 75, 0, 360, $red);
    $im->fill(50, 50, $red);
    binmode STDOUT;
    print $im->png;
' > /tmp/test.png
open /tmp/test.png  # macOS
```

### Phase 2: Copy, Merge, and Full Drawing

**Goal:** Image manipulation, full polygon support, brushes, dashed lines.
Enables advanced `GD::Graph` styles and `GD::Simple` turtle graphics.

**Functions (~25):**

- `copy(dst,src,dstX,dstY,srcX,srcY,w,h)`
- `copyResized(dst,src,dstX,dstY,srcX,srcY,dstW,dstH,srcW,srcH)`
- `copyResampled(...)` — bilinear interpolation
- `copyMerge(...)`, `copyMergeGray(...)` — alpha blending
- `paletteCopy(dst,src)`
- `dashedLine(x1,y1,x2,y2,color)`
- `unclosedPolygon(poly,color)`
- `setBrush(brush_img)`, `setTile(tile_img)`, `setStyle(colors...)`
- `setAntiAliased(color)`, `setAntiAliasedDontBlend(color,flag)`
- `clip(x1,y1,x2,y2)` — get/set clip region
- `interlaced(flag)`
- `trueColor(flag)` — get/set default
- `trueColorToPalette(dither,colors)`
- `compare(img1,img2)`
- `STORABLE_freeze/thaw` — Storable serialization

Image output:
- `bmp(compression)`, `wbmp(fg)`

### Phase 3: Filters, Transforms, Animation

**Goal:** Image filters, geometric transforms, animated GIF, uncommon
format support.

**Functions (~40):**

Filters:
- `negate`, `grayscale`, `brightness`, `contrast`
- `gaussianBlur`, `edgeDetectQuick`, `emboss`, `smooth`, `meanRemoval`
- `selectiveBlur`, `scatter`, `scatterColor`, `pixelate`, `color`
- `copyGaussianBlurred(radius,sigma)`

Transforms:
- `copyRotate90/180/270`
- `copyFlipHorizontal/Vertical`
- `copyTranspose/ReverseTranspose`
- `rotate180`, `flipHorizontal`, `flipVertical` (in-place)
- `copyScaleInterpolated(w,h)`, `copyRotateInterpolated(angle,bg)`
- `interpolationMethod(method)`

GIF animation:
- `gifAnimBegin(globalcm,loops)`
- `gifAnimAdd(localcm,leftofs,topofs,delay,disposal,previm)`
- `gifAnimEnd()`

Text extras:
- `char/charUp` — single character
- `stringFTCircle(...)` — text on circular path
- `useFontConfig(flag)`
- Font `load(fontpath)` — custom bitmap font

Uncommon formats (optional, needs TwelveMonkeys ImageIO plugin):
- `newFromTiff/tiff` — add `com.twelvemonkeys.imageio:imageio-tiff`
- `newFromWebp/webp` — add `com.twelvemonkeys.imageio:imageio-webp`
- `newFromXbm` — custom parser (simple text format)
- `newFromBmp/bmpData` — already in JDK, add in Phase 1 if trivial

Stubs (not implementable in pure Java):
- `newFromHeif/newFromAvif/heif/avif` — die with clear error
- `newFromGd/newFromGd2/gd/gd2` — die "deprecated GD format not supported"
- `newFromXpm` — die with clear error

---

## Key Implementation Challenges

### 1. Flood Fill

No built-in flood fill in AWT. Implement as BFS on `BufferedImage` pixel
data (~30 lines). Both `fill()` and `fillToBorder()` variants needed.

### 2. Palette Image Mutations

Java's `IndexColorModel` is immutable. When `colorAllocate()` adds a new
color, rebuild the color model and create a new `BufferedImage`. Cache the
palette as a `List<Color>` and rebuild only when colors change.

### 3. GD's Brush Mode

GD stamps a brush image at each pixel along a drawn line. Java's
`TexturePaint` tiles instead. Implement by iterating Bresenham line points
and drawing the brush at each position.

### 4. Special Color Constants

`gdBrushed`, `gdStyled`, `gdTiled`, etc. are negative "magic" color
values. Drawing functions must check for these and switch rendering mode
accordingly (brush stamp, style dash, tile fill).

### 5. GIF Animation Metadata

Java's GIF `ImageWriter` supports animation but requires manipulating
`IIOMetadata` DOM trees — verbose (~50 lines per frame). Well-documented
pattern.

### 6. Built-in Bitmap Fonts

GD's 5 bitmap fonts have specific pixel dimensions. Options:
- **Quick:** Map to `java.awt.Font("Monospaced", PLAIN, n)` with
  approximate sizes (not pixel-perfect)
- **Exact:** Embed the bitmap font data from GD's C source as static
  byte arrays and render manually

---

## Downstream Consumers

Modules that depend on GD and would become usable:

| Module | Use case |
|--------|----------|
| `GD::Graph` | Chart generation (bars, lines, pie, area) |
| `GD::Text` | Text utilities for GD::Graph |
| `GD::Barcode` | Barcode generation |
| `GD::SecurityImage` | CAPTCHA generation |
| `GD::Thumbnail` | Image thumbnails |
| `GD::SVG` | SVG output using GD API |
| `PDF::API2::Resource::XObject::Image::GD` | PDF image embedding |
| `Bio::Graphics` | Bioinformatics visualization |
| `Chart` | Charting library |

---

## Files

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/perlmodule/GD.java` | Java XS implementation |
| `src/main/perl/lib/GD.pm` | Main module (adapted from CPAN) |
| `src/main/perl/lib/GD/Image.pm` | Pure Perl image dispatchers (from CPAN) |
| `src/main/perl/lib/GD/Polygon.pm` | Pure Perl polygon class (from CPAN) |
| `src/main/perl/lib/GD/Polyline.pm` | Pure Perl polyline class (from CPAN) |
| `src/main/perl/lib/GD/Simple.pm` | Pure Perl turtle graphics (from CPAN) |
| `src/main/perl/lib/GD/Group.pm` | Pure Perl GD::SVG compat (from CPAN) |
| `src/test/resources/module/GD/t/` | Tests from CPAN distribution |

---

## Dependencies

**Runtime (pure Perl, should already work via jcpan):**
- `Math::Trig` — used by `GD::Simple` for polar coordinates

**No new Maven/Gradle dependencies** for Phase 1 and 2.

**Phase 3 optional (uncommon formats):**
- `com.twelvemonkeys.imageio:imageio-tiff:3.12.0` — TIFF support
- `com.twelvemonkeys.imageio:imageio-webp:3.12.0` — WebP support

---

## Progress Tracking

### Current Status: Not started

### Phases
- [ ] Phase 1: Core MVP (image create, draw, color, PNG/JPEG/GIF output)
- [ ] Phase 2: Copy, merge, full drawing, brushes, style
- [ ] Phase 3: Filters, transforms, GIF animation, extra formats

### Next Steps
1. Create `GD.java` with Phase 1 functions
2. Copy and adapt `GD.pm` and pure Perl files from CPAN
3. Write `basic.t` test
4. Run `make` to verify no regressions
5. Test with `GD::Graph` as validation target

---

## Related Documents

- `dev/modules/image_magick.md` — Image::Magick CLI wrapper (different approach)
- `dev/modules/xs_fallback.md` — XS fallback mechanism (Phase 1-2 used by DateTime)
- `.agents/skills/port-cpan-module/SKILL.md` — Module porting skill
