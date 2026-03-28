# jcpan Blog Post

Blog post about the `jcpan` CPAN client for PerlOnJava.

## Status

Draft - ready for review

## Style Notes

Based on previous posts at https://blogs.perl.org/users/flavio_s_glock/:
- Short, focused (~300-500 words)
- Working code examples with actual output
- Technical but accessible
- Tags: Java, PerlOnJava (previously Perlito)

Previous posts (2015-2017) covered Perlito5. This is the first post about PerlOnJava with jcpan.

## Test Results

- **Moo**: 809/840 subtests passed (96.3%) - failures are due to unimplemented `weaken`/`DESTROY`
- **DateTime**: 3589/3589 subtests passed (100%)

## Files

- `blog-post-long.md` - **Main blog post** (~1500 words, polished, shareable)
- `blog-post.md` - Short version (~130 lines, blogs.perl.org style)
- `example_datetime.pl` - Verified DateTime example script
- `example_moo.pl` - Verified Moo example script
- `benchmark_results.md` - Performance benchmark results (PerlOnJava 2.1x faster)
- `jruby_jython_reference.md` - Research on how JRuby/Jython present themselves in blogs
- `README.md` - This file

## Related Resources

- [User Guide: Using CPAN Modules](../../../docs/guides/using-cpan-modules.md)
- [Dev: CPAN Client Support](../../modules/cpan_client.md)
- [Dev: DateTime Fixes](../../modules/JCPAN_DATETIME_FIXES.md)
