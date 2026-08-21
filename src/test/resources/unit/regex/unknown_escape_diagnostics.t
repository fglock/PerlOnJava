use strict;
use warnings;
no warnings 'experimental::re_strict';
use Test::More tests => 10;

for my $case (
    [ '\y',  '\y' ],
    [ 'a\q', '\q' ],
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
        qr/^Unrecognized escape \Q$escape\E passed through in regex; marked by <-- HERE/,
        "$pattern warning retains the escape and position");

    @warnings = ();
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval "use re 'strict'; qr/$pattern/";
    }
    is($@, '', "$pattern remains a warning under re strict");
    like($warnings[-1],
        qr/^Unrecognized escape \Q$escape\E passed through in regex; marked by <-- HERE/,
        "$pattern retains its warning under re strict");
}
