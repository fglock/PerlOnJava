# Ensure all pod-formatted documentation is valid

use lib 'inc';

use Test::Net::SSLeay;

# Starting with Net-SSLeay 1.88, the pod syntax uses constructs that are not
# legal according to older Test::Pod versions (e.g. 1.40, in RHEL 6).
# Here's a snippet from the Changes file for Test::Pod 1.41:
#   Test::Pod no longer complains about the construct L<text|url>, as it is no
#   longer illegal (as of Perl 5.11.3).
eval "use Test::Pod 1.41";
if ($@) {
    plan skip_all => "Test::Pod 1.41 required for testing pod";
}

all_pod_files_ok(qw(
    blib/lib/Net/SSLeay.pm
    blib/lib/Net/SSLeay/Handle.pm
    helper_script/generate-test-pki
    inc/Test/Net/SSLeay.pm
    inc/Test/Net/SSLeay/Socket.pm
));
