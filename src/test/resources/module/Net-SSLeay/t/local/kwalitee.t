# Ensure module distribution passes Kwalitee checks

use lib 'inc';

use Test::Net::SSLeay;

if (!$ENV{RELEASE_TESTING}) {
    plan skip_all => 'These tests are for only for release candidate testing. Enable with RELEASE_TESTING=1';
}

require Test::Kwalitee;
Test::Kwalitee::kwalitee_ok();
