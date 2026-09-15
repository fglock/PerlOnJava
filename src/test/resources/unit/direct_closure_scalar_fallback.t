use strict;
use warnings;
use Test::More tests => 3;

my $first = 10;
my $second = 20;
my $code = sub { $first + $second };

is($code->(), 30, 'captured numeric closure returns its scalar result');

$code = sub { 17 };
is($code->(), 17, 'replaced code reference takes scalar fallback');

my @values = $code->();
is_deeply(\@values, [17], 'replacement retains ordinary list context');
