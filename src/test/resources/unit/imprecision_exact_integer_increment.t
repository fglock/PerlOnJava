use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    use warnings 'imprecision';

    my $exact = 9_007_199_254_740_992; # 2**53, exact as a Perl IV
    $exact++;
    is($exact, 9_007_199_254_740_993,
       'increment retains the exact integer past the floating precision boundary');
}
is_deeply(\@warnings, [], 'exact integer increment does not warn about imprecision');

{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    use warnings 'imprecision';

    my $floating = 1e300;
    $floating++;
}
like($warnings[-1], qr/Lost precision when incrementing/,
     'inexact floating increment retains the imprecision warning');

done_testing;
