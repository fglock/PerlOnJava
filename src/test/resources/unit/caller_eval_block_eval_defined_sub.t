use strict;
use warnings;
use Test::More tests => 8;

# caller(0) inside a sub reports that sub, also when the sub was compiled by
# eval STRING and is called from an eval BLOCK; (eval) is the next level out.

eval q{
    sub from_string {
        my @here = caller 0;
        my @outer = caller 1;
        return join ',', $here[3], (@outer ? $outer[3] : 'none');
    }
    1;
} or die $@;

is(eval { from_string() }, 'main::from_string,(eval)',
    'called from an eval block');
is(from_string(), 'main::from_string,none', 'called directly');
is(eval { eval { from_string() } }, 'main::from_string,(eval)',
    'called from nested eval blocks');

sub outer { eval { from_string() } }
is(outer(), 'main::from_string,(eval)', 'called from an eval block inside a sub');

sub plain { (caller 0)[3] }
is(eval { plain() }, 'main::plain', 'an ordinary sub called from an eval block');

my $anon = eval q{ sub { (caller 0)[3] } } or die $@;
is(eval { $anon->() }, 'main::__ANON__', 'an eval-compiled anonymous sub');

my $named = eval q{ sub named_in_string { (caller 0)[3] } named_in_string() } or die $@;
is($named, 'main::named_in_string', 'a named sub called inside its eval string');
is(named_in_string(), 'main::named_in_string', 'the same named sub called later');
