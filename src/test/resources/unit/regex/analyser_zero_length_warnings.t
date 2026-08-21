use strict;
use warnings;
use Test::More tests => 4;

for my $pattern ('(?=a){1,3}\x{100}', '(a|b)(?=a){3}\x{100}') {
    my $warning = '';
    my $compiled;
    {
        local $SIG{__WARN__} = sub { $warning .= $_[0] };
        $compiled = eval "qr/$pattern/";
    }
    ok(defined $compiled, "$pattern compiles");
    like($warning, qr/Quantifier unexpected on zero-length expression/,
        "$pattern emits the zero-length quantifier warning");
}
