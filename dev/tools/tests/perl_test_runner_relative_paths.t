use strict;
use warnings;

use FindBin;
use Test::More;
use lib "$FindBin::Bin/../lib";
use PerlTestRunner::Scheduler qw(profile_for_test);
use PerlTestRunner::Timeouts qw(timeout_for_runner_test timeout_for_test);

is(timeout_for_test('re/anyof.t', 300), 2400,
    'root-relative anyof receives the policy completion floor');
is(timeout_for_runner_test('re/anyof.t', 300), 3600,
    'root-relative anyof receives the runner completion allowance');
is(timeout_for_runner_test('re/anyof_thr.t', 300), 3600,
    'root-relative threaded anyof receives the runner completion allowance');

my $profile = profile_for_test('re/anyof.t');
is($profile->{class}, 'heavy', 'root-relative anyof is scheduled as heavy');
is($profile->{weight}, 5, 'root-relative anyof consumes half the UAT budget');

done_testing;
