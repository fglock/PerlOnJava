# SBOM (Software Bill of Materials) for PerlOnJava

## Status: Implemented

**Date:** 2026-03-19 (updated 2026-03-24)  
**Related:** US Executive Order 14028, CISA SBOM Guidelines, CycloneDX ECMA-424

---

## Overview

This document describes the plan to add CycloneDX SBOM generation to PerlOnJava, covering both:
1. **Java dependencies** (automatically via build tools)
2. **Bundled Perl modules** (via custom script using SBOM::CycloneDX)

The goal is to produce a unified or complementary SBOM that documents the complete software supply chain of PerlOnJava releases.

---

## Glossary

| Term | Definition |
|------|------------|
| **SBOM** | Software Bill of Materials - a formal inventory of software components |
| **[CycloneDX](https://cyclonedx.org/)** | An OWASP standard for SBOM format (JSON/XML), now ECMA-424 |
| **[SPDX](https://spdx.dev/)** | Alternative SBOM standard from Linux Foundation, focused on licensing |
| **[PURL](https://github.com/package-url/purl-spec)** | Package URL - a standard format for identifying software packages (e.g., `pkg:maven/org.ow2.asm/asm@9.9.1`) |
| **[VEX](https://cyclonedx.org/capabilities/vex/)** | Vulnerability Exploitability eXchange - documents whether vulnerabilities affect a product |
| **Shaded/Uber JAR** | A JAR file that bundles all dependencies into a single archive |
| **[Maven Central](https://central.sonatype.com/)** | The primary repository for Java libraries (like CPAN for Perl) |
| **[CPAN](https://metacpan.org/)** | Comprehensive Perl Archive Network - the primary repository for Perl modules |

---

## Background

### What is SBOM?

A **Software Bill of Materials (SBOM)** is a formal, machine-readable inventory of all components in a software product. Think of it as a "ingredients list" for software.

**Why it matters:**
- **Security teams** can quickly check if a product contains vulnerable components
- **Legal/compliance teams** can verify license obligations
- **Procurement** can assess supply chain risk before adoption

SBOMs are increasingly required for:
- **US Government contracts** (Executive Order 14028, May 2021)
- **Supply chain security** and vulnerability management
- **License compliance** tracking
- **Security auditing** and incident response
- **Regulatory compliance** (FDA for medical devices, EU Cyber Resilience Act)

### Why CycloneDX?

**CycloneDX** is the recommended SBOM standard for PerlOnJava because:

1. **Security-focused** - Designed by OWASP for application security contexts
2. **Lightweight** - Simple JSON/XML format, easy to generate and consume
3. **Well-supported** - Mature plugins for Gradle, Maven; Perl library available on CPAN
4. **Standardized** - ECMA-424 international standard (December 2024)
5. **Comprehensive** - Supports dependencies, licenses, and vulnerability status (VEX)

**Alternative:** SPDX (Linux Foundation) is another respected standard, more focused on license compliance. Both have good tooling; CycloneDX is chosen here for its security focus and simpler format.

---

## PerlOnJava Component Inventory

### Java Dependencies (from pom.xml / build.gradle)

These are external libraries downloaded from Maven Central during build:

| Dependency | Version | License | Purpose |
|------------|---------|---------|---------|
| org.ow2.asm:asm | 9.9.1 | BSD-3-Clause | JVM bytecode generation |
| org.ow2.asm:asm-util | 9.9.1 | BSD-3-Clause | ASM utilities |
| com.ibm.icu:icu4j | 78.2 | ICU License | Unicode support |
| org.snakeyaml:snakeyaml-engine | 3.0.1 | Apache-2.0 | YAML processing |
| org.tomlj:tomlj | 1.1.1 | Apache-2.0 | TOML processing |
| org.apache.commons:commons-csv | 1.14.1 | Apache-2.0 | CSV processing |
| com.github.jnr:jnr-posix | 3.1.19 | LGPL-2.1+ | Native POSIX access |

Test dependencies (excluded from runtime SBOM):
- org.junit.jupiter (JUnit 5) - test scope only

### Bundled Perl Modules (511 files in src/main/perl/lib/)

These are Perl modules bundled with PerlOnJava to provide standard library functionality:

| Category | Examples | License | Notes |
|----------|----------|---------|-------|
| Pragmas | strict.pm, warnings.pm | Perl Artistic-2.0 | Compile-time behavior controls |
| CPAN client | CPAN.pm, CPAN::Meta | Various | Module installation tools |
| Pod modules | Pod::Text, Pod::Man | GPL-1.0+ OR Artistic-1.0 | Documentation processing |
| Test modules | Test::More, Test::Builder | Perl Artistic-2.0 | Testing framework |
| Utility modules | File::Spec, Cwd | Perl Artistic-2.0 | Cross-platform utilities |

---

## Implementation Plan

### Phase 1: Java SBOM Generation

Add CycloneDX plugins to both Gradle and Maven builds. These plugins automatically scan dependencies and generate compliant SBOMs.

#### Gradle Configuration

Add to `gradle/libs.versions.toml`:
```toml
[plugins]
cyclonedx = { id = "org.cyclonedx.bom", version = "3.2.2" }
```

Add to `build.gradle`:
```groovy
plugins {
    id 'org.cyclonedx.bom'
}

// Configure CycloneDX (optional - defaults are sensible)
cyclonedxBom {
    projectType = "application"
    schemaVersion = "1.6"
    includeLicenseText = false
    includeBomSerialNumber = true
    
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

Run with: `./gradlew cyclonedxBom`

**Output:** `build/reports/cyclonedx/bom.json` and `bom.xml`

#### Maven Configuration

Add to `pom.xml` in the `<plugins>` section:
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
        <outputName>bom</outputName>
    </configuration>
</plugin>
```

Run with: `mvn package` (SBOM generated automatically) or `mvn cyclonedx:makeAggregateBom`

**Output:** `target/bom.json` and `bom.xml`

### Phase 2: Perl SBOM Generation

Generate SBOM for the 511 bundled Perl modules. Since these aren't downloaded from a package manager, we need a custom approach.

**Recommended approach:** Use native Perl with the `SBOM::CycloneDX` module from CPAN.

#### Prerequisites

```bash
# Install from CPAN (requires native Perl)
cpanm SBOM::CycloneDX
```

#### Generation Script

Create `dev/tools/generate-perl-sbom.pl`:
```perl
#!/usr/bin/env perl
use strict;
use warnings;
use SBOM::CycloneDX;
use SBOM::CycloneDX::Component;
use SBOM::CycloneDX::License;
use SBOM::CycloneDX::Enum qw(:component_type);
use File::Find;

my $bom = SBOM::CycloneDX->new(spec_version => '1.6');

# Define the root component (PerlOnJava itself)
my $root = SBOM::CycloneDX::Component->new(
    type     => COMPONENT_TYPE_APPLICATION,
    name     => 'perlonjava-perl-modules',
    version  => $ENV{VERSION} // '5.42.0',
    licenses => [SBOM::CycloneDX::License->new(id => 'Artistic-2.0')],
    bom_ref  => 'perlonjava-perl'
);

$bom->metadata->component($root);

# Scan and add all Perl modules
find(sub {
    return unless /\.pm$/;
    my $path = $File::Find::name;
    
    # Convert path to module name: lib/Foo/Bar.pm -> Foo::Bar
    my $module = $path;
    $module =~ s{.*/lib/}{};
    $module =~ s{/}{::}g;
    $module =~ s{\.pm$}{};
    
    # Try to extract version from module
    my $version = extract_version($path) // 'bundled';
    
    my $component = SBOM::CycloneDX::Component->new(
        type    => COMPONENT_TYPE_LIBRARY,
        name    => $module,
        version => $version,
        bom_ref => "perl:$module",
    );
    
    $bom->components->add($component);
    $bom->dependencies->add($root->bom_ref, $component->bom_ref);
    
}, 'src/main/perl/lib');

# Validate and output
my @errors = $bom->validate;
die "SBOM validation failed: @errors\n" if @errors;

print $bom->to_json;

sub extract_version {
    my ($path) = @_;
    open my $fh, '<', $path or return;
    while (<$fh>) {
        # Match: our $VERSION = '1.23'; or $VERSION = "1.23";
        if (/\$VERSION\s*=\s*['"]?([0-9][0-9._]*)/) {
            return $1;
        }
    }
    return;
}
```

Run with:
```bash
perl dev/tools/generate-perl-sbom.pl > build/reports/cyclonedx/perl-bom.json
```

#### Alternative: Simpler Static Approach

If `SBOM::CycloneDX` dependencies are problematic, generate a minimal compliant SBOM using core Perl modules only. See `dev/tools/generate-perl-sbom-simple.pl` (to be created).

### Phase 3: Combined SBOM (Optional)

Merge Java and Perl SBOMs into a unified document using CycloneDX CLI:

```bash
# Install CycloneDX CLI
npm install -g @cyclonedx/cyclonedx-cli

# Merge SBOMs
cyclonedx merge \
    --input-files build/reports/cyclonedx/bom.json \
                  build/reports/cyclonedx/perl-bom.json \
    --output-file build/reports/cyclonedx/combined-bom.json
```

Alternatively, keep them separate:
- `bom.json` - Java dependencies (automatically updated by build)
- `perl-bom.json` - Perl modules (generated separately)

---

## SBOM Storage Locations

### Build Output (Development)

During build, SBOMs are generated to:

| Build System | Location | Files |
|--------------|----------|-------|
| Gradle | `build/reports/cyclonedx/` | `bom.json`, `bom.xml` |
| Maven | `target/` | `bom.json`, `bom.xml` |
| Perl script | `build/reports/cyclonedx/` | `perl-bom.json` |

### Distribution: JAR File

SBOMs can be embedded inside the JAR for easy discovery:

```
perlonjava-5.42.0.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── sbom/
│       └── bom.json          # CycloneDX JSON format
└── ... (other contents)
```

To include SBOM in JAR, add to `build.gradle`:
```groovy
// Copy SBOM into JAR's META-INF/sbom/
shadowJar {
    from("$buildDir/reports/cyclonedx") {
        into 'META-INF/sbom'
        include 'bom.json'
    }
}

// Ensure SBOM is generated before JAR
shadowJar.dependsOn cyclonedxBom
```

For Maven, the shade plugin can include the SBOM automatically if it's in the resources directory during build.

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
    from("$buildDir/reports/cyclonedx") {
        into '/opt/perlonjava/share/sbom'
        include 'bom.json'
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
    path: build/reports/cyclonedx/
    retention-days: 90
```

### Release Artifacts

Include SBOM in GitHub releases:
```yaml
- name: Create Release
  uses: softprops/action-gh-release@v1
  with:
    files: |
      target/perlonjava-*-all.jar
      build/reports/cyclonedx/bom.json
```

---

## SBOM::CycloneDX Perl Module

The `SBOM::CycloneDX` module on CPAN provides a full implementation of the CycloneDX specification for Perl.

### Module Information

- **CPAN:** https://metacpan.org/pod/SBOM::CycloneDX
- **License:** Artistic-2.0
- **Minimum Perl:** v5.16.0

### Key Dependencies

- Cpanel::JSON::XS (fast JSON encoding)
- JSON::Validator (schema validation)
- Moo (object system)
- Type::Tiny (type constraints)
- URI::PackageURL (PURL support)
- UUID::Tiny (serial number generation)

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

## CPAN Security Group (CPANSec)

The [CPAN Security Group](https://security.metacpan.org/) is a community effort for supporting and responding to security incidents in the Perl/CPAN ecosystem.

### Why CPANSec Matters for SBOM

1. **CVE Numbering Authority (CNA)**: As of February 2025, CPANSec is the official [CVE Numbering Authority](https://www.cve.org/PartnerInformation/ListofPartners) for Perl and CPAN. This means:
   - All CPAN-related CVEs go through CPANSec
   - Vulnerability IDs in SBOMs can be traced to authoritative sources
   - Security advisories are coordinated through proper channels

2. **CPANSA Feed**: The [cpansa-feed](https://github.com/CPAN-Security/cpansa-feed) provides automatically updated security advisory data for CPAN modules in a structured JSON format. This can be used to:
   - Check bundled Perl modules against known vulnerabilities
   - Generate VEX (Vulnerability Exploitability eXchange) data
   - Integrate with vulnerability scanning tools

3. **SBOM Guidelines**: CPANSec maintains [perl-SBOM-Examples](https://github.com/CPAN-Security/perl-SBOM-Examples) with guidance on:
   - When to create/update SBOM files
   - Required metadata fields for Perl distributions
   - Handling vendored (bundled) dependencies

### CPANSec Resources

| Resource | Description |
|----------|-------------|
| [Security Advisory Database](https://security.metacpan.org/) | Main website, CVE announcements |
| [cpansa-feed](https://github.com/CPAN-Security/cpansa-feed) | Machine-readable security advisory data |
| [perl-SBOM-Examples](https://github.com/CPAN-Security/perl-SBOM-Examples) | SBOM best practices for Perl |
| [Test-CVE](https://metacpan.org/pod/Test::CVE) | Test distributions for CVE vulnerabilities |
| [Net-CVE](https://metacpan.org/pod/Net::CVE) | Fetch CVE data from cve.org |
| [CVE Announcements](https://lists.security.metacpan.org/cve-announce/) | Mailing list archive |

### Integration Opportunity

Future enhancement: Integrate CPANSA feed checking into CI/CD to automatically flag if any bundled Perl modules have known vulnerabilities. This would complement the SBOM by providing actionable security status.

---

## Makefile Integration

Add SBOM generation to the Makefile:
```makefile
.PHONY: sbom sbom-java sbom-perl sbom-clean

sbom: sbom-java sbom-perl
	@echo "SBOM generated in build/reports/cyclonedx/"

sbom-java:
	./gradlew cyclonedxBom

sbom-perl:
	@mkdir -p build/reports/cyclonedx
	perl dev/tools/generate-perl-sbom.pl > build/reports/cyclonedx/perl-bom.json

sbom-clean:
	rm -rf build/reports/cyclonedx/
```

---

## Verification

### Validate Generated SBOM

```bash
# Using CycloneDX CLI (install via npm: npm install -g @cyclonedx/cyclonedx-cli)
cyclonedx validate --input-file build/reports/cyclonedx/bom.json

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

## Component Hashes

### PerlOnJava Distribution Model

PerlOnJava ships as a **shaded/uber JAR** containing everything:
```
perlonjava-5.42.0.jar
├── org/perlonjava/...        (PerlOnJava Java classes)
├── org/ow2/asm/...           (shaded ASM library)
├── com/ibm/icu/...           (shaded ICU4J library)
├── lib/*.pm                  (511 bundled Perl modules)
└── META-INF/sbom/bom.json    (embedded SBOM)
```

### Hash Strategy

#### 1. Java Dependencies → **Automatic (per-component)**

The CycloneDX plugins automatically include hashes for Java dependencies:
- These are hashes of the **original Maven artifacts** (before shading)
- Used to identify exact versions and match against CVE databases
- Hashes are retrieved from Maven Central metadata

```json
{
  "type": "library",
  "name": "asm",
  "version": "9.9.1",
  "purl": "pkg:maven/org.ow2.asm/asm@9.9.1",
  "hashes": [
    {"alg": "MD5", "content": "..."},
    {"alg": "SHA-1", "content": "..."},
    {"alg": "SHA-256", "content": "..."}
  ]
}
```

#### 2. Perl Modules → **Optional (skip individual hashes)**

For the 511 bundled Perl modules, individual hashes are **not required** because:
- They are bundled source files, not downloaded artifacts
- CPAN does publish checksums, but there's no standard vulnerability database keyed by hash
- Version tracking via `$VERSION` is sufficient for identification

The Perl SBOM should include:
- Module name and version
- License information (where known)
- **Hashes: omitted** (simplifies generation; can be added later if needed)

#### 3. Distribution Artifacts → **Single hash file**

The final distribution artifacts should have accompanying hash files:

```
Release v5.42.0/
├── perlonjava-5.42.0.jar
├── perlonjava-5.42.0.jar.sha256      # echo "abc123... perlonjava-5.42.0.jar"
├── perlonjava_5.42.0_amd64.deb
├── perlonjava_5.42.0_amd64.deb.sha256
└── perlonjava-5.42.0-sbom.json       # SBOM (contains component hashes)
```

Generate with:
```bash
sha256sum perlonjava-5.42.0.jar > perlonjava-5.42.0.jar.sha256
```

### Supported Hash Algorithms

CycloneDX supports:
- **MD5**, **SHA-1** (legacy, for compatibility)
- **SHA-256** (recommended)
- **SHA-384**, **SHA-512**
- **SHA3-256**, **SHA3-384**, **SHA3-512**
- **BLAKE2b-256**, **BLAKE2b-384**, **BLAKE2b-512**
- **BLAKE3**

### Summary

| What | Individual Hashes? | Notes |
|------|-------------------|-------|
| Java dependencies | ✅ Yes (automatic) | Pre-shading artifact hashes from Maven Central |
| Perl modules | ❌ No (optional) | Version/license is sufficient |
| Final JAR | ✅ Yes (single file) | `.sha256` file alongside release |
| Final DEB | ✅ Yes (single file) | `.sha256` file alongside release |

---

## Open Questions

1. **Perl module licensing:** Should we manually curate licenses for all 511 bundled modules, or use a default?

2. **Version tracking:** How to handle Perl modules that don't have explicit `$VERSION`?

3. **Separate vs. merged SBOM:** Should we ship one unified SBOM or separate Java/Perl SBOMs?

4. **SBOM::CycloneDX porting:** Is it worth porting SBOM::CycloneDX to run under PerlOnJava for self-hosting?

5. **VEX integration:** Should we include vulnerability status (VEX) in the SBOM?

---

## Progress Tracking

### Current Status: Phase 1, 2 & 3 Complete (Unified SBOM)

### Completed Phases
- [x] Phase 0: Research and design document (2026-03-19)
- [x] Phase 1: Java SBOM Generation (2026-03-24)
  - Added CycloneDX plugin to gradle/libs.versions.toml (v2.3.0)
  - Configured build.gradle with CycloneDX settings
  - Added cyclonedx-maven-plugin to pom.xml (v2.9.1)
- [x] Phase 2: Perl SBOM Generation (2026-03-24)
  - Created dev/tools/generate-perl-sbom.pl using core Perl modules
  - Generates CycloneDX 1.6 compliant JSON
  - Scans 558 bundled Perl modules with version and license detection
- [x] Phase 3: Combined SBOM (2026-03-24)
  - Created dev/tools/merge-sbom.pl to merge Java and Perl SBOMs
  - Unified SBOM embedded in JAR at META-INF/sbom/sbom.json
  - Gradle tasks: generatePerlSbom, mergeSbom (configuration-cache compatible)
  - Final SBOM contains 590 components (8 Java + 558 Perl + metadata)
- [x] Makefile Integration (2026-03-24)
  - Added targets: sbom, sbom-java, sbom-perl, sbom-clean
- [x] CI/CD Integration (2026-03-24)
  - Updated .github/workflows/gradle.yml to generate and upload combined SBOM

### Next Steps
1. (Optional) Add VEX (Vulnerability Exploitability eXchange) integration
2. (Optional) Port SBOM::CycloneDX to run under PerlOnJava

### Open Questions Resolved
- **Separate vs. merged SBOM**: Using unified/merged SBOM
- **Perl module license detection**: Basic detection implemented, can be refined

---

## References

### SBOM Standards
- [CycloneDX Specification](https://cyclonedx.org/specification/overview/) - OWASP SBOM standard
- [CycloneDX ECMA-424](https://ecma-international.org/publications-and-standards/standards/ecma-424/) - International standard (Dec 2024)
- [SPDX Specification](https://spdx.dev/specifications/) - Linux Foundation SBOM standard
- [PURL Specification](https://github.com/package-url/purl-spec) - Package URL format
- [VEX Specification](https://cyclonedx.org/capabilities/vex/) - Vulnerability Exploitability eXchange

### CycloneDX Tooling
- [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin)
- [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)
- [CycloneDX CLI](https://github.com/CycloneDX/cyclonedx-cli) - Merge, validate, convert SBOMs
- [CycloneDX Tool Center](https://cyclonedx.org/tool-center/) - Full list of tools
- [CycloneDX Online Validator](https://cyclonedx.github.io/sbom-validator/)

### Perl Resources
- [SBOM::CycloneDX on CPAN](https://metacpan.org/pod/SBOM::CycloneDX)
- [CPAN](https://metacpan.org/) - Comprehensive Perl Archive Network
- [CPAN Security Group (CPANSec)](https://security.metacpan.org/) - CVE Numbering Authority for Perl/CPAN
- [CPANSA Feed](https://github.com/CPAN-Security/cpansa-feed) - Machine-readable security advisories
- [perl-SBOM-Examples](https://github.com/CPAN-Security/perl-SBOM-Examples) - SBOM best practices for Perl
- [CPANSec CVE Announcements](https://lists.security.metacpan.org/cve-announce/) - Mailing list archive

### Java Resources
- [Maven Central](https://central.sonatype.com/) - Primary Java package repository

### Regulatory & Government
- [US Executive Order 14028](https://www.whitehouse.gov/briefing-room/presidential-actions/2021/05/12/executive-order-on-improving-the-nations-cybersecurity/) - Improving the Nation's Cybersecurity (May 2021)
- [CISA SBOM Resources](https://www.cisa.gov/sbom) - US guidance and tools
- [NTIA SBOM Minimum Elements](https://www.ntia.gov/files/ntia/publications/sbom_minimum_elements_report.pdf) - Required fields
- [EU Cyber Resilience Act](https://digital-strategy.ec.europa.eu/en/policies/cyber-resilience-act) - European regulation
