use strict;
use warnings;
use Test::More tests => 15;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

for my $case (
    [ 'qr/(?<;name>match)/', 'Group name must start with a non-digit word character' ],
    [ 'qr/(?^-i:foo)/',      'Sequence (?^-...) not recognized' ],
    [ 'qr/(?^d:foo)/',       'Sequence (?^d...) not recognized' ],
    [ 'qr/(?^lu:foo)/',      'Regexp modifiers "l" and "u" are mutually exclusive' ],
    [ 'qr/(?da:foo)/',       'Regexp modifiers "d" and "a" are mutually exclusive' ],
    [ 'qr/(?lil:foo)/',      'Regexp modifier "l" may not appear twice' ],
    [ 'qr/(?aaia:foo)/',     'Regexp modifier "a" may appear a maximum of twice' ],
) {
    my ($source, $expected) = @$case;
    my $ok = eval "$source; 1";
    ok(!$ok, "$source is rejected");
    like($@, qr/^\Q$expected\E/, "$source reports the Perl diagnostic");
}

is(scalar @warnings, 0, 'fatal option diagnostics do not warn');
