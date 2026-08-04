use strict;
use warnings;
 use Test::More tests => 4;
use Storable qw(freeze);

my $code = sub { return 42 };
local $Storable::Deparse = 1;
 local $Storable::Eval = 1;
my $frozen = eval { freeze($code) };

ok(!$@, 'Storable can freeze a code reference with Deparse enabled');
ok(defined($frozen) && length($frozen), 'the frozen code reference is non-empty');

my $delimited = sub {
    my $text = q{a{b}};
    return $text;
};
my $delimited_frozen = eval { freeze($delimited) };
ok(!$@ && defined($delimited_frozen),
   'compiler span survives quote-like delimiters');

my $adjacent = sub { return 1 };
my $second = sub { return 2 };
my $adjacent_frozen = eval { freeze($adjacent) };
my $second_frozen = eval { freeze($second) };
ok(!$@ && defined($adjacent_frozen) && defined($second_frozen)
       && $adjacent_frozen ne $second_frozen,
   'compiler span selects the complete adjacent anonymous subroutine');
