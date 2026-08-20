use strict;
use warnings;
use Test::More;

for my $pattern (
    ':{4,a}',
    'xa{3\\,4}y',
    '\\${[^\\}]*}',
    '.{',
    '[x]{',
    '\\p{Latin}{',
) {
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        my $regex = eval { qr/$pattern/ };
        ok(defined($regex), "$pattern remains compilable");
    }
    is(scalar(@warnings), 1, "$pattern emits exactly one warning");
    like($warnings[0], qr/^Unescaped left brace in regex is passed through/,
        "$pattern retains the Perl warning text");
}

for my $pattern ('^{', 'foo|{') {
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $regex = eval { qr/$pattern/ };
    ok(defined($regex) && !@warnings,
        "$pattern remains an allowed quiet brace context");
}

{
    no warnings 'regexp';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $pattern = ':{4,a}';
    my $regex = eval { qr/$pattern/ };
    ok(defined($regex) && !@warnings,
        'regexp warning suppression applies to the single retained warning');
}

done_testing;
