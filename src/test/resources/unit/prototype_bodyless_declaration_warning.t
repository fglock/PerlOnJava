use strict;
use warnings;
use Test::More tests => 1;

sub prototype_warning_target (&);

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval q{sub prototype_warning_target { return @_ }};
}

like($warning, qr/^Prototype mismatch: sub main::prototype_warning_target/,
    'a bodyless declaration warns when a definition changes its prototype');

