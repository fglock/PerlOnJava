# Support and Contribution Guide

## Getting Help

### Documentation
- [README.md](../../README.md) - Project overview and setup instructions
- [Feature Matrix](../reference/feature-matrix.md) - Detailed feature compatibility list
- [Roadmap](roadmap.md) - Future plans
- [Changelog](changelog.md) - Release history

### Community Resources
- GitHub Issues: Technical questions and bug reports
- GitHub Discussions: General questions and community interaction
- Stack Overflow: Use the tags `perl` and eventually `perlonjava`

## Version Support

PerlOnJava version numbers track the compatible Perl language version. For
example, PerlOnJava 5.44.0 targets Perl 5.44, and later patch releases use the
final component for PerlOnJava fixes.

The project does not currently publish a formal LTS or fixed-duration support
policy. Bug fixes and security updates target the current development and
release branches; see the [Security Policy](../../SECURITY.md) for reporting a
vulnerability.

## Contributing Guidelines

### Code Contributions

1. Fork the repository
2. Create a feature branch
3. Write clean, documented code
4. Include tests for new features
5. Submit a Pull Request

### Coding Standards
- Follow existing code style
- Add comments for complex logic
- Include JavaDoc for public methods
- Update relevant documentation

### Testing Requirements
- Add unit tests for new features
- Ensure all tests pass locally
- Include test cases for bug fixes
- Update test documentation

### Documentation Contributions
- Update relevant .md files
- Add code examples where helpful
- Keep the style consistent
- Include references to related features

### Pull Request Process
1. Update documentation
2. Add/update tests
3. Update Feature Matrix if needed
4. Request review from maintainers

### Issue Reporting
Include:
- PerlOnJava version
- Java version
- Operating system
- Minimal reproducible example
- Expected vs actual behavior
- Related error messages

## Release Process

Changes are developed on feature branches, reviewed through pull requests, and
merged after testing. Release notes are maintained in the
[Changelog](changelog.md).

## Security

### Reporting Security Issues
- Report security vulnerabilities privately
- Include proof-of-concept if possible
- Allow time for fixes before public disclosure

### Security Updates
- Critical updates released as needed
- Regular security audits
- Dependency vulnerability monitoring

## Project Governance

### Decision Making
- Technical decisions through pull request reviews
- Major features discussed in GitHub Discussions
- Version planning via milestones

### Core Team
- Maintainers review pull requests
- Documentation team reviews docs
- Security team handles vulnerabilities

## Communication Channels

### Official Channels
- GitHub Issues: Bug reports and features
- GitHub Discussions: Community interaction
- Release Notes: Version updates

### Best Practices
- Search existing issues before creating new ones
- Use clear, descriptive titles
- Include relevant code examples
- Follow up on your submissions

## License

This project is licensed under the same terms as Perl 5 (Artistic License or GPL v1+). See [LICENSE](../../LICENSE.md) for details.
