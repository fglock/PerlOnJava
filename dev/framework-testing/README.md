# Framework Compatibility Testing Results

This directory contains documentation and test results for verifying Perl HTTP framework compatibility with PerlOnJava's bundled Plack::Handler::Netty.

## Results

✓ **Dancer2** - Fully compatible, ~25,000 req/s  
✓ **Catalyst::Runtime** - Fully compatible, ~25,000 req/s  
⚠ **Mojolicious** - Module available, Lite mode needs workaround

## Documentation

- **`FRAMEWORK_TEST_RESULTS.md`** - Comprehensive framework test results and deployment guide
  - Test methodology and results
  - Framework-specific details and templates
  - Deployment recommendations
  - Troubleshooting guide
  - Installation with jcpan

- **`FRAMEWORK_TEST_GUIDE.md`** - Navigation guide
  - Quick reference for what to read
  - Summary of consolidation
  - Test script locations

## Running Tests

From project root:

```bash
# Run compatibility test
./jperl examples/http_server_plack/framework_test_updated.pl

# Run examples
./jperl examples/http_server_plack/dancer_example.pl
./jperl examples/http_server_plack/catalyst_runtime_test.pl
```

## Examples Directory

All runnable examples and general documentation are in:
```
examples/http_server_plack/
├── README.md                      (General reference)
├── PERFORMANCE.md                 (Benchmarks)
├── test.pl                        (Basic PSGI)
├── dancer_example.pl              (Dancer2)
├── catalyst_runtime_test.pl       (Catalyst)
├── framework_test_updated.pl      (Compatibility test)
├── test_https.pl                  (HTTPS)
└── test_streaming.pl              (Streaming)
```

See `examples/http_server_plack/README.md` for general usage.

## Key Findings

1. **Multiple frameworks supported** - Dancer2 and Catalyst work out-of-the-box
2. **High performance** - 20,000-25,000 requests/second
3. **Production ready** - Suitable for deployment with load balancing
4. **Simple integration** - Just export to PSGI and run with Netty handler

## Recommendation

**Use Dancer2** for new projects - simplest, best documented, excellent performance.

**Alternative: Catalyst** for complex enterprise applications needing extensive features.

See `FRAMEWORK_TEST_RESULTS.md` for detailed information.
