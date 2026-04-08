# Ensure all public symbols in Net::SSLeay, Net::SSLeay::Handle, and our private
# Test:: modules are appropriately documented

use lib 'inc';

use Test::Net::SSLeay;

if (!$ENV{RELEASE_TESTING}) {
    plan skip_all => 'These tests are for only for release candidate testing. Enable with RELEASE_TESTING=1';
}
eval "use Test::Pod::Coverage 1.00";
if ($@) {
    plan skip_all => 'Test::Pod::Coverage >= 1.00 required for testing pod coverage';
} else {
    plan tests => 4;
}

pod_coverage_ok('Net::SSLeay');
pod_coverage_ok('Net::SSLeay::Handle');
pod_coverage_ok('Test::Net::SSLeay');
pod_coverage_ok('Test::Net::SSLeay::Socket');
