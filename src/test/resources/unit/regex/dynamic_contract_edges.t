use strict;
use warnings;
use Test::More;

my @warnings;
my $undef_matches;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $undef_matches = 'a' =~ /^a(??{ undef })$/;
}
ok($undef_matches, 'undef dynamic result is an empty pattern');
like(join('', @warnings), qr/^Use of uninitialized value/, 'undef warns at match time');

ok('a' =~ /^a(??{ '' })$/, 'empty dynamic result matches empty');

my $position;
ok('ab' =~ /^a(??{ $position = pos; 'b' })$/, 'dynamic result matches at current position');
is($position, 1, 'pos is the current dynamic entry offset');

my $repeat_count = 0;
ok('aa' =~ /^(?:(??{ ++$repeat_count; 'a' })){2}$/,
    'dynamic result executes inside a quantifier');
is($repeat_count, 2, 'dynamic expression executes for each quantifier entry');

my $choice_count = 0;
ok('ab' =~ /^(??{ ++$choice_count; 'ab|a' })b$/,
    'nested alternatives can backtrack before the outer suffix');
is($choice_count, 1, 'nested alternative backtracking does not reevaluate expression');

ok('a' =~ /^(??{ 'A' })$/i, 'returned string inherits outer modifiers');
my $strict_qr = qr/A/;
ok(!('a' =~ /^(??{ $strict_qr })$/i), 'returned qr retains its own modifiers');

our $seed;
ok('seed' =~ /^(?{ $seed = 'seed' })(??{ $^R })$/,
    'dynamic expression sees prior callback result');

my $error = eval { 'x' =~ /^(??{ die "dynamic boom\n" })$/; 1 };
ok(!$error, 'exception aborts matching');
is($@, "dynamic boom\n", 'dynamic exception propagates unchanged');

my $invalid = eval { 'x' =~ /^(??{ '[' })$/; 1 };
ok(!$invalid, 'invalid returned pattern fails at match time');
like($@, qr/Unmatched \[/, 'invalid returned pattern reports compile error');

done_testing;
