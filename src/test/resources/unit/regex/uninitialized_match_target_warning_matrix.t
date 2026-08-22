use strict;
use warnings;
use Test::More;

{
    no warnings 'uninitialized';
    my ($matched, @warnings);
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $target;
    $matched = $target =~ /x/;
    ok(!$matched, 'suppressed undef target does not match');
    is(scalar(@warnings), 0,
        'lexical no warnings suppresses the undef-target match warning');
}

{
    use warnings 'uninitialized';
    my ($matched, @warnings);
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $target;
    $matched = $target =~ /x/;
    ok(!$matched, 'enabled undef target does not match');
    is(scalar(@warnings), 1,
        'enabled uninitialized warnings emit once for an undef match target');
    like($warnings[0],
        qr/^Use of uninitialized value \$target in pattern match \(m\/\/\)/,
        'undef-target warning retains the Perl diagnostic');
}

{
    use warnings 'uninitialized';
    my ($matched, @warnings);
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $target = 'x';
    $matched = $target =~ /x/;
    ok($matched, 'defined target matches');
    is(scalar(@warnings), 0,
        'defined match target remains warning free');
}

done_testing;
