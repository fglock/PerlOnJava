use strict;
use warnings;
use Test::More tests => 2;

sub caller_line {
    return (caller(0))[2];
}

sub consume($) { return $_[0] }

my $expected_nested = __LINE__ + 1;
my $nested = consume(
    'prefix ' . caller_line() . ' suffix'
);
like($nested, qr/\b$expected_nested\b/,
    'nested call reports the containing expression start line');

my $expected_plain = __LINE__ + 1;
my $plain = caller_line();
is($plain, $expected_plain, 'ordinary call still reports its own expression line');
