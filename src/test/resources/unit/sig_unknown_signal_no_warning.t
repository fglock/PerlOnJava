use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    no warnings 'signal';
    $SIG{HUNGRY} = 'mmm_pie';
}

is $SIG{HUNGRY}, 'mmm_pie',
    'an unknown signal name retains its handler when signal warnings are disabled';
is scalar @warnings, 0,
    q{no warnings 'signal' suppresses the unknown signal diagnostic};
is delete $SIG{HUNGRY}, 'mmm_pie',
    'an unknown signal entry remains deletable after suppressed assignment';

done_testing;
