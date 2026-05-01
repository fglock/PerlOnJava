# Gtk2 Module for PerlOnJava

## Overview

Provide a working `Gtk2` module for PerlOnJava by implementing the XS layer in
Java, backed by **JavaFX** (available from Maven Central), so that
`./jcpan -t Gtk2` can run the upstream test suite on any platform where the JDK
is present.

**Author:** Torsten Schoenfeld et al. (gtk2-perl project)  
**CPAN version:** 1.24999 (latest stable)  
**License:** LGPL-2.1  
**CPAN dist:** https://metacpan.org/dist/Gtk2

---

## Background: What `Gtk2` Actually Is

The `Gtk2` CPAN distribution is an auto-generated XS binding to the GTK+ 2 C
library, produced by `glib-perl`'s code-generator from GObject introspection
metadata. Key characteristics:

- ~500 widget/object types (`Gtk2::Window`, `Gtk2::Button`, `Gtk2::TreeView`, …)
- ~3,000 XS functions
- Depends on `Glib` (also XS), `Pango`, `Cairo`, `GDK`
- GObject reference-counting, signals, properties, type system
- A **blocking event loop**: `Gtk2->main` hands control to the GLib/GTK2
  event dispatcher until `Gtk2->main_quit` is called

There is no `Gtk2::PP` fallback. Without a Java XS stub the first
`use Gtk2` dies with `"Can't load loadable object"`.

---

## Implementation Strategy: JavaFX-Backed Shim

### Why JavaFX, Not Direct GTK2 Bindings via FFM

Two approaches are possible:

| Approach | Pros | Cons |
|----------|------|------|
| **JavaFX shim** (recommended) | Pure Java, no GTK2 installed, cross-platform (macOS/Windows/Linux), headless test support via Monocle | APIs are not 1:1; mapping effort for large widget set |
| **FFM direct to libgtk-2.0** | Exact GTK2 semantics, no mapping | GTK2 must be installed, Linux/macOS only, ~3,000 functions to bind, event loop threading hazards |

The JavaFX approach follows the same pattern as `XML::Parser` (Java SAX
instead of libexpat) and `GD` (Java AWT instead of libgd): **replace the C
backend with an equivalent Java library that ships with the JDK or is available
on Maven Central.**

JavaFX is available from Maven Central as `org.openjfx:javafx-*:21`. It is
cross-platform, supports a headless `Monocle` backend for CI, and has a widget
hierarchy that maps naturally to GTK2:

| GTK2 concept | JavaFX equivalent |
|---|---|
| `GtkWidget` | `javafx.scene.Node` |
| `GtkContainer` | `javafx.scene.layout.Pane` |
| `GtkWindow` / `GtkDialog` | `javafx.stage.Stage` |
| `GtkVBox` / `GtkHBox` | `VBox` / `HBox` |
| `GtkGrid` / `GtkTable` | `GridPane` |
| `GtkButton` | `Button` |
| `GtkLabel` | `Label` |
| `GtkEntry` | `TextField` |
| `GtkTextView` | `TextArea` |
| `GtkCheckButton` | `CheckBox` |
| `GtkRadioButton` | `RadioButton` |
| `GtkComboBox` | `ComboBox` |
| `GtkScrolledWindow` | `ScrollPane` |
| `GtkNotebook` | `TabPane` |
| `GtkMenuBar` / `GtkMenu` / `GtkMenuItem` | `MenuBar` / `Menu` / `MenuItem` |
| `GtkFileChooserDialog` | `FileChooser` |
| `GtkColorChooserDialog` | `ColorPicker` |
| `GtkProgressBar` | `ProgressBar` |
| `GtkSpinButton` | `Spinner` |
| `GtkDrawingArea` | `Canvas` |
| GLib signal | JavaFX event handler / `ObjectProperty.addListener()` |
| GObject property | JavaFX `Property` / `SimpleStringProperty`, etc. |
| `GtkMainLoop` | JavaFX Application Thread + `CountDownLatch` |

---

## Architecture

### File Layout

```
src/main/
├── perl/lib/
│   ├── Gtk2.pm                          # Loader + class hierarchy glue
│   ├── Gtk2/
│   │   ├── Widget.pm                    # Base widget methods (pure Perl)
│   │   ├── Container.pm                 # Container methods (pure Perl)
│   │   ├── Window.pm                    # Window-specific methods
│   │   └── ... (thin PM shims per class)
└── java/org/perlonjava/runtime/perlmodule/
    └── Gtk2.java                        # Java XS for all GTK2 functions

src/test/resources/module/
└── Gtk2/
    └── t/
        ├── basic.t                      # Core widgets smoke test
        ├── signals.t                    # Signal connect/emit
        ├── properties.t                 # GObject property get/set
        └── layout.t                     # Container/layout tests
```

### Object Model

A `Gtk2::Widget` (and all subclasses) is a **blessed scalar reference** in
Perl. The scalar holds an opaque integer handle that maps into a Java-side
`HashMap<Integer, Node>` (or `Stage` for windows) maintained inside `Gtk2.java`.

```java
// In Gtk2.java — widget registry
private static final Map<Integer, Object> widgetRegistry = new ConcurrentHashMap<>();
private static final AtomicInteger nextHandle = new AtomicInteger(1);

static int registerWidget(Object fxNode) {
    int handle = nextHandle.getAndIncrement();
    widgetRegistry.put(handle, fxNode);
    return handle;
}

static Object getWidget(RuntimeScalar perlObj) {
    int handle = ((RuntimeScalar) perlObj.deref()).getInt();
    return widgetRegistry.get(handle);
}
```

`Gtk2::Window->new('toplevel')` creates a JavaFX `Stage`, registers it, and
returns a `RuntimeScalar` holding the handle, blessed into `Gtk2::Window`.

### The Event Loop

This is the central architectural challenge. GTK2's `Gtk2->main()` blocks the
calling Perl thread. JavaFX requires all UI operations on its own Application
Thread.

**Solution: dedicated FX thread + synchronous dispatch queue.**

```
Perl main thread                         JavaFX Application Thread
─────────────────                        ─────────────────────────────
Gtk2->init                    ──────>    Platform.startup(() -> {})
Gtk2::Window->new             ──────>    CompletableFuture<Stage> + runLater
                              <──────    handle (waits synchronously)
$win->show_all                ──────>    Platform.runLater(stage::show)
Gtk2->main           blocks   ──────>    mainLatch.await()
                              <──────    (event loop running in FX thread)
$btn signal 'clicked' fires              [user clicks button]
  callback runs in Perl                  Platform.runLater triggers Perl callback
Gtk2->main_quit               ──────>    mainLatch.countDown()
Gtk2->main           returns
```

```java
// Gtk2.java — skeleton

private static CountDownLatch mainLatch = new CountDownLatch(1);
private static volatile boolean fxStarted = false;

// Gtk2->init or `use Gtk2 -init`
public static RuntimeList init(RuntimeArray args, int ctx) {
    if (!fxStarted) {
        fxStarted = true;
        Platform.startup(() -> {});   // starts FX thread, returns immediately
        Platform.setImplicitExit(false);
    }
    return new RuntimeList();
}

// Gtk2->main — blocks until main_quit
public static RuntimeList main(RuntimeArray args, int ctx) {
    mainLatch = new CountDownLatch(1);
    try { mainLatch.await(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    return new RuntimeList();
}

// Gtk2->main_quit
public static RuntimeList main_quit(RuntimeArray args, int ctx) {
    mainLatch.countDown();
    return new RuntimeList();
}

// Helper — run on FX thread and block until done
static <T> T runOnFXThread(Callable<T> task) {
    if (Platform.isFxApplicationThread()) {
        try { return task.call(); } catch (Exception e) { throw new RuntimeException(e); }
    }
    CompletableFuture<T> future = new CompletableFuture<>();
    Platform.runLater(() -> {
        try { future.complete(task.call()); }
        catch (Throwable e) { future.completeExceptionally(e); }
    });
    try { return future.get(30, TimeUnit.SECONDS); }
    catch (Exception e) { throw new RuntimeException(e); }
}
```

All widget-creation and manipulation methods call `runOnFXThread(...)`.

### Signal System

GTK2 GLib signals are Perl coderefs attached via `signal_connect`. JavaFX
uses event handler lambdas. The bridge:

```java
// $widget->signal_connect('clicked' => \&callback)
public static RuntimeList signal_connect(RuntimeArray args, int ctx) {
    int handle = args.get(0).getInt();       // $self (handle integer)
    String signal = args.get(1).toString();  // signal name
    RuntimeScalar callback = args.get(2);    // Perl coderef

    Object node = widgetRegistry.get(handle);
    connectSignal(node, signal, callback);
    // Returns a handler ID (integer) — used by signal_handler_disconnect
    return new RuntimeScalar(signalHandlerId.getAndIncrement()).getList();
}

private static void connectSignal(Object node, String signal, RuntimeScalar cb) {
    if (node instanceof Button btn) {
        switch (signal) {
            case "clicked" -> btn.setOnAction(e -> invokePerlCallback(cb));
            case "focus-in-event" -> btn.focusedProperty().addListener(
                (obs, old, now) -> { if (now) invokePerlCallback(cb); });
            // ... more signals
        }
    } else if (node instanceof Stage stage) {
        switch (signal) {
            case "delete-event" -> stage.setOnCloseRequest(e -> {
                invokePerlCallback(cb);
                e.consume();   // Perl handler decides whether to destroy
            });
            case "destroy" -> stage.setOnHiding(e -> invokePerlCallback(cb));
        }
    }
    // ... other node types
}

private static void invokePerlCallback(RuntimeScalar cb) {
    // Run Perl coderef from FX thread — must schedule on Perl main thread
    // Use a shared queue that Gtk2->main drains
    callbackQueue.add(cb);
}
```

**Callback execution model:** `Gtk2->main()` does not simply block — it also
drains a `LinkedBlockingQueue<RuntimeScalar>` of pending Perl callbacks that the
FX thread queued up from signal emissions. This keeps Perl code running on the
Perl main thread (avoiding JVM thread-safety issues with the interpreter).

```java
public static RuntimeList main(RuntimeArray args, int ctx) {
    mainLatch = new CountDownLatch(1);
    while (true) {
        try {
            RuntimeScalar cb = callbackQueue.poll(50, TimeUnit.MILLISECONDS);
            if (cb != null) {
                RuntimeArray cbArgs = new RuntimeArray();
                cb.apply(cbArgs, RuntimeContextType.VOID);
            }
        } catch (InterruptedException e) { break; }
        if (mainLatch.getCount() == 0) break;
    }
    return new RuntimeList();
}
```

### GObject Properties

`$widget->get('title')` and `$widget->set(title => 'My App')` map to JavaFX
bean properties:

```java
public static RuntimeList get_property(RuntimeArray args, int ctx) {
    Object node = getWidget(args.get(0));
    String prop = args.get(1).toString();
    return switch (prop) {
        case "title"   -> { yield node instanceof Stage s
                                ? new RuntimeScalar(s.getTitle()).getList()
                                : new RuntimeList(); }
        case "visible" -> new RuntimeScalar(((Node)node).isVisible() ? 1 : 0).getList();
        case "width"   -> new RuntimeScalar((int)((Stage)node).getWidth()).getList();
        // ...
        default -> new RuntimeList();
    };
}
```

---

## Cross-Platform Support

JavaFX is available on all three target platforms.  Each has its own concerns.

| Platform | JavaFX backend | Notes |
|----------|---------------|-------|
| **Linux x86_64** | GTK3 (native peer) | Requires `libgtk-3.so`, `libpango-1.0.so`, `libfreetype.so`, `libXtst.so` at runtime. All present on a standard desktop; absent on minimal servers — use Monocle headless there. |
| **macOS x86_64 (Intel)** | Quartz / Metal | Works out of the box. Classifier: `mac`. |
| **macOS aarch64 (Apple Silicon M1/M2/M3)** | Quartz / Metal | Separate Maven artifact. Classifier: **`mac-aarch64`**, not `mac`. Auto-detected by `javafxplugin`. |
| **Windows x86_64** | Direct3D / GDI | All native DLLs ship inside the JavaFX JARs; no extra system installs. Classifier: `win`. |

JavaFX does **not** require `-XstartOnFirstThread` on macOS (that is an SWT
requirement). `Platform.startup()` starts the FX Application Thread as a daemon
thread and returns immediately to the calling thread — safe to call from the
JVM main thread that PerlOnJava runs Perl on.

---

## Gradle/Maven Changes

### The `shadowJar` Constraint

PerlOnJava builds a fat JAR via the Gradle Shadow plugin (`shadowJar`).
JavaFX JARs **cannot** be merged into a fat JAR because:

1. Each JavaFX JAR contains a `module-info.class` in its root. Shadow merges
   all JARs by copying files; duplicate `module-info.class` entries cause
   `IllegalStateException: module-info.class found in multiple JARs`.
2. JavaFX's security model depends on JPMS encapsulation which is enforced
   per-JAR, not per merged blob.
3. Native libraries (`.so` / `.dylib` / `.dll`) inside JavaFX JARs are
   extracted by the JavaFX bootstrap code using JPMS resource lookup — this
   lookup path breaks when the JARs are merged.

**Solution: `compileOnly` dependency + runtime `--module-path`.**

JavaFX is added as `compileOnly` so `Gtk2.java` compiles against the JavaFX
API. It is **not** merged into `perlonjava.jar`. At runtime, users who want
`Gtk2` must have JavaFX JARs on the `--module-path`. A `jperl-gtk` wrapper
script handles this automatically (see below).

```groovy
// build.gradle — compile-time only; NOT merged into shadowJar
def fxVersion = "21"
def fxClassifier = {
    def arch = System.getProperty("os.arch")
    def os   = System.getProperty("os.name").toLowerCase()
    if (os.contains("mac"))  return arch == "aarch64" ? "mac-aarch64" : "mac"
    if (os.contains("win"))  return "win"
    return "linux"
}()

compileOnly "org.openjfx:javafx-controls:${fxVersion}:${fxClassifier}"
compileOnly "org.openjfx:javafx-graphics:${fxVersion}:${fxClassifier}"
compileOnly "org.openjfx:javafx-base:${fxVersion}:${fxClassifier}"
```

At install time, the build also downloads the JavaFX JARs to a known location
so `jperl-gtk` can find them:

```groovy
// Separate configuration to resolve JavaFX JARs to a directory
configurations { javafxRuntime }
dependencies {
    javafxRuntime "org.openjfx:javafx-controls:${fxVersion}:${fxClassifier}"
    javafxRuntime "org.openjfx:javafx-graphics:${fxVersion}:${fxClassifier}"
    javafxRuntime "org.openjfx:javafx-base:${fxVersion}:${fxClassifier}"
}
tasks.register('copyJavaFX', Copy) {
    from configurations.javafxRuntime
    into "$buildDir/../lib/javafx"
}
build.dependsOn copyJavaFX
```

### `jperl-gtk` Wrapper Script

A new launcher (`jperl-gtk` / `jperl-gtk.bat`) wraps `jperl` and adds the
JavaFX module path. Users run `jperl-gtk mygui.pl` instead of `jperl mygui.pl`
for any script that uses `Gtk2`.

```bash
#!/bin/bash
# jperl-gtk — jperl with JavaFX modules for Gtk2 support
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
FX_LIBS="$SCRIPT_DIR/lib/javafx"

exec "$SCRIPT_DIR/jperl" \
    --module-path "$FX_LIBS" \
    --add-modules javafx.controls,javafx.graphics,javafx.base \
    "$@"
```

```bat
@echo off
rem jperl-gtk.bat — jperl with JavaFX for Gtk2
set SCRIPT_DIR=%~dp0
set FX_LIBS=%SCRIPT_DIR%lib\javafx
java %JVM_OPTS% %JPERL_OPTS% ^
  --module-path "%FX_LIBS%" ^
  --add-modules javafx.controls,javafx.graphics,javafx.base ^
  -cp "%SCRIPT_DIR%target\perlonjava-5.42.0.jar" ^
  org.perlonjava.app.cli.Main %*
```

`jcpan` also needs to use `jperl-gtk` when testing Gtk2:

```bash
# Distroprefs override for Gtk2 (in CPAN/distroprefs/Gtk2.yml)
# Tells jcpan to run tests with jperl-gtk instead of jperl
```

Alternatively, `jperl` itself detects at startup that the `Gtk2` module was
required and re-execs itself with the `--module-path` flag if JavaFX JARs are
present in `lib/javafx/` — transparent to users.

### Graceful Degradation

`Gtk2.java`'s `initialize()` detects whether JavaFX is available at runtime:

```java
public static void initialize() {
    try {
        Class.forName("javafx.application.Platform");
    } catch (ClassNotFoundException e) {
        // JavaFX not on module path — register nothing
        // XSLoader will fail with "Can't load loadable object" as usual
        return;
    }
    // ... register methods
}
```

No JavaFX → standard `"Can't load loadable object"` error → scripts with a
`Gtk2::PP` fallback degrade gracefully. Server deployments without any GUI
dependency are completely unaffected.

**Note:** JavaFX is optional. If the jars are absent (server-only deployments),
`Gtk2.java`'s `initialize()` checks `Class.forName("javafx.application.Platform")`
and skips registration — `use Gtk2` will then fail with `"Can't load loadable
object"` (the standard XS-absent error), which is the correct behavior.

---

## Headless Testing

The Gtk2 test suite requires a display. On CI (no X11/Wayland) and during
`./jcpan -t Gtk2`, use JavaFX's `Monocle` headless backend:

```bash
# Run with headless FX
JAVA_TOOL_OPTIONS="-Djava.awt.headless=true \
  -Dprism.order=sw \
  -Dglass.platform=Monocle \
  -Dmonocle.platform=Headless" \
  ./jcpan -t Gtk2
```

Monocle is included in recent OpenJFX distributions. As a fallback, `Xvfb`
(virtual framebuffer) also works:

```bash
Xvfb :99 -screen 0 1024x768x24 &
DISPLAY=:99 ./jcpan -t Gtk2
```

Test files that call `Gtk2->init` should detect the headless environment and
skip display-dependent tests when neither a display nor Monocle is available:

```perl
BEGIN {
    unless ($ENV{DISPLAY} || $ENV{WAYLAND_DISPLAY}
         || $ENV{MONOCLE_HEADLESS}) {
        plan skip_all => 'No display available';
    }
}
```

---

## Widget API Inventory

The full Gtk2 API is ~3,000 functions. For Phase 1, approximately 80 functions
covering the 12 most common widget types are sufficient to run the majority of
real-world Gtk2 programs and a meaningful subset of the upstream tests.

### Phase 1 — Core Widget Set (~80 functions)

#### Class Methods / Module-Level

| Perl call | Java implementation |
|-----------|---------------------|
| `Gtk2->init` / `use Gtk2 -init` | `Platform.startup(() -> {})` |
| `Gtk2->init_check` | Same, returns 1 |
| `Gtk2->main` | Drain callback queue + `mainLatch.await()` |
| `Gtk2->main_quit` | `mainLatch.countDown()` |
| `Gtk2->main_level` | Return nesting depth (integer) |
| `Gtk2->events_pending` | `Platform.isFxApplicationThread() ? 0 : 1` |
| `Gtk2->main_iteration` | Process one pending callback |
| `Gtk2->main_iteration_do($blocking)` | Process one callback, optional block |
| `Gtk2->get_current_event_time` | `System.currentTimeMillis()` |

#### `Gtk2::Widget` (base class)

| Perl method | JavaFX mapping |
|-------------|----------------|
| `show()` | `node.setVisible(true)` |
| `hide()` | `node.setVisible(false)` |
| `show_all()` | Recursively show all children |
| `destroy()` | Remove from registry; `stage.close()` if Stage |
| `grab_focus()` | `node.requestFocus()` |
| `is_sensitive()` | `!node.isDisabled()` |
| `set_sensitive($bool)` | `node.setDisable(!$bool)` |
| `get_size_request()` | `(node.getPrefWidth(), node.getPrefHeight())` |
| `set_size_request($w, $h)` | `node.setPrefSize($w, $h)` |
| `get_allocation()` | `(x, y, width, height)` from layout bounds |
| `signal_connect($sig, \&cb)` | `connectSignal(node, $sig, $cb)` |
| `signal_handler_disconnect($id)` | Remove handler from registry |
| `get_parent()` | Return handle of parent node |
| `set_tooltip_text($text)` | `Tooltip.install(node, new Tooltip($text))` |
| `get_name()` / `set_name($s)` | `node.getId()` / `node.setId($s)` |

#### `Gtk2::Container`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `add($child)` | `pane.getChildren().add(childNode)` |
| `remove($child)` | `pane.getChildren().remove(childNode)` |
| `get_children()` | Return list of child handles |
| `foreach($func)` | Iterate children, call $func |
| `set_border_width($n)` | `pane.setPadding(new Insets($n))` |
| `get_border_width()` | `pane.getPadding().getTop()` |

#### `Gtk2::Window`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($type)` | `new Stage()` + register |
| `set_title($s)` | `stage.setTitle($s)` |
| `get_title()` | `stage.getTitle()` |
| `set_default_size($w, $h)` | `stage.setWidth($w); stage.setHeight($h)` |
| `resize($w, $h)` | Same |
| `get_size()` | `(stage.getWidth(), stage.getHeight())` |
| `move($x, $y)` | `stage.setX($x); stage.setY($y)` |
| `set_resizable($bool)` | `stage.setResizable($bool)` |
| `maximize()` | `stage.setMaximized(true)` |
| `fullscreen()` | `stage.setFullScreen(true)` |
| `set_modal($bool)` | `stage.initModality(WINDOW_MODAL)` |
| `set_transient_for($parent)` | `stage.initOwner(parentStage)` |
| `present()` | `stage.toFront()` |

#### `Gtk2::VBox` / `Gtk2::HBox`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($homogeneous, $spacing)` | `new VBox($spacing)` / `new HBox($spacing)` |
| `pack_start($child, $expand, $fill, $pad)` | `getChildren().add(child)` + `VBox.setVgrow` |
| `pack_end($child, $expand, $fill, $pad)` | Insert at index 0 with same logic |
| `set_spacing($n)` | `box.setSpacing($n)` |
| `get_spacing()` | `box.getSpacing()` |

#### `Gtk2::Button`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($label)` | `new Button($label)` |
| `new_with_mnemonic($label)` | Same (strip leading `_`) |
| `new_from_stock($stock_id)` | Map stock IDs to Button with label |
| `get_label()` | `button.getText()` |
| `set_label($s)` | `button.setText($s)` |
| `clicked()` | Fire `ActionEvent` on button |
| `set_relief($relief)` | `button.setStyle(...)` |
| Signals: `clicked` | `button.setOnAction(...)` |

#### `Gtk2::Label`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($text)` | `new Label($text)` |
| `new_with_mnemonic($text)` | Same |
| `get_text()` | `label.getText()` |
| `set_text($s)` | `label.setText($s)` |
| `set_markup($s)` | Parse Pango markup → CSS / `TextFlow` |
| `set_line_wrap($bool)` | `label.setWrapText($bool)` |
| `set_justify($justify)` | `label.setTextAlignment(...)` |
| `get_layout_offsets()` | `(0, 0)` (stub) |

#### `Gtk2::Entry`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new()` | `new TextField()` |
| `get_text()` | `field.getText()` |
| `set_text($s)` | `field.setText($s)` |
| `set_visibility($bool)` | `true` → `TextField`, `false` → `PasswordField` |
| `set_max_length($n)` | `TextFormatter` with length limit |
| `set_editable($bool)` | `field.setEditable($bool)` |
| `select_region($start, $end)` | `field.selectRange($start, $end)` |
| `get_position()` | `field.getCaretPosition()` |
| Signals: `changed`, `activate` | Property listener / `setOnAction` |

#### `Gtk2::CheckButton` / `Gtk2::ToggleButton`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($label)` | `new CheckBox($label)` |
| `get_active()` | `cb.isSelected()` |
| `set_active($bool)` | `cb.setSelected($bool)` |
| Signals: `toggled` | `selectedProperty().addListener(...)` |

#### `Gtk2::ComboBox`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new()` | `new ComboBox<String>()` |
| `new_text()` | Same |
| `append_text($s)` | `cb.getItems().add($s)` |
| `get_active_text()` | `cb.getValue()` |
| `set_active($idx)` | `cb.getSelectionModel().select($idx)` |
| `get_active()` | `cb.getSelectionModel().getSelectedIndex()` |
| Signals: `changed` | `valueProperty().addListener(...)` |

#### `Gtk2::TextView` / `Gtk2::TextBuffer`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `Gtk2::TextView->new()` | `new TextArea()` |
| `get_buffer()` | Return a `Gtk2::TextBuffer` handle wrapping the same `TextArea` |
| `Gtk2::TextBuffer->new()` | Internal; wraps `StringProperty` |
| `$buf->set_text($s)` | `area.setText($s)` |
| `$buf->get_text($start,$end,$include_hidden)` | `area.getText()` |
| `$buf->insert_at_cursor($s)` | `area.insertText(caretPos, $s)` |
| `$buf->get_char_count()` | `area.getText().length()` |

#### `Gtk2::ScrolledWindow`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($hadjust, $vadjust)` | `new ScrollPane()` |
| `add($child)` | `sp.setContent(childNode)` |
| `set_policy($hp, $vp)` | `sp.setHbarPolicy(...)` / `sp.setVbarPolicy(...)` |

#### `Gtk2::Dialog` / `Gtk2::MessageDialog`

| Perl method | JavaFX mapping |
|-------------|----------------|
| `new($title,$parent,$flags,@buttons)` | `new Stage()` + `ButtonType` setup |
| `run()` | `showAndWait()` (blocks), return response ID |
| `Gtk2::MessageDialog->new(...)` | `new Alert(AlertType....)` |

---

## Implementation Phases

### Phase 1: Event Loop + Core Widgets

**Goal:** `use Gtk2; Gtk2->init; Gtk2::Window->new; $w->show_all; Gtk2->main`
works, buttons and labels render, signals fire. Enough to run the most common
real-world Gtk2 scripts.

**Files to create:**

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Gtk2.java` | All Java XS (~1,500 lines) |
| `src/main/perl/lib/Gtk2.pm` | Module loader with class hierarchy |
| `src/test/resources/module/Gtk2/t/basic.t` | Smoke: init, window, button, label, loop |
| `src/test/resources/module/Gtk2/t/signals.t` | Signal connect/disconnect |

**Functions to implement (Phase 1 subset — ~80):**

- Module: `init`, `init_check`, `main`, `main_quit`, `main_level`,
  `events_pending`, `main_iteration`, `main_iteration_do`
- Widget: `show`, `hide`, `show_all`, `destroy`, `grab_focus`,
  `is_sensitive`, `set_sensitive`, `signal_connect`, `signal_handler_disconnect`,
  `set_size_request`, `get_size_request`, `set_tooltip_text`, `get_name`, `set_name`
- Container: `add`, `remove`, `get_children`, `set_border_width`
- Window: `new`, `set_title`, `get_title`, `set_default_size`, `resize`,
  `get_size`, `move`, `set_resizable`, `maximize`, `set_modal`, `present`
- VBox/HBox: `new`, `pack_start`, `pack_end`, `set_spacing`
- Button: `new`, `new_with_mnemonic`, `new_from_stock`, `get_label`,
  `set_label`, `clicked`
- Label: `new`, `new_with_mnemonic`, `get_text`, `set_text`, `set_markup`,
  `set_line_wrap`, `set_justify`
- Entry: `new`, `get_text`, `set_text`, `set_visibility`, `set_editable`
- CheckButton/ToggleButton: `new`, `get_active`, `set_active`
- ComboBox: `new`, `new_text`, `append_text`, `get_active_text`, `set_active`, `get_active`

**Signals to support (Phase 1):**

| Widget | Signals |
|--------|---------|
| All widgets | `show`, `hide`, `destroy`, `focus-in-event`, `focus-out-event` |
| Window | `delete-event`, `destroy`, `configure-event`, `key-press-event` |
| Button | `clicked`, `pressed`, `released` |
| Entry | `changed`, `activate`, `key-press-event` |
| CheckButton | `toggled` |
| ComboBox | `changed` |

**build.gradle changes:**

```groovy
// Apply the JavaFX plugin
plugins {
    id 'org.openjfx.javafxplugin' version '0.1.0'
}

javafx {
    version = "21"
    modules = ['javafx.controls', 'javafx.graphics', 'javafx.base']
}
```

**Verify:**

```bash
make
./jperl -e '
    use Gtk2;
    Gtk2->init;
    my $win = Gtk2::Window->new("toplevel");
    $win->set_title("Hello PerlOnJava");
    my $btn = Gtk2::Button->new("Quit");
    $btn->signal_connect(clicked => sub { Gtk2->main_quit });
    $win->add($btn);
    $win->show_all;
    Gtk2->main;
'

# Run module tests headless
JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dglass.platform=Monocle \
  -Dmonocle.platform=Headless" \
  make test-bundled-modules JPERL_TEST_FILTER=Gtk2
```

---

### Phase 2: Layout, Text, Menus, Dialogs

**Goal:** Cover enough of the widget set to run the upstream Gtk2 test suite's
non-display tests and the most common GUI application patterns.

**Additional functions (~120):**

- `Gtk2::Grid` / `Gtk2::Table`: `attach`, `set_row_spacing`, `set_col_spacing`
- `Gtk2::Frame`: `new`, `set_label`, `set_shadow_type`
- `Gtk2::Expander`: `new`, `set_expanded`, `get_expanded`
- `Gtk2::Notebook`: `append_page`, `set_current_page`, `get_current_page`
- `Gtk2::TextView` / `Gtk2::TextBuffer`: full text manipulation
- `Gtk2::ScrolledWindow`: policy variants
- `Gtk2::MenuBar`, `Gtk2::Menu`, `Gtk2::MenuItem`, `Gtk2::ImageMenuItem`,
  `Gtk2::SeparatorMenuItem`: full menu hierarchy
- `Gtk2::Dialog`: `run`, `response`, `add_button`, `get_action_area`,
  `get_content_area`
- `Gtk2::MessageDialog`: `new`, info/warning/error/question variants
- `Gtk2::FileChooserDialog`: `new`, `get_filename`, `set_filename`
- `Gtk2::AboutDialog`: `new`, `set_name`, `set_version`, `set_copyright`
- `Gtk2::ProgressBar`: `new`, `set_fraction`, `set_text`, `pulse`
- `Gtk2::Spinner`: `new`, `set_value`, `get_value`
- `Gtk2::Image`: `new_from_file`, `new_from_stock`, `new_from_pixbuf`
- `Gtk2::Pixbuf`: `new_from_file`, `get_width`, `get_height`, `scale_simple`
- `Gtk2::DrawingArea`: `new` + expose-event signal
- `Gtk2::Adjustment`: `new`, `get_value`, `set_value`
- `Gtk2::HScale` / `Gtk2::VScale`: `new`, `set_value`, `get_value`
- `Gtk2::Paned`: `add1`, `add2`, `set_position`

**Additional signals:**

| Widget | Signals |
|--------|---------|
| DrawingArea | `expose-event`, `configure-event` |
| Scale | `value-changed` |
| Notebook | `switch-page` |
| TreeView | `row-activated`, `cursor-changed` |

---

### Phase 3: TreeView, ListStore, CellRenderer

**Goal:** Support the most complex widget in common Gtk2 usage — `Gtk2::TreeView`
backed by `Gtk2::ListStore` or `Gtk2::TreeStore`. These are used in file managers,
data grids, and nearly every non-trivial Gtk2 application.

`Gtk2::TreeView` maps to `javafx.scene.control.TableView` (for list data) or
`TreeTableView` (for hierarchical data).

**Additional functions (~80):**

- `Gtk2::ListStore`: `new`, `append`, `set`, `get`, `remove`, `clear`,
  `iter_is_valid`, `get_iter_first`, `iter_next`
- `Gtk2::TreeStore`: same + `append` with parent iter
- `Gtk2::TreeView`: `new`, `set_model`, `append_column`, `get_selection`,
  `get_model`, `expand_all`, `collapse_all`
- `Gtk2::TreeViewColumn`: `new`, `set_title`, `pack_start`, `add_attribute`,
  `set_sort_column_id`
- `Gtk2::CellRendererText`, `Gtk2::CellRendererToggle`,
  `Gtk2::CellRendererPixbuf`: `new`, attribute mapping
- `Gtk2::TreeSelection`: `get_selected`, `set_mode`, `get_selected_rows`
- `Gtk2::TreeIter`: wrapper for JavaFX `TableView.getItems()` indices
- `Gtk2::TreePath`: `new`, `to_string`, `get_indices`

---

### Phase 4: Upstream Test Suite Parity

**Goal:** `./jcpan -t Gtk2` passes (or cleanly skips) all tests in the upstream
Gtk2 CPAN distribution's `t/` directory.

The upstream test suite has ~90 `.t` files covering:
- Object construction for all widget types
- Property get/set round-trips
- Signal emission and handler invocation
- Iterator protocols (TreeModel)
- Type introspection (`isa`, `get_type`)
- Deprecated API compatibility

Many tests call `Gtk2->init` and then immediately test widget properties
without actually running a GUI loop — these are the easiest to pass.

**Strategy:**
1. Run `./jcpan -t Gtk2` under Monocle headless
2. Categorize failures: missing function vs wrong value vs crash
3. Implement the missing functions in priority order
4. Accept `TODO` skip annotations for irreducibly display-dependent tests

---

## Risk Analysis

| Area | Risk | Notes |
|------|------|-------|
| Event loop threading | Medium | `runOnFXThread` synchronous dispatch adds latency; deep recursion from signal-in-signal could deadlock |
| Callback queue draining | Medium | `Gtk2->main` must drain Perl callbacks between FX events; timing-sensitive |
| GObject property reflection | Low | JavaFX properties map 1:1 for common types; rare type mismatches |
| TreeView / ListStore | Medium | TableView model API differs significantly; requires index-based iter emulation |
| Cairo / Pango integration | High | `Gtk2::DrawingArea` + `Gtk2::Pango::*` relies on Cairo; JavaFX Canvas replaces it but API is very different |
| `Glib` module (required) | Medium | `use Gtk2` depends on `use Glib`; a minimal `Glib.pm` stub registering `Glib::Object` is needed |
| JavaFX absent in JRE | Low | Graceful fail with standard "Can't load loadable object" |
| `GDK` functions | Low | Only basic window geometry needed for Phase 1 |

---

## `Glib` Dependency

`Gtk2.pm` requires `Glib` before it can load (`use Glib`). `Glib` is a
separate CPAN XS module (the GLib binding). For Phase 1, a minimal bundled stub
is sufficient:

```perl
# src/main/perl/lib/Glib.pm  (stub)
package Glib;

our $VERSION = '1.329';

# Provide the base blessed-object constructor that Gtk2 expects
package Glib::Object;
sub new { my $class = shift; bless { _handle => 0 }, $class }
sub isa { UNIVERSAL::isa(@_) }

1;
```

The `Glib.java` Java XS stub need only implement:
- `Glib::Object::new`
- `Glib::Object::signal_connect`
- `Glib::Object::get` / `set` (property access)
- `Glib::timeout_add($ms, \&cb)` — map to JavaFX `Timeline`
- `Glib::idle_add(\&cb)` — map to `Platform.runLater`

---

## Open Questions

1. **`jperl` auto-reexec vs `jperl-gtk` separate launcher**: Should the
   standard `jperl` detect `use Gtk2` early (before execution) and re-exec
   itself with `--module-path lib/javafx` if those JARs exist? This would be
   transparent to users but requires parsing `@INC`/`use` statements before the
   interpreter starts. Alternatively, keep `jperl-gtk` as an explicit separate
   launcher and document it in `jcpan`'s Gtk2 distroprefs. Preferred: auto-reexec
   if the performance cost is acceptable.

2. **`jcpan -t Gtk2` without `jperl-gtk`**: The current `jcpan` wrapper calls
   `jperl` directly. For `./jcpan -t Gtk2` to work, `jcpan` needs to know to use
   `jperl-gtk`. Distroprefs (`CPAN/distroprefs/Gtk2.yml`) can override the test
   command per module — document this and ship the override file bundled in the
   CPAN configuration.

3. **Linux: detect missing GTK3 system libraries gracefully**: On a minimal
   Linux server without `libgtk-3.so`, JavaFX will throw `UnsatisfiedLinkError`
   when `Platform.startup()` tries to load the GTK3 native peer. `Gtk2.java`
   must catch this in `initialize()` and fall back to `"No display available"`
   rather than a cryptic JVM crash. Check if Monocle is available and auto-enable
   it as fallback.

4. **`Gtk2::Gdk::*` namespace**: Many programs use `Gtk2::Gdk::Event`,
   `Gtk2::Gdk::Keyval`, `Gtk2::Gdk::Screen`. Phase 1 stubs returning sensible
   defaults are sufficient; Phase 2 should map to JavaFX `Screen`/`KeyCode` APIs.

5. **`Gtk2::Stock` constants**: `gtk-ok`, `gtk-cancel`, etc. are deprecated in
   GTK3 and removed in GTK4. Map to plain text labels + standard JavaFX buttons.

6. **Perl threads**: `Gtk2->main` occupies the main Perl thread. Programs that
   spawn `threads` and update GUI from worker threads will not work correctly
   (but neither does this reliably in real GTK2 without `gdk_threads_*` guards).

---

## Progress Tracking

### Current Status: Planning

### Completed
- [x] Architecture design (2026-05-01)
- [x] Widget/JavaFX mapping inventory
- [x] Event loop threading model
- [x] Build system integration plan

### In Progress
- [ ] Phase 1 implementation

### Next Steps
1. Add JavaFX plugin to `build.gradle`
2. Create `Gtk2.java` with `init`, `main`, `main_quit`, event loop skeleton
3. Create `Gtk2.pm` loader with `Gtk2::Window`, `Gtk2::Button`, `Gtk2::Label` stubs
4. Create minimal `Glib.pm` stub
5. Write `t/basic.t` smoke test
6. Run `make` to verify no regressions
7. Run `./jcpan -t Gtk2` under Monocle headless
8. Iteratively fix failures

---

## See Also

- [GD module plan](gd.md) — similar pattern: Java AWT replaces libgd
- [module-porting.md](../../docs/guides/module-porting.md) — general porting guide
- [port-native-module skill](../../.agents/skills/port-native-module/SKILL.md) — FFM patterns (alternative approach)
- [xml_parser.md](xml_parser.md) — precedent: Java SAX replaces libexpat
- Upstream CPAN: https://metacpan.org/dist/Gtk2
- JavaFX API: https://openjfx.io/javadoc/21/
- JavaFX Monocle (headless): https://wiki.openjdk.org/display/OpenJFX/Monocle
