use strict;
use warnings;
use Test::More;

use feature 'class';
no warnings 'experimental::class';

class DirectMethodInvocant {
    method instance_only { 'unreachable' }
    sub class_helper { 'class helper' }
}

my $ok = eval { DirectMethodInvocant::instance_only(); 1 };
ok(!$ok, 'a class method rejects a direct call without an instance');
like($@, qr/Cannot invoke method "instance_only" on a non-instance/,
    'direct class-method call reports the Perl-compatible diagnostic');

is(DirectMethodInvocant->class_helper, 'class helper',
    'an ordinary sub declared in a class remains callable as a class helper');

done_testing;
