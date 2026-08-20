use strict;
use warnings;
use Test::More tests => 5;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/[\8\9]\x{100}/};
}
is($@, '', 'unknown numeric class escapes remain nonfatal normally');
is(scalar @warnings, 2, 'each unknown numeric class escape warns');
like($warnings[0], qr/^Unrecognized escape \\8 in character class passed through/,
    'first numeric escape retains its spelling');
like($warnings[1], qr/^Unrecognized escape \\9 in character class passed through/,
    'second numeric escape retains its spelling');

{
    use re 'strict';
    local $SIG{__WARN__} = sub {};
    eval q{qr/[\8\9]\x{100}/};
}
like($@, qr/^Unrecognized escape \\8 in character class in regex/,
    're strict promotes the first numeric escape warning');
