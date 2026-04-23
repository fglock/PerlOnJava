# Ensure all pod-formatted documentation is valid

use lib 'inc';

use Test::Net::SSLeay;

# Starting with Net-SSLeay 1.88, the pod syntax uses constructs that are not
# legal according to older Test::Pod versions (e.g. 1.40, in RHEL 6).
# Here's a snippet from the Changes file for Test::Pod 1.41:
#   Test::Pod no longer complains about the construct L<text|url>, as it is no
#   longer illegal (as of Perl 5.11.3).
# PerlOnJava: Net::SSLeay is an XS module that cannot be built, so the
# blib/ and helper_script/ directories do not exist.  When Test::Pod is
# installed (e.g. via CPAN into ~/.perlonjava/lib/) the test would run
# and report "not ok" for every missing file.  Skip unconditionally.
plan skip_all => "POD distribution test not applicable without a full build (no blib/ directory)";
