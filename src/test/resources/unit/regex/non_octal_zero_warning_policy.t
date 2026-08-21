use strict;
use warnings;
use Test::More;

for my $case (
    [ '\\08',     '\\0008' ],
    [ '\\018',    '\\0018' ],
    [ '[\\08]',   '\\0008' ],
    [ '[\\018]',  '\\0018' ],
) {
    my ($pattern, $resolved) = @$case;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        my $regex = eval { qr/$pattern/ };
        ok(defined($regex), "$pattern remains compilable");
    }
    is(scalar(@warnings), 1, "$pattern emits one warning");
    like($warnings[0],
        qr/^Non-octal character '8' terminates \\0 early\.  Resolved as "\Q$resolved\E" in regex; marked by <-- HERE/,
        "$pattern reports Perl's resolved escape");
}

{
    no warnings 'regexp';
    local $SIG{__WARN__} = sub { fail('regexp warning suppression is honored') };
    my $pattern = '\\08';
    my $regex = eval { qr/$pattern/ };
    ok(defined($regex), 'suppressed non-octal warning leaves a valid regex');
}

done_testing;
