# Security Policy

## Supported Versions

Only the latest release of PerlOnJava receives security fixes. We recommend always running the most recent version.

## Reporting a Vulnerability

If you discover a security vulnerability in PerlOnJava, please **do not** open a public GitHub issue. Instead, use one of the following private disclosure channels:

- **GitHub Private Advisory**: [Report a vulnerability](https://github.com/fglock/PerlOnJava/security/advisories/new) via GitHub's Security Advisories feature.

Please include as much of the following as possible:

- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- The version of PerlOnJava affected
- Any suggested mitigations, if known

We will do our best to respond promptly, but cannot guarantee a specific response timeline. We appreciate your patience and your effort in responsible disclosure.

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

PerlOnJava depends on third-party Java libraries. These dependencies may themselves contain vulnerabilities. Keep your dependencies up to date and monitor them with tools such as [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) or GitHub's Dependabot.

## Recommendations for Safe Deployment

- **Never run untrusted Perl code** through PerlOnJava without OS-level sandboxing (e.g., containers with limited capabilities).
- **Disable or restrict Java integration** when it is not needed.
- **Sanitize all inputs** before they reach any dynamic code execution constructs (`eval`, `system`, `exec`, etc.).
- **Keep PerlOnJava updated** to the latest release.
- **Monitor your Java dependencies** for known CVEs.

## Out of Scope

The following are generally not considered security vulnerabilities for this project:

- Bugs in unsupported or older versions
- Issues requiring physical access to the host machine
- Denial-of-service via crafted Perl programs with no network/auth boundary (e.g., infinite loops in local scripts)
- Security issues in third-party CPAN modules not distributed with PerlOnJava

## Acknowledgements

We are grateful to security researchers who responsibly disclose vulnerabilities. Confirmed reporters will be credited in the release notes for the fixing version, unless they prefer to remain anonymous.

