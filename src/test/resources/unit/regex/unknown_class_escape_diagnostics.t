use strict;
use warnings;
no warnings 'experimental::re_strict';
use Test::More tests => 8;

for my $case (
    [ '[\y]',  '\y' ],
    [ '[a\zb]', '\z' ],
) {
    my ($pattern, $escape) = @$case;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval "qr/$pattern/";
    }
    is($@, '', "$pattern compiles without re strict");
    is(scalar @warnings, 1, "$pattern emits one warning");
    like($warnings[0],
        qr/^Unrecognized escape \Q$escape\E in character class passed through in regex; marked by <-- HERE/,
        "$pattern warning retains the escape and position");

    eval "use re 'strict'; qr/$pattern/";
    like($@,
        qr/^Unrecognized escape \Q$escape\E in character class in regex; marked by <-- HERE/,
        "$pattern is fatal under re strict");
}
