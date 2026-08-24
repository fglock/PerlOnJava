# Maven Central Publishing for PerlOnJava

## Status: Design refreshed; implementation not started

**Originally drafted:** 2025-03-19

**Last updated:** 2026-08-09

**Related:** [Roadmap - Java Platform Alignment](../../docs/about/roadmap.md#maven-central-publishing), [Java Integration](../../docs/guides/java-integration.md)

---

## Objective

Publish a supported PerlOnJava release to Maven Central so Java applications can
embed the runtime with ordinary Maven or Gradle dependency declarations.

The publication must:

- use artifacts produced from the same source and dependency graph as `make`;
- preserve the standalone single-JAR distribution;
- provide a conventional dependency-safe artifact for embedded use;
- include complete Central metadata, sources, Javadocs, signatures, and checksums;
- be reproducible through a manually triggered GitHub Actions release;
- never expose signing keys or Central Portal credentials in the repository; and
- prevent an untested or mismatched tag/version from being published.

## Complexity and Expected Effort

This is a small release-engineering project, not just a plugin addition. The
Central mechanics are straightforward; the main work is defining the artifact
contract and preventing the Gradle and Maven build descriptions from drifting.

| Work | Expected effort | Primary owner |
|------|-----------------|---------------|
| Artifact layout and coordinates | 0.5-1 day | Maintainer + implementation |
| POM metadata and dependency parity | 0.5-1 day | Implementation |
| Sources/Javadocs and validation fixes | 0.5-1.5 days | Implementation |
| Signing and Portal publishing configuration | 0.5 day | Implementation |
| Portal account, namespace, key, and token | 1-2 hours plus verification | Maintainer |
| Release workflow and dry run | 1 day | Implementation |
| First Portal validation corrections | 0.5-1 day | Implementation |

Expected total: **3-5 focused engineering days**, or approximately one week for
a polished automated release including review and first-Portal validation.

---

## Current Repository State

| Requirement | Current state |
|-------------|---------------|
| Java build and test authority | Gradle through `make` |
| Maven build description | `pom.xml` exists and can build the project independently |
| Group ID | `org.perlonjava` in Gradle and Maven |
| Artifact ID | `perlonjava` |
| Version | `5.44.1` |
| Runtime Java version | Java 24+ |
| Standalone artifact | Shaded executable JAR; currently replaces the unclassified main JAR |
| POM project metadata | Incomplete; URL is still the Maven example URL |
| Sources JAR | Missing |
| Javadoc JAR | Missing |
| Artifact signing | Missing |
| Central Portal plugin/profile | Missing |
| Release workflow | Missing |
| Portal account/token | Maintainer setup required |
| Namespace verification | Maintainer decision and setup required |

The Maven and Gradle dependency lists are maintained separately. Publication
must not silently choose one stale list. A dependency-parity check or a single
generated publication model is required before the first release.

## Current Central Portal Requirements

For each non-`pom` component, Maven Central requires:

- a main JAR;
- a POM containing valid coordinates, name, description, project URL, license,
  developer, and SCM information;
- a corresponding sources JAR;
- a corresponding Javadoc JAR;
- detached ASCII-armored GPG/PGP signatures for the POM and all artifacts; and
- MD5 and SHA-1 checksums for the files being uploaded. SHA-256 and SHA-512 are
  supported but not mandatory.

Released coordinates are immutable. A bad `groupId:artifactId:version` cannot
be replaced or deleted; a corrected release must use a new version.

Authoritative references:

- [Central publishing requirements](https://central.sonatype.org/publish/requirements/)
- [Central Portal registration](https://central.sonatype.org/register/central-portal/)
- [Namespace registration](https://central.sonatype.org/register/namespace/)
- [PGP signing](https://central.sonatype.org/publish/requirements/gpg/)
- [Maven Portal plugin](https://central.sonatype.org/publish/publish-portal-maven/)
- [Central immutability policy](https://central.sonatype.org/publish/requirements/immutability/)

---

## Decisions Required Before Implementation

### 1. Artifact layout

**Recommendation:** publish a normal embeddable JAR as the primary
`perlonjava` artifact and retain the standalone shaded JAR as an attached
artifact with an `all` classifier or as a separate `perlonjava-cli` artifact.

Proposed contract:

| Artifact | Purpose |
|----------|---------|
| `perlonjava-<version>.jar` | Thin embeddable runtime with dependencies declared in the POM |
| `perlonjava-<version>-all.jar` | Existing standalone CLI/runtime distribution |
| `perlonjava-<version>-sources.jar` | Java sources and appropriate source resources |
| `perlonjava-<version>-javadoc.jar` | Java API documentation |

Why the distinction matters:

- A thin artifact avoids embedding unrelocated ASM, ICU, Netty, and other
  libraries into a host application's classpath.
- The POM can express transitive dependencies normally for Java consumers.
- The shaded artifact preserves the project's single-JAR command-line promise.
- Publishing a shaded main artifact while also declaring all dependencies can
  load duplicate classes and makes embedding behavior difficult to reason about.

Before changing classifiers, audit `jperl`, `jcpan`, distribution packaging,
tests that locate `target/perlonjava-*.jar`, and documentation that assumes a
single unclassified JAR.

### 2. Namespace

There are two viable choices:

#### Option A: `org.perlonjava` (preferred if the domain is controlled)

Current Central rules require proof of control over `perlonjava.org`, normally
with a DNS TXT record on that exact domain. A GitHub repository under a personal
account does not verify an arbitrary reverse-domain namespace.

#### Option B: `io.github.fglock` (lowest setup effort)

Signing into Central Portal with the `fglock` GitHub account normally provisions
`io.github.fglock` automatically. This avoids DNS work but changes the existing
coordinates and all documentation examples.

**Maintainer decision required:** confirm control of `perlonjava.org`; retain
`org.perlonjava` only if it can be verified through DNS. Do not publish under a
temporary coordinate with the intention of moving later.

### 3. Versioning

The current project version mirrors the supported Perl language version. Before
publishing `5.44.1`, decide how subsequent PerlOnJava-only fixes are numbered.
The scheme must allow multiple runtime releases against the same Perl version
without overwriting an immutable Central coordinate.

Recommended candidates:

- SemVer with Perl compatibility documented separately, for example `1.0.0`;
- a fourth numeric component such as `5.44.1.1`; or
- a SemVer-compatible qualifier whose ordering is documented.

The Git tag, Gradle version, POM version, generated runtime version, and GitHub
release version must agree before publication.

### 4. Publishing implementation

Sonatype currently supplies an official Maven Portal plugin but no official
Gradle Portal plugin. Community Gradle integrations exist but are unsupported by
Sonatype.

**Recommendation:** keep Gradle/`make` authoritative for building and testing,
and use a Maven release profile only as the Central upload adapter. Expose all
release operations through `make` targets so contributors and agents do not run
raw Maven or Gradle publishing commands.

The upload step must consume artifacts verified by the release build, or prove
byte-for-byte that Maven reproduced the canonical artifacts. It must not build a
different dependency set unnoticed.

---

## Implementation Plan

### Phase 1: Artifact contract and build convergence

1. Decide the namespace and versioning scheme.
2. Decide between the `all` classifier and a separate CLI artifact.
3. Make the normal Java JAR and shaded standalone JAR distinct outputs.
4. Update launchers, Debian packaging, tests, and documentation for the chosen
   artifact names.
5. Compare Maven and Gradle dependencies, scopes, resources, manifest entries,
   service descriptors, and SBOM behavior.
6. Add an automated dependency/publication parity check or generate the Maven
   publication metadata from the canonical Gradle model.
7. Add an embedding smoke test using only the proposed Central-style dependency
   graph, including JSR-223 service discovery.

**Exit gate:** `make` passes and both proposed main and standalone artifacts work
without relying on stale files in `target/`.

### Phase 2: Publication metadata and required artifacts

Add the following POM metadata:

- project name and description;
- `https://github.com/fglock/PerlOnJava` project URL;
- Artistic License 1.0 and GPL-1.0-or-later declarations;
- maintainer/developer identity;
- read-only and developer SCM connections; and
- issue tracker and CI URLs where appropriate.

Generate and attach:

- sources JAR;
- Javadoc JAR;
- the selected standalone artifact; and
- optional SBOM artifacts only if their Central layout and signing are tested.

Run Javadoc with useful content rather than relying on an empty placeholder.
Fix malformed documentation or visibility errors found across the Java API. If
the complete internal API is unsuitable, document and enforce the supported
public package surface.

**Exit gate:** a local staging directory contains the exact Central layout and
passes an automated completeness check.

### Phase 3: Signing and maintainer setup

Maintainer actions:

1. Sign into [Central Portal](https://central.sonatype.com/) with the intended
   long-term owner account.
2. Verify the chosen namespace.
3. Generate or select a long-lived signing key with a protected private key.
4. Publish the public key to a Central-supported keyserver.
5. Generate a Central Portal user token.
6. Store the token, armored private key, and passphrase as GitHub Actions
   secrets.

Implementation actions:

1. Add a release-only signing profile.
2. Select the signing key explicitly; do not rely on "first key" behavior.
3. Sign the POM, main JAR, sources, Javadocs, and attached distributable.
4. Verify every signature and checksum during the release build.
5. Ensure secrets are supplied only through environment variables or generated
   CI settings, never checked-in files or command-line logs.

Required secret roles, with final names chosen during implementation:

| Secret | Purpose |
|--------|---------|
| Central token username | Portal authentication |
| Central token password | Portal authentication |
| Armored GPG private key | Artifact signing |
| GPG passphrase | Unlock signing key |

**Exit gate:** a clean local or disposable CI environment creates and verifies
all signed artifacts without publishing them.

### Phase 4: Central upload adapter and safe release workflow

Add `make` targets such as:

- `make release-verify` — full build, tests, artifact checks, signatures, and
  local staging only;
- `make release-bundle` — produce the exact Portal upload bundle; and
- `make release-publish` — CI-only authenticated upload.

The implementation may invoke Maven internally, but the supported project
interface remains `make`.

Create a dedicated GitHub Actions workflow with these properties:

1. Trigger manually or from a published GitHub release, not on ordinary pushes.
2. Require a release tag and verify it against every project version source.
3. Build and test with Java 24 through the existing `make` gate.
4. Require the normal Ubuntu and Windows CI jobs to pass before publishing.
5. Publish exactly once from Ubuntu.
6. Use GitHub environment protection for the production publishing job.
7. Upload the unsigned/signed staging bundle as a retained workflow artifact for
   audit.
8. For the first release, use user-managed publication: upload, wait for Portal
   validation, inspect the result, and publish manually.
9. Enable automatic publication only after at least one successful release and
   rollback/recovery documentation exists.

Do not use an older Java release in the release workflow; PerlOnJava requires
Java 24+.

**Exit gate:** a dry-run workflow proves tag validation, artifact provenance,
secret isolation, and single-uploader behavior.

### Phase 5: First release and consumer verification

Before uploading:

- merge all intended release changes and require a clean tree;
- run `make` and the release verification target;
- verify the published coordinates have never been used;
- inspect the generated POM and dependency scopes;
- test the thin artifact in a fresh Maven consumer and a fresh Gradle consumer;
- run the standalone artifact using the documented command; and
- review licenses and bundled third-party notices.

After Portal validation and publication:

- resolve the artifact from Maven Central in clean Maven and Gradle caches;
- run a JSR-223 embedding smoke test;
- run a CLI smoke test with the standalone artifact;
- verify sources and Javadocs resolve in an IDE;
- update installation and Java integration documentation; and
- close or update GitHub issue #32 with the final coordinates.

---

## Release Safety Rules

- Never publish from a pull request or unprotected branch.
- Never publish the same release from multiple matrix jobs.
- Never place Portal credentials or a private signing key in the repository.
- Never log secret-bearing generated settings files.
- Never use automatic publication for the first release.
- Never reuse a released version, even when the release is broken.
- Never publish when Gradle/Maven dependency parity is unknown.
- Always retain the generated POM, artifact inventory, checksums, and workflow
  run URL as release evidence.

## Acceptance Criteria

- [ ] Namespace and versioning decisions are recorded.
- [ ] Primary and standalone artifact contracts are documented and tested.
- [ ] Gradle and Maven dependency/publication metadata cannot drift silently.
- [ ] `make` passes on the release commit.
- [ ] Ubuntu and Windows CI pass on the release commit.
- [ ] POM satisfies all Central metadata requirements.
- [ ] Sources and Javadocs are attached and useful.
- [ ] Every required artifact and POM is signed and signatures verify.
- [ ] Checksums and Portal bundle layout validate locally.
- [ ] Release workflow validates tag/version equality and publishes once.
- [ ] Portal namespace and user token are configured.
- [ ] Fresh Maven and Gradle consumers resolve and execute PerlOnJava.
- [ ] Standalone CLI artifact still works.
- [ ] Published documentation names the final immutable coordinates.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Publishing the wrong namespace/version | Permanent misleading coordinate | Decide before implementation; validate tag and coordinate availability |
| Thin/shaded classpath conflicts | Broken embedding or duplicate classes | Publish distinct artifacts and test in clean consumer projects |
| Maven/Gradle drift | Published artifact differs from tested artifact | Canonical build plus automated parity/provenance checks |
| Javadoc failures across a large API | Release blocked late | Run Javadoc in Phase 2 and define the supported API surface |
| Leaked GPG key or Portal token | Supply-chain compromise | Protected environment, encrypted secrets, no logged settings |
| Multiple CI jobs publish the same version | Failed or ambiguous deployment | Single Ubuntu publisher after all test jobs |
| Automatic publication of a bad first bundle | Immutable broken release | User-managed first publication and explicit inspection |

---

## Progress Tracking

### Current Status: Phase 0 complete; maintainer decisions required

### Completed Phases

- [x] Phase 0: Research and initial design (2025-03-19)
- [x] Phase 0 refresh: align design with Central Portal and current repository
  architecture (2026-08-09)
  - Corrected namespace verification guidance.
  - Updated the workflow requirement from Java 21 to Java 22.
  - Defined the thin-versus-standalone artifact boundary.
  - Kept Gradle/`make` as the canonical build and scoped Maven to the supported
    Central upload adapter.
  - Added dependency-parity, provenance, first-release, and consumer gates.

### Next Steps

1. Confirm whether `perlonjava.org` is controlled and choose `org.perlonjava`
   or `io.github.fglock`.
2. Choose the first public versioning scheme.
3. Approve the thin primary JAR plus standalone `all` artifact contract.
4. Implement Phase 1 build convergence and artifact smoke tests.
5. Run Phase 2 Javadoc and local staging validation before configuring secrets.

### Open Questions

- Is `perlonjava.org` controlled and available for Central DNS verification?
- Should the standalone distribution use an `all` classifier or a separate
  `perlonjava-cli` artifact ID?
- What version follows `5.44.1` when runtime fixes ship without a Perl language
  version change?
- Which Java packages constitute the supported public embedding API?
- Who owns the long-term GPG key and Central Portal account?
- Should releases be manually triggered or tied to published GitHub releases?

## Related Documents

- [Roadmap](../../docs/about/roadmap.md)
- [Java Integration](../../docs/guides/java-integration.md)
- [Versioning](versioning.md)
- [SBOM Design](sbom.md)
- [Distribution Design](distro.md)
- [Project Agent Guidelines](../../AGENTS.md)
