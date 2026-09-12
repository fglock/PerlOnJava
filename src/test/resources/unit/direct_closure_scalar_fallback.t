use strict;
use warnings;
use Test::More tests => 3;

my $first = 10;
my $second = 20;
my $code = sub { $first + $second };

is($code->(), 30, 'captured numeric closure returns its scalar result');

# The emitted scalar call site may recognize the first closure, but the CODE
# scalar itself remains mutable. A replacement must take the ordinary call
# boundary rather than using the old closure's direct result.
$code = sub { 17 };
is($code->(), 17, 'replaced code reference takes scalar fallback');

my @values = $code->();
is_deeply(\@values, [17], 'replacement retains ordinary list context');
