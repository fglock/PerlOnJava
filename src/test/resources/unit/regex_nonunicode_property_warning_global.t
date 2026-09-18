use strict;
use warnings;
use Test::More tests => 3;

my $input = chr(0x110000) . chr(0x110001);
my @warnings;
my $line = __LINE__ + 3;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $matches = () = $input =~ /\p{Unassigned}/g;
    is($matches, 2, 'global property search matches both non-Unicode scalars');
}

is(scalar @warnings, 4, 'global property search preserves every property warning');
like(join('', @warnings), qr/line $line\b/, 'warnings retain the match-use location');
