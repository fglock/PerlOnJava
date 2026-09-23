use strict;
use warnings;
use Test::More tests => 2;

for my $strict ('', q{no warnings 'experimental::re_strict'; use re 'strict';}) {
    my $error = eval "$strict qr/(?[[\\]]\\x{00}])/; 1" ? '' : $@;
    like($error, qr/Operand with no preceding operator/,
         "escaped extended-class operands require an operator ($strict)");
}
