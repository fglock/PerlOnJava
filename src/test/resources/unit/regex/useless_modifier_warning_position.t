use strict;
use warnings;
use Test::More tests => 7;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = eval q{qr/(?ogc)/};
    ok($compiled, 'modifier-only group compiles');
}

is(scalar @warnings, 3, 'one warning is emitted per useless modifier');
like($warnings[0], qr/Useless \(\?o\).*\(\?o <-- HERE gc\)/s,
     'o warning is marked after o');
like($warnings[1], qr/Useless \(\?g\).*\(\?og <-- HERE c\)/s,
     'g warning is marked after g');
like($warnings[2], qr/Useless \(\?c\).*\(\?ogc <-- HERE \)/s,
     'c warning is marked after c');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = eval q{qr/(?-cgo)/};
    ok($compiled, 'negative modifier group compiles');
}
is(scalar @warnings, 2, 'c suppresses the redundant g warning but not o');
