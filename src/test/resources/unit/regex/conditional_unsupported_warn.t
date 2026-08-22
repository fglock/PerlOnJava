use strict;
use warnings;
use Test::More tests => 3;

my $pattern = eval q{qr/^(a(?(1)\1)){4}$/};

ok(defined $pattern, 'conditional inside a repeated group compiles natively');
is($@, '', 'native repeated conditional has no compile error');
unlike('aaaaaaaaa', $pattern,
    'conditional backreference semantics reject the anchored control');
