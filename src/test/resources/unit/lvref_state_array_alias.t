use strict;
use warnings;
use feature qw(refaliasing state);
no warnings 'experimental::refaliasing';
use Test::More;

my $value = 3;
my @iterations = (1, 2);
for (@iterations) {
    \state(@state) = \$value if $_ == 1;
    if ($_ == 2) {
        die 'state array refalias did not survive a later iteration'
                unless scalar @state == 1;
        die 'state array does not hold the aliased referent'
                unless $state[0] == $value;
    }
}

pass 'state array refalias checks completed';
done_testing;
