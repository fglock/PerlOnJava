# Perl HTTP Framework Support Roadmap

## Current Status

✓ **Dancer2** - Fully working, ~25,000 req/s  
✓ **Catalyst::Runtime** - Fully working, ~25,000 req/s  
⚠ **Mojolicious** - Module available, Lite mode needs workaround

All frameworks tested with `Plack::Handler::Netty` on PerlOnJava.

## Priority 1: Fix Mojolicious Integration

**Problem:** Mojolicious::Lite doesn't expose `to_app()` method for PSGI compatibility.

**Options:**
- [ ] Create Mojolicious::Lite PSGI wrapper that exports `to_app()`
- [ ] Document Mojolicious full framework approach (not Lite)
- [ ] Add `to_app()` backport/compatibility layer
- [ ] Test Mojolicious with full app (not Lite mode)

**Effort:** 1-2 hours  
**Impact:** High (framework already installed, just needs integration fix)

## Priority 2: Test More Frameworks

**Why:** Verify PSGI compatibility is universal and identify patterns.

**Candidates to test:**
- [ ] Web::Simple (minimal framework)
- [ ] Plack::App::* modules (minimal PSGI apps)
- [ ] Mojo (full Mojolicious framework)
- [ ] CGI::Application (legacy framework support)
- [ ] Mason (template framework with PSGI support)

**Effort:** 2-3 hours  
**Impact:** Medium (validates approach, finds gaps)

## Priority 3: Better Error Handling

**Why:** Currently errors aren't always clear to developers.

**Improvements:**
- [ ] Framework detection with helpful error messages
- [ ] Validate PSGI app before running (structure check)
- [ ] Better stack traces for app errors
- [ ] Health check endpoint support
- [ ] Clear error messages when module missing

**Effort:** 2-3 hours  
**Impact:** High (improves developer experience)

## Priority 4: Performance Tuning

**Why:** Optimize and benchmark per-framework performance.

**Work:**
- [ ] Profile Dancer2 vs Catalyst vs raw PSGI
- [ ] Benchmark with realistic workloads (DB queries, JSON, etc.)
- [ ] Identify bottlenecks per framework
- [ ] Optimize critical paths
- [ ] Publish performance comparison matrix

**Effort:** 3-4 hours  
**Impact:** Medium (validation work, optimization opportunities)

## Priority 5: Middleware Support

**Why:** Most real apps use Plack middleware (CORS, compression, etc.).

**Test:**
- [ ] Common middleware (CORS, compression, logging, etc.)
- [ ] Middleware stacking and ordering
- [ ] Middleware error handling
- [ ] Conditional middleware based on request

**Effort:** 2-3 hours  
**Impact:** High (essential for real apps)

## Priority 6: Documentation Expansion

**Why:** Make it easier for developers to get started.

**Add:**
- [ ] Complete Catalyst integration guide
- [ ] Mojolicious workaround/full-app guide
- [ ] Migration guide from standard Perl servers (Starman, Plack standalone)
- [ ] Deployment patterns and best practices
- [ ] Per-framework troubleshooting guide

**Effort:** 3-4 hours  
**Impact:** High (helps adoption)

## Priority 7: Automated Testing

**Why:** Ensure framework support doesn't regress.

**Create:**
- [ ] Framework compatibility test suite (automated)
- [ ] Performance benchmark tests (automated)
- [ ] Integration tests with real apps
- [ ] CI/CD pipeline checks (GitHub Actions, etc.)

**Effort:** 4-5 hours  
**Impact:** High (quality assurance)

## Priority 8: Developer Experience

**Why:** Make it frictionless to use.

**Add:**
- [ ] Framework detection and auto-setup
- [ ] plackup compatibility layer
- [ ] --help and --version support
- [ ] Configuration file support (.netty, config.pl, etc.)
- [ ] Development mode with auto-reloading

**Effort:** 4-6 hours  
**Impact:** Medium (nice-to-have but valuable)

## Priority 9: Ecosystem Integration

**Why:** Work with existing deployment tools.

**Integrate:**
- [ ] Docker container support (Dockerfile, docker-compose)
- [ ] systemd unit file templates
- [ ] Supervisor config templates
- [ ] nginx reverse proxy configs
- [ ] AWS/Heroku deployment guides

**Effort:** 5-6 hours  
**Impact:** Medium (helps production deployments)

## Priority 10: Advanced Features

**Why:** Support production use cases.

**Add:**
- [ ] WebSocket support (if not already)
- [ ] Server-Sent Events (SSE)
- [ ] HTTP/2 support
- [ ] Custom header handling
- [ ] Request filtering/validation

**Effort:** 6-8 hours  
**Impact:** Medium (advanced use cases)

## Recommended Implementation Order

### Week 1: Quick Wins
1. Fix Mojolicious (Priority 1) - 1-2 hours
2. Add Mojolicious full app example - 0.5 hours
3. Document workarounds - 0.5 hours
4. **Total: 2 hours, immediate value**

### Week 2: Validation
5. Test more frameworks (Priority 2) - 2-3 hours
6. Create test matrix - 1 hour
7. **Total: 3-4 hours, validates approach**

### Week 3: Experience
8. Better error handling (Priority 3) - 2-3 hours
9. Improve error messages - 1-2 hours
10. **Total: 3-5 hours, improves DX**

### Week 4: Documentation
11. Documentation expansion (Priority 6) - 3-4 hours
12. Migration guides - 1-2 hours
13. **Total: 4-6 hours, helps adoption**

### Week 5: Quality
14. Automated testing (Priority 7) - 4-5 hours
15. Set up CI/CD - 1-2 hours
16. **Total: 5-7 hours, ensures quality**

### Ongoing
- Performance tuning (Priority 4)
- Middleware support (Priority 5)
- Developer experience (Priority 8)
- Ecosystem integration (Priority 9)
- Advanced features (Priority 10)

## Investigation Questions

- [ ] What other Perl frameworks are popular in 2026?
- [ ] Are there specific use cases we should support?
- [ ] What deployment scenarios are most common?
- [ ] Should we support FCGI or CGI compatibility?
- [ ] What's the target audience (startups, enterprises, both)?
- [ ] Should we support older frameworks for legacy projects?
- [ ] What's the minimum Perl version we should support?

## Success Metrics

- [ ] 5+ frameworks tested and documented
- [ ] Zero framework-related bug reports
- [ ] Framework compatibility test suite > 90% coverage
- [ ] < 30 second startup time for hello world
- [ ] > 25,000 req/s for simple endpoints
- [ ] > 90% of real-world Plack middleware compatible
- [ ] Deployment guides for 3+ hosting platforms

## Testing Methodology

Each framework should be tested for:
1. **Basic compatibility** - Does it run with Netty?
2. **Routing** - Do routes work correctly?
3. **Request/Response** - Can it handle POST, JSON, etc.?
4. **Error handling** - Does it handle errors gracefully?
5. **Middleware** - Does it work with common middleware?
6. **Performance** - What's the throughput?

## Related Files

- Test results: `dev/framework-testing/RESULTS.md`
- Navigation guide: `dev/framework-testing/GUIDE.md`
- Examples: `examples/http_server_plack/`
- Framework compatibility sandbox scripts: `dev/sandbox/http_server/`

## Notes

- All frameworks use PSGI spec
- Netty provides async I/O backend
- Single-threaded model limits some frameworks (CPU-bound)
- Performance is excellent for I/O-bound apps
- Module ecosystem accessed via jcpan
