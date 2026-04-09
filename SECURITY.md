# Security Policy

## Supported Versions

Only the latest release of PerlOnJava receives security fixes. We recommend always running the most recent version.

## Relationship to Perl 5

PerlOnJava is an **independent project** that implements the Perl 5 language on the JVM. It is not part of the Perl core distribution and is not maintained by the Perl core developers. The [Perl security team](https://perldoc.perl.org/perlsecpolicy) (`perl-security@perl.org`) does **not** handle security issues in PerlOnJava — please do not report PerlOnJava vulnerabilities to them.

If you are unsure whether a bug is in PerlOnJava or in Perl itself, report it to PerlOnJava first using the channels below. We will coordinate with the Perl security team if the issue turns out to affect the upstream Perl interpreter.

## Reporting a Vulnerability

If you discover a security vulnerability in PerlOnJava, please **do not** open a public GitHub issue. Instead, use one of the following private disclosure channels:

- **GitHub Private Advisory** (preferred): [Report a vulnerability](https://github.com/fglock/PerlOnJava/security/advisories/new) via GitHub's Security Advisories feature.

- **Email**: Contact the maintainer directly (see [GitHub profile](https://github.com/fglock)).

### For Bundled Perl Module Vulnerabilities

If the vulnerability is in a Perl module bundled with PerlOnJava (rather than PerlOnJava itself), you may also contact the [CPAN Security Group](https://security.metacpan.org/) at [cpan-security@security.metacpan.org](mailto:cpan-security@security.metacpan.org). CPANSec is the CVE Numbering Authority for Perl and CPAN.

### What to Include

Please include as much of the following as possible:

- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- The version of PerlOnJava affected
- Any suggested mitigations, if known

### Response Process

After receiving your report, we will:
- **Acknowledge** receipt of your report
- **Assess** the vulnerability and determine its severity
- **Coordinate** with you on disclosure timing and any fixes

This is a volunteer-maintained project, so response times may vary. We appreciate your patience and your effort in responsible disclosure.

## Security Considerations

PerlOnJava is a Perl interpreter that runs on the JVM. There are several architectural security considerations to be aware of when deploying it.

### Code Injection via `eval`

PerlOnJava supports Perl's `eval` construct. If user-supplied input is passed into `eval` — directly or indirectly — this constitutes a code injection vulnerability. Never pass untrusted input to `eval` without thorough sanitization.

```perl
# DANGEROUS - do not do this
eval $user_input;

# Safe - evaluate only trusted, hard-coded strings
eval { some_function() };
```

### System Execution (`system`, `exec`, backticks)

Perl's `system()`, `exec()`, and backtick operators can execute arbitrary OS commands. If user-controlled data reaches these constructs, it may lead to OS command injection. These should be avoided entirely when processing untrusted input, or used with strict argument-list forms.

### Taint Mode Limitations

Standard Perl provides a taint mode (`-T` flag) to track and restrict the use of user-supplied data. PerlOnJava's implementation of taint mode may not be complete or behave identically to standard Perl. **Do not rely on taint mode as your primary security control** in PerlOnJava.

### Sandbox and JVM Escape

PerlOnJava executes Perl code within the JVM. However, it does not provide a hardened sandbox. Malicious or untrusted Perl code may be able to:

- Access the local filesystem via Perl file I/O functions
- Interact with Java internals via PerlOnJava's Java integration features
- Make network connections

If you are executing untrusted Perl code, you must enforce isolation at the OS or container level (e.g., Docker, seccomp, a restricted JVM security policy) rather than relying on PerlOnJava itself to sandbox execution.

### Java Integration Surface

PerlOnJava supports calling Java classes and methods from Perl (JSR-223). This significantly expands the attack surface when running untrusted code, as it can expose JVM internals, class loaders, and other Java APIs. Restrict access to Java integration features when running untrusted Perl.

### Dependency Vulnerabilities

PerlOnJava depends on third-party Java libraries and bundles Perl modules. These dependencies may themselves contain vulnerabilities.

**AI-generated code**: AI coding assistants may introduce security-sensitive patterns (e.g., unsanitized input reaching `eval` or `system`, exposed secrets, insecure defaults). All AI-assisted contributions undergo the same review process as human-written code, with particular attention to the risk areas described in this document. See [AI_POLICY.md](AI_POLICY.md) for PerlOnJava's AI-assisted development policy and attribution practices.

**Java dependencies**: Keep your dependencies up to date and monitor them with tools such as [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) or GitHub's Dependabot.

**Bundled Perl modules**: Check the [CPAN Security Advisory Database](https://security.metacpan.org/) and the [CPANSA feed](https://github.com/CPAN-Security/cpansa-feed) for known vulnerabilities in Perl modules.

**SBOM**: A unified Software Bill of Materials (SBOM) in CycloneDX format lists all Java dependencies and bundled Perl modules:
- Embedded in the JAR at `META-INF/sbom/sbom.json`
- Generated via `make sbom` (outputs to `build/reports/sbom.json`)
- Uploaded as CI artifacts on each build

Use with vulnerability scanning tools like [OWASP Dependency-Track](https://dependencytrack.org/) or `cyclonedx-cli validate` to check for known issues.

## Recommendations for Safe Deployment

- **Never run untrusted Perl code** through PerlOnJava without OS-level sandboxing (e.g., containers with limited capabilities).
- **Disable or restrict Java integration** when it is not needed.
- **Sanitize all inputs** before they reach any dynamic code execution constructs (`eval`, `system`, `exec`, etc.).
- **Keep PerlOnJava updated** to the latest release.
- **Keep your JVM updated** — JVM vulnerabilities affect all PerlOnJava users.
- **Monitor your Java dependencies** for known CVEs.

## Out of Scope

The following are generally not considered security vulnerabilities for this project:

- Bugs in unsupported or older versions
- Issues requiring physical access to the host machine
- Denial-of-service via crafted Perl programs with no network/auth boundary (e.g., infinite loops in local scripts)
- Security issues in third-party CPAN modules not distributed with PerlOnJava

## Acknowledgements

We are grateful to security researchers who responsibly disclose vulnerabilities. Confirmed reporters will be credited in the release notes for the fixing version, unless they prefer to remain anonymous.

## Related Resources

- [CPAN Security Group](https://security.metacpan.org/) - CVE Numbering Authority for Perl/CPAN
- [Perl Security Policy](https://perldoc.perl.org/perlsecpolicy) - Security handling for the Perl core interpreter (independent from PerlOnJava; see [Relationship to Perl 5](#relationship-to-perl-5))
- [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) - Vulnerability scanner for Java dependencies
- [GitHub Security Advisories](https://github.com/fglock/PerlOnJava/security/advisories) - Published advisories for this project

