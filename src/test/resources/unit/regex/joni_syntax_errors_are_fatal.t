use strict;
use warnings;
use Test::More tests => 7;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

for my $case (
    [ 'qr/(?;x/',          'invalid group introducer' ],
    [ 'qr/[z-a]/',         'invalid character range' ],
    [ 'qr/x{2147483648}/', 'oversized quantifier' ],
) {
    my ($source, $description) = @$case;
    my $ok = eval "$source; 1";
    ok(!$ok, "$description is rejected");
    ok(length($@), "$description populates the compile error");
}

is(scalar @warnings, 0, 'syntax errors are not downgraded to warnings');
