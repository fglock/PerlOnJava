use strict;
use warnings;
use Test::More;

for my $pattern ('a**', '.{1}??', '.{1}?+', '(?i:a**)') {
    my ($regex, @warnings);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $regex = eval { qr/$pattern/ };
    }
    ok(!defined($regex), "$pattern is rejected");
    like($@, qr/^Nested quantifiers/, "$pattern uses Perl nested-quantifier diagnostic");
}

for my $case (
    [ 'x(~~)*(?:(?:F)?)?', 'x~~', undef, 1 ],
    [ '(?:r?)*?r|(.{2,4})', 'abcde', 'abcd', 1 ],
    [ '^(.)(?:(.)+)*[BX]', 'ABCDE', undef, 1 ],
    [ '(?x:( a | ( bc ) ) {0,0} ? xyz)', 'xyz', undef, 0 ],
    [ '(?x:( a | ( bc ) ) {0,0} + xyz)', 'xyz', undef, 0 ],
) {
    my ($pattern, $subject, $capture, $requires_quiet) = @$case;
    my (@warnings, $regex);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $regex = eval { qr/$pattern/ };
    }
    ok(defined($regex) && $@ eq '' && (!$requires_quiet || !@warnings),
        "$pattern has Perl's legal grouped-quantifier compile policy");
    ok($subject =~ $regex, "$pattern retains match semantics");
    is($1, $capture, "$pattern retains capture semantics") if defined($capture);
}

done_testing;
