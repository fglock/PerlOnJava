# SBOM (Software Bill of Materials) for PerlOnJava

## Status: Planned

**Author:** Devin AI  
**Date:** 2026-03-19  
**Related:** US Executive Order 14028, CISA SBOM Guidelines, CycloneDX ECMA-424

---

## Overview

This document describes the plan to add CycloneDX SBOM generation to PerlOnJava, covering both:
1. **Java dependencies** (automatically via build tools)
2. **Bundled Perl modules** (via custom script using SBOM::CycloneDX)

The goal is to produce a unified or complementary SBOM that documents the complete software supply chain of PerlOnJava releases.

---

## Background

### What is SBOM?

A **Software Bill of Materials (SBOM)** is a nested inventory - a list of ingredients that make up software components. SBOMs are increasingly required for:

- **US Government contracts** (Executive Order 14028, May 2021)
- **Supply chain security** and vulnerability management
- **License compliance** tracking
- **Security auditing** and incident response
- **Regulatory compliance** (FDA, EU Cyber Resilience Act)

### CycloneDX Standard

**CycloneDX** is the recommended SBOM standard for PerlOnJava because:

1. **Security-focused** - Designed by OWASP for application security contexts
2. **Lightweight** - Simple JSON/XML format, easy to generate and consume
3. **Well-supported** - Extensive tooling for Java (Gradle, Maven) and Perl
4. **Standardized** - ECMA-424 international standard
5. **Comprehensive** - Supports dependencies, licenses, vulnerabilities (VEX)

Alternative: **SPDX** (Linux Foundation) is more focused on license compliance but has less mature Java tooling.

---

## PerlOnJava Component Inventory

### Java Dependencies (from pom.xml / build.gradle)

| Dependency | Version | License | Purpose |
|------------|---------|---------|---------|
| org.ow2.asm:asm | 9.9.1 | BSD-3-Clause | Bytecode manipulation |
| org.ow2.asm:asm-util | 9.9.1 | BSD-3-Clause | ASM utilities |
| com.ibm.icu:icu4j | 78.2 | ICU License | Unicode support |
| com.alibaba.fastjson2:fastjson2 | 2.0.61 | Apache-2.0 | JSON processing |
| org.snakeyaml:snakeyaml-engine | 3.0.1 | Apache-2.0 | YAML processing |
| org.tomlj:tomlj | 1.1.1 | Apache-2.0 | TOML processing |
| org.apache.commons:commons-csv | 1.14.1 | Apache-2.0 | CSV processing |
| com.github.jnr:jnr-posix | 3.1.19 | LGPL-2.1+ | Native POSIX access |

Test dependencies (not in runtime SBOM):
- org.junit.jupiter (JUnit 5) - test scope only

### Bundled Perl Modules (511 files in src/main/perl/lib/)

Categories of bundled Perl modules:

| Category | Examples | License | Notes |
|----------|----------|---------|-------|
| Core modules | strict.pm, warnings.pm | Perl Artistic-2.0 | Re-implemented for PerlOnJava |
| CPAN modules | CPAN.pm, CPAN::Meta | Various | Ported from CPAN |
| Pod modules | Pod::Text, Pod::Man | GPL-1.0+ OR Artistic-1.0 | Documentation tools |
| Test modules | Test::More, Test::Builder | Perl Artistic-2.0 | Testing infrastructure |
| Utility modules | File::Spec, Cwd | Perl Artistic-2.0 | Standard utilities |

---

## Implementation Plan

### Phase 1: Java SBOM Generation

Add CycloneDX plugins to both Gradle and Maven builds.

#### Gradle Configuration

Add to `gradle/libs.versions.toml`:
```toml
[plugins]
cyclonedx = "org.cyclonedx.bom:3.2.2"
```

Add to `build.gradle`:
```groovy
plugins {
    id 'org.cyclonedx.bom' version '3.2.2'
}

// Configure CycloneDX
tasks.cyclonedxBom {
    projectType = "application"
    schemaVersion = "1.6"
    includeLicenseText = false
    includeBomSerialNumber = true
    includeBuildSystem = true
    
    // Output configuration
    destination = file("$buildDir/reports/sbom")
    outputName = "perlonjava-java"
    outputFormat = "all"  // json and xml
    
    // Component metadata
    componentName = "perlonjava"
    componentVersion = project.version
    
    // Organization metadata
    organizationalEntity {
        name = "PerlOnJava Project"
        urls = ["https://github.com/fglock/PerlOnJava"]
    }
}
```

#### Maven Configuration

Add to `pom.xml`:
```xml
<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>2.9.1</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>makeAggregateBom</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <projectType>application</projectType>
        <schemaVersion>1.6</schemaVersion>
        <includeBomSerialNumber>true</includeBomSerialNumber>
        <includeLicenseText>false</includeLicenseText>
        <outputFormat>all</outputFormat>
        <outputName>perlonjava-java</outputName>
    </configuration>
</plugin>
```

**Output:** `build/reports/sbom/perlonjava-java.json` (Gradle) or `target/perlonjava-java.json` (Maven)

### Phase 2: Perl SBOM Generation

Use **SBOM::CycloneDX** (v1.07) to generate SBOM for bundled Perl modules.

#### Option A: Use Native Perl (if available)

```bash
# Install SBOM::CycloneDX
cpanm SBOM::CycloneDX
```

Create `dev/tools/generate-perl-sbom.pl`:
```perl
#!/usr/bin/env perl
use strict;
use warnings;
use SBOM::CycloneDX;
use SBOM::CycloneDX::Enum qw(COMPONENT_TYPE);
use File::Find;
use JSON::PP;

my $bom = SBOM::CycloneDX->new;

# Root component
my $root = SBOM::CycloneDX::Component->new(
    type     => COMPONENT_TYPE->APPLICATION,
    name     => 'perlonjava-perl-modules',
    version  => '5.42.0',
    licenses => [SBOM::CycloneDX::License->new('Artistic-2.0')],
    bom_ref  => 'perlonjava-perl'
);

$bom->metadata->component($root);
$bom->metadata->tools->add(SBOM::CycloneDX::cyclonedx_tool());

# Scan Perl modules
my @modules;
find(sub {
    return unless /\.pm$/;
    my $path = $File::Find::name;
    my $module = $path;
    $module =~ s{.*/lib/}{};
    $module =~ s{/}{::}g;
    $module =~ s{\.pm$}{};
    
    # Extract version from module if available
    my $version = extract_version($path);
    
    push @modules, {
        name    => $module,
        version => $version // 'bundled',
        path    => $path,
    };
}, 'src/main/perl/lib');

# Add components
for my $mod (@modules) {
    my $component = SBOM::CycloneDX::Component->new(
        type    => COMPONENT_TYPE->LIBRARY,
        name    => $mod->{name},
        version => $mod->{version},
        bom_ref => "perl:$mod->{name}",
        purl    => URI::PackageURL->new(
            type => 'cpan',
            name => $mod->{name},
            version => $mod->{version},
        ),
    );
    $bom->components->add($component);
    $bom->add_dependency($root, [$component]);
}

# Validate and output
my @errors = $bom->validate;
die "Validation errors: @errors" if @errors;

print $bom->to_string;

sub extract_version {
    my ($path) = @_;
    open my $fh, '<', $path or return;
    while (<$fh>) {
        if (/(?:our\s+)?\$VERSION\s*=\s*['"]?([0-9._]+)/) {
            return $1;
        }
    }
    return;
}
```

#### Option B: Use PerlOnJava (self-hosting)

Once SBOM::CycloneDX is ported to PerlOnJava:
```bash
./jperl dev/tools/generate-perl-sbom.pl > build/reports/sbom/perlonjava-perl.json
```

#### Option C: Static JSON Generation

For initial implementation, generate a static inventory:
```bash
# Create a simple inventory script
perl dev/tools/generate-perl-sbom-simple.pl > build/reports/sbom/perlonjava-perl.json
```

### Phase 3: Combined SBOM (Optional)

Merge Java and Perl SBOMs into a unified document using CycloneDX CLI:

```bash
# Install CycloneDX CLI
npm install -g @cyclonedx/cyclonedx-cli

# Merge SBOMs
cyclonedx merge \
    --input-files build/reports/sbom/perlonjava-java.json \
                  build/reports/sbom/perlonjava-perl.json \
    --output-file build/reports/sbom/perlonjava-complete.json
```

Alternatively, keep them separate:
- `perlonjava-java.json` - Java dependencies (automatically updated by build)
- `perlonjava-perl.json` - Perl modules (manually curated)

---

## SBOM Storage Locations

### Build Output (Development)

During build, SBOMs are generated to:

| Build System | Location | Files |
|--------------|----------|-------|
| Gradle | `build/reports/sbom/` | `bom.json`, `bom.xml` |
| Maven | `target/` | `bom.json`, `bom.xml` |

### Distribution: JAR File

SBOMs should be embedded inside the JAR following CycloneDX conventions:

```
perlonjava-5.42.0.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── sbom/
│       ├── bom.json          # CycloneDX JSON format
│       └── bom.xml           # CycloneDX XML format (optional)
└── ... (other contents)
```

To include SBOM in JAR, add to `build.gradle`:
```groovy
// Copy SBOM into JAR's META-INF/sbom/
shadowJar {
    from("$buildDir/reports/sbom") {
        into 'META-INF/sbom'
        include '*.json', '*.xml'
    }
}

// Ensure SBOM is generated before JAR
shadowJar.dependsOn cyclonedxBom
```

For Maven, add to `pom.xml`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-sbom</id>
            <phase>package</phase>
            <goals><goal>copy-resources</goal></goals>
            <configuration>
                <outputDirectory>${project.build.outputDirectory}/META-INF/sbom</outputDirectory>
                <resources>
                    <resource>
                        <directory>${project.build.directory}</directory>
                        <includes>
                            <include>bom.json</include>
                            <include>bom.xml</include>
                        </includes>
                    </resource>
                </resources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Distribution: DEB Package

For Debian packages, SBOMs go in the standard documentation directory:

```
/opt/perlonjava/
├── bin/
│   └── jperl
├── lib/
│   └── perlonjava-5.42.0.jar
└── share/
    └── sbom/
        ├── bom.json
        └── bom.xml
```

Alternative location (Debian convention):
```
/usr/share/doc/perlonjava/
├── copyright
├── changelog.gz
└── sbom/
    ├── bom.json
    └── bom.xml
```

To include in DEB package, update `build.gradle`:
```groovy
ospackage {
    // ... existing config ...
    
    // Include SBOM in package
    from("$buildDir/reports/sbom") {
        into '/opt/perlonjava/share/sbom'
        include '*.json', '*.xml'
    }
}
```

### GitHub Release Artifacts

SBOMs should also be attached as separate release artifacts:

```
Release v5.42.0
├── perlonjava-5.42.0.jar
├── perlonjava_5.42.0_amd64.deb
├── perlonjava-5.42.0-sbom.json      # Standalone SBOM
└── perlonjava-5.42.0-sbom.xml       # Standalone SBOM (XML)
```

This allows consumers to inspect the SBOM without downloading/extracting the full package.

---

## CI/CD Integration

### GitHub Actions

Add to `.github/workflows/ci.yml`:
```yaml
- name: Generate SBOM
  run: ./gradlew cyclonedxBom

- name: Upload SBOM
  uses: actions/upload-artifact@v4
  with:
    name: sbom
    path: build/reports/sbom/
    retention-days: 90

# Optional: Upload to Dependency-Track or similar
- name: Upload to Dependency-Track
  if: github.ref == 'refs/heads/master'
  run: |
    curl -X POST "$DT_URL/api/v1/bom" \
      -H "X-Api-Key: $DT_API_KEY" \
      -H "Content-Type: application/json" \
      -d @build/reports/sbom/perlonjava-java.json
```

### Release Artifacts

Include SBOM in GitHub releases:
```yaml
- name: Create Release
  uses: softprops/action-gh-release@v1
  with:
    files: |
      target/perlonjava-*-all.jar
      build/reports/sbom/perlonjava-*.json
```

---

## SBOM::CycloneDX Module Details

### Module Information

- **CPAN:** https://metacpan.org/pod/SBOM::CycloneDX
- **Version:** 1.07 (as of 2026-01-21)
- **Author:** Giuseppe Di Terlizzi (GDT)
- **License:** Artistic-2.0
- **Perl Version:** v5.16.0+

### Dependencies

Core dependencies required:
- Cpanel::JSON::XS
- JSON::Validator
- List::Util (core)
- Moo
- Type::Tiny
- URI::PackageURL
- UUID::Tiny
- namespace::autoclean

### Supported CycloneDX Versions

- 1.7 (latest)
- 1.6
- 1.5
- 1.4
- 1.3
- 1.2

### Key Features

- Full CycloneDX model support
- Built-in validation against official schemas
- JSON output format
- Support for PURL (Package URL) specification
- VEX (Vulnerability Exploitability eXchange) support
- License expression parsing

---

## Makefile Integration

Add SBOM generation to the Makefile:
```makefile
.PHONY: sbom sbom-java sbom-perl sbom-clean

sbom: sbom-java sbom-perl
	@echo "SBOM generated in build/reports/sbom/"

sbom-java:
	./gradlew cyclonedxBom

sbom-perl:
	perl dev/tools/generate-perl-sbom.pl > build/reports/sbom/perlonjava-perl.json

sbom-clean:
	rm -rf build/reports/sbom/
```

---

## Verification

### Validate Generated SBOM

```bash
# Using CycloneDX CLI
cyclonedx validate --input-file build/reports/sbom/perlonjava-java.json

# Using online validator
# https://cyclonedx.github.io/sbom-validator/
```

### Check NTIA Minimum Elements

The generated SBOM must include:
- [x] Supplier name
- [x] Component name  
- [x] Component version
- [x] Unique identifier (PURL/CPE)
- [x] Dependency relationship
- [x] Author of SBOM data
- [x] Timestamp

---

## Open Questions

1. **Perl module licensing:** Should we manually curate licenses for all 511 bundled modules, or use a default?

2. **Version tracking:** How to handle Perl modules that don't have explicit `$VERSION`?

3. **Separate vs. merged SBOM:** Should we ship one unified SBOM or separate Java/Perl SBOMs?

4. **SBOM::CycloneDX porting:** Is it worth porting SBOM::CycloneDX to run under PerlOnJava for self-hosting?

5. **VEX integration:** Should we include vulnerability status (VEX) in the SBOM?

---

## Progress Tracking

### Current Status: Phase 0 - Planning

### Completed Phases
- [x] Phase 0: Research and design document (2026-03-19)

### Next Steps
1. Add CycloneDX plugin to build.gradle
2. Add CycloneDX plugin to pom.xml
3. Create Perl SBOM generation script
4. Add Makefile targets
5. Add CI/CD workflow
6. Verify SBOM compliance

### Open Questions to Resolve
- Decide on separate vs. merged SBOM approach
- Determine Perl module version/license extraction strategy

---

## References

- [CycloneDX Specification](https://cyclonedx.org/specification/overview/)
- [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin)
- [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)
- [SBOM::CycloneDX Perl Module](https://metacpan.org/pod/SBOM::CycloneDX)
- [CISA SBOM Resources](https://www.cisa.gov/sbom)
- [NTIA SBOM Minimum Elements](https://www.ntia.gov/files/ntia/publications/sbom_minimum_elements_report.pdf)
- [US Executive Order 14028](https://www.whitehouse.gov/briefing-room/presidential-actions/2021/05/12/executive-order-on-improving-the-nations-cybersecurity/)
- [CycloneDX Tool Center](https://cyclonedx.org/tool-center/)
