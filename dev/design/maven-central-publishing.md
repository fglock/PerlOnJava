# Maven Central Publishing for PerlOnJava

## Status: Planned

**Date:** 2025-03-19  
**Related:** [Roadmap - Distribution and Packaging](../../docs/about/roadmap.md)

---

## Overview

This document describes the plan to publish PerlOnJava to Maven Central, enabling Java developers to easily integrate PerlOnJava as a dependency in their projects.

### Why Maven Central?

Publishing to Maven Central provides:
- **Discoverability**: Indexed by Maven Central Search, IDE integrations
- **Easy consumption**: Single dependency line in pom.xml/build.gradle
- **Trust**: Verified publisher, signed artifacts
- **Integration**: Works with all major build tools (Maven, Gradle, sbt, etc.)

### Current State

| Requirement | Status |
|-------------|--------|
| GroupId (`org.perlonjava`) | ✅ Valid |
| ArtifactId (`perlonjava`) | ✅ Valid |
| Version (`5.42.0`) | ✅ Valid (not -SNAPSHOT) |
| POM `<name>` | ✅ Present |
| POM `<description>` | ❌ **Missing** |
| POM `<url>` | ⚠️ Placeholder (`http://maven.apache.org`) |
| POM `<licenses>` | ❌ **Missing** |
| POM `<developers>` | ❌ **Missing** |
| POM `<scm>` | ❌ **Missing** |
| Sources JAR | ❌ **Missing** |
| Javadoc JAR | ❌ **Missing** |
| GPG signatures | ❌ **Missing** |
| Publishing plugins | ❌ **Missing** |
| Central Portal account | ❌ **Not created** |
| Namespace verified | ❌ **Not claimed** |

---

## Maven Central Requirements

All requirements below are **mandatory** for publishing to Maven Central.

### 1. POM Metadata

Reference: [Sonatype Requirements](https://central.sonatype.org/publish/requirements/)

#### Required Elements

```xml
<!-- Project coordinates (already present) -->
<groupId>org.perlonjava</groupId>
<artifactId>perlonjava</artifactId>
<version>5.42.0</version>
<packaging>jar</packaging>

<!-- Human-readable info (MISSING) -->
<name>PerlOnJava</name>
<description>A Perl 5 compiler and runtime for the JVM that compiles Perl scripts 
    to Java bytecode, enabling seamless Java integration while maintaining 
    Perl semantics.</description>
<url>https://github.com/fglock/PerlOnJava</url>

<!-- License info (MISSING) -->
<licenses>
    <license>
        <name>Artistic License 2.0</name>
        <url>https://www.perlfoundation.org/artistic-license-20.html</url>
    </license>
</licenses>

<!-- Developer info (MISSING) -->
<developers>
    <developer>
        <name>Flavio Soibelmann Glock</name>
        <email>fglock@gmail.com</email>
        <url>https://github.com/fglock</url>
    </developer>
</developers>

<!-- SCM info (MISSING) -->
<scm>
    <connection>scm:git:git://github.com/fglock/PerlOnJava.git</connection>
    <developerConnection>scm:git:ssh://github.com:fglock/PerlOnJava.git</developerConnection>
    <url>https://github.com/fglock/PerlOnJava/tree/master</url>
</scm>
```

### 2. Required Artifacts

For each release, Maven Central requires:

| Artifact | Description |
|----------|-------------|
| `perlonjava-5.42.0.jar` | Main JAR (already built) |
| `perlonjava-5.42.0.pom` | POM file with metadata |
| `perlonjava-5.42.0-sources.jar` | Source code JAR |
| `perlonjava-5.42.0-javadoc.jar` | Javadoc JAR |
| `*.asc` | GPG signatures for all above |
| `*.md5`, `*.sha1` | Checksums for all above |

### 3. GPG Signing

Reference: [GPG Requirements](https://central.sonatype.org/publish/requirements/gpg/)

All artifacts must be signed with GPG/PGP. Requirements:
- Generate a GPG key pair
- Upload public key to a key server (keys.openpgp.org, keyserver.ubuntu.com)
- Configure build tools to sign during release

### 4. Namespace Verification

Reference: [Namespace Registration](https://central.sonatype.org/register/namespace/)

The `org.perlonjava` namespace must be claimed. Options:

1. **GitHub-based verification** (Recommended for this project):
   - Use `io.github.fglock` namespace (auto-verified via GitHub)
   - OR verify `org.perlonjava` by creating a temporary public repo

2. **Domain-based verification**:
   - Requires DNS TXT record on `perlonjava.org` domain
   - Not applicable unless you control the domain

**Recommendation**: Use `io.github.fglock` for simplicity, OR verify `org.perlonjava` via GitHub repo method.

---

## Recommended Approach: Maven

**Maven is the recommended approach** because:
- Sonatype provides an **official Maven plugin** (`central-publishing-maven-plugin`)
- No Groovy required - pure XML configuration
- Most widely used and documented approach
- The project already has a working `pom.xml`

Gradle support exists only via community plugins (not officially supported by Sonatype).

---

## Implementation Plan

### Phase 1: POM Metadata (No account needed)

#### 1.1 Update pom.xml

Add the following after line 12 (after `<url>`):

```xml
<description>A Perl 5 compiler and runtime for the JVM that compiles Perl scripts 
    to Java bytecode, enabling seamless Java integration while maintaining 
    Perl semantics.</description>
<url>https://github.com/fglock/PerlOnJava</url>

<licenses>
    <license>
        <name>Artistic License 2.0</name>
        <url>https://www.perlfoundation.org/artistic-license-20.html</url>
    </license>
</licenses>

<developers>
    <developer>
        <id>fglock</id>
        <name>Flavio Soibelmann Glock</name>
        <email>fglock@gmail.com</email>
        <url>https://github.com/fglock</url>
    </developer>
</developers>

<scm>
    <connection>scm:git:git://github.com/fglock/PerlOnJava.git</connection>
    <developerConnection>scm:git:ssh://github.com:fglock/PerlOnJava.git</developerConnection>
    <url>https://github.com/fglock/PerlOnJava/tree/master</url>
</scm>
```

#### 1.2 Add Source and Javadoc JARs

Add to `pom.xml` `<plugins>` section:

```xml
<!-- Source JAR -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-source-plugin</artifactId>
    <version>3.3.1</version>
    <executions>
        <execution>
            <id>attach-sources</id>
            <goals>
                <goal>jar-no-fork</goal>
            </goals>
        </execution>
    </executions>
</plugin>

<!-- Javadoc JAR -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-javadoc-plugin</artifactId>
    <version>3.10.1</version>
    <executions>
        <execution>
            <id>attach-javadocs</id>
            <goals>
                <goal>jar</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <doclint>none</doclint>
        <quiet>true</quiet>
    </configuration>
</plugin>
```

### Phase 2: GPG Signing Setup

#### 2.1 Generate GPG Key (Manual step)

```bash
# Generate key
gpg --gen-key
# Enter: Flavio Soibelmann Glock <fglock@gmail.com>

# List keys to get key ID
gpg --list-keys --keyid-format short

# Export and upload to key servers
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
```

#### 2.2 Add GPG Plugin to pom.xml

Add to `pom.xml` in a release profile:

```xml
<profiles>
    <profile>
        <id>release</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-gpg-plugin</artifactId>
                    <version>3.2.7</version>
                    <executions>
                        <execution>
                            <id>sign-artifacts</id>
                            <phase>verify</phase>
                            <goals>
                                <goal>sign</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

### Phase 3: Central Portal Account Setup (Manual)

#### 3.1 Create Account

1. Go to [https://central.sonatype.com](https://central.sonatype.com)
2. Sign in with GitHub (recommended)
3. If signed in with GitHub as `fglock`, namespace `io.github.fglock` is auto-verified

#### 3.2 Namespace Decision

**Option A: Use `io.github.fglock` (Easiest)**
- Change groupId from `org.perlonjava` to `io.github.fglock`
- Automatically verified via GitHub
- Cons: Less professional-looking, harder to migrate later

**Option B: Verify `org.perlonjava` (Recommended)**
- Create temporary public repo at github.com/perlonjava/OSSRH-XXXX
- Sonatype verifies you control the "organization"
- Keep using `org.perlonjava` groupId
- More professional, matches existing coordinates

#### 3.3 Generate Portal Token

1. Log in to [https://central.sonatype.com](https://central.sonatype.com)
2. Click username → "Generate User Token"
3. Save the token securely (needed for CI/CD)

### Phase 4: Publishing Configuration

#### 4.1 Add Central Publishing Plugin to pom.xml

Reference: [Official Maven Publishing Guide](https://central.sonatype.org/publish/publish-portal-maven/)

Add to `pom.xml`:

```xml
<!-- In plugins section -->
<plugin>
    <groupId>org.sonatype.central</groupId>
    <artifactId>central-publishing-maven-plugin</artifactId>
    <version>0.9.0</version>
    <extensions>true</extensions>
    <configuration>
        <publishingServerId>central</publishingServerId>
        <autoPublish>true</autoPublish>
    </configuration>
</plugin>
```

#### 4.2 Configure Local Credentials

Create or update `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username><!-- Portal token username --></username>
            <password><!-- Portal token password --></password>
        </server>
    </servers>
</settings>
```

#### 4.3 Local Release Command

To publish locally (for testing or manual releases):

```bash
# Build, sign, and deploy to Maven Central
mvn clean deploy -Prelease
```

### Phase 5: CI/CD Release Workflow

#### 5.1 Platform Strategy

The existing CI tests on **Windows** and **Ubuntu**. For releases:
- **Testing**: Run on all platforms (Windows, Ubuntu, macOS) to catch platform-specific issues
- **Publishing**: Only run once from Ubuntu (publishing the same artifact multiple times would fail)

#### 5.2 GitHub Actions Workflow

Create `.github/workflows/release.yml`:

```yaml
name: Release to Maven Central

on:
  release:
    types: [published]

jobs:
  # Test on all platforms before publishing
  test:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Build and Test (Unix)
        if: runner.os != 'Windows'
        run: make ci
      
      - name: Build and Test (Windows)
        if: runner.os == 'Windows'
        shell: cmd
        run: make ci
        env:
          GRADLE_OPTS: "-Dorg.gradle.daemon=false"

  # Publish only from Ubuntu after all tests pass
  publish:
    needs: test
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          server-id: central
          server-username: MAVEN_USERNAME
          server-password: MAVEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE
      
      - name: Publish to Maven Central
        run: mvn clean deploy -Prelease -DskipTests
        env:
          MAVEN_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

#### 5.3 Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key (`gpg --armor --export-secret-keys KEY_ID`) |
| `GPG_PASSPHRASE` | GPG key passphrase |
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |

---

## Verification Checklist

Before first release, verify:

- [ ] `make` passes (all tests green)
- [ ] POM has all required metadata (description, licenses, developers, scm)
- [ ] Sources JAR builds: `mvn source:jar`
- [ ] Javadoc JAR builds: `mvn javadoc:jar`
- [ ] GPG key generated and uploaded to key servers
- [ ] GPG signing works locally: `mvn verify -Prelease`
- [ ] Central Portal account created
- [ ] Namespace verified (`org.perlonjava` or `io.github.fglock`)
- [ ] Portal token generated and stored in GitHub secrets

---

## References

### Official Documentation
- [Maven Central Requirements](https://central.sonatype.org/publish/requirements/) - What's needed for publishing
- [Maven Publishing Plugin](https://central.sonatype.org/publish/publish-portal-maven/) - Official plugin docs
- [GPG Signing Guide](https://central.sonatype.org/publish/requirements/gpg/) - Key generation and setup
- [Namespace Registration](https://central.sonatype.org/register/namespace/) - Claiming your namespace
- [Central Portal](https://central.sonatype.com) - Account management and publishing dashboard

### Example Projects
- [simpligility/ossrh-demo](https://github.com/simpligility/ossrh-demo) - Official example with complete pom.xml
- [ossrh-pipeline-demo](https://bitbucket.org/simpligility/ossrh-pipeline-demo/src) - CI/CD pipeline example

---

## Progress Tracking

### Current Status: Phase 0 - Planning

### Completed Phases
- [x] Phase 0: Research and design document (2025-03-19)

### Next Steps
1. Add POM metadata (description, licenses, developers, scm)
2. Add maven-source-plugin and maven-javadoc-plugin
3. Set up GPG key and maven-gpg-plugin
4. Create Central Portal account
5. Verify namespace (`org.perlonjava`)
6. Add central-publishing-maven-plugin
7. Add GitHub secrets for CI/CD
8. Create release workflow
9. Test with first release

### Open Questions
- [ ] Use `io.github.fglock` or verify `org.perlonjava`? (Recommend: verify org.perlonjava)
- [ ] Who holds the GPG key? (Owner: Flavio)
- [ ] Release cadence? (Suggest: on demand via GitHub releases)

---

## Related Documents

- [SBOM Design](sbom.md) - Software Bill of Materials
- [Versioning](versioning.md) - Version management
- [Roadmap](../../docs/about/roadmap.md) - Project roadmap
