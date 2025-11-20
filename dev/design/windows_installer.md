# Windows Installer Build Configuration

This document describes how to set up and build Windows installers for PerlOnJava using cross-platform tools.

## Prerequisites

### macOS Setup
```bash
brew install wixtoolset
```

### Ubuntu Setup
```bash
sudo apt-get install wixl
```

## Gradle Configuration

Add to `build.gradle`:

```gradle
plugins {
    // ... existing plugins ...
    id 'org.beryx.jlink' version '3.0.1'
}

jlink {
    launcher {
        name = 'jperl'
        jvmArgs = []
    }
    jpackage {
        installerType = 'msi'
        installerName = 'PerlOnJava'
        appVersion = project.version
        vendor = 'PerlOnJava'
        copyright = 'PerlOnJava Project'
        
        // Windows-specific settings
        winDirChooser = true
        winMenu = true
        winMenuGroup = 'PerlOnJava'
        winShortcut = true
        
        imageOptions = ['--win-console']
    }
}
```

## Makefile Targets

Add to project Makefile:

```makefile
.PHONY: windows-installer

windows-installer:
	./gradlew jpackage
```

## Build Process

The Windows installer will:
1. Bundle JRE for Windows
2. Include PerlOnJava JAR and dependencies
3. Create Start Menu shortcuts
4. Add jperl to system PATH
5. Provide standard Windows uninstaller

## Usage

To build the Windows MSI installer:
```bash
make windows-installer
```

The installer will be created in `build/jpackage/`.

## Directory Structure

Post-installation directory layout:
```
C:\Program Files\PerlOnJava\
├── bin\
│   └── jperl.exe
├── lib\
│   └── perlonjava-3.0.0.jar
└── runtime\
    └── [JRE files]
```