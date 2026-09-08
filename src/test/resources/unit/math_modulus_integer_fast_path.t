use strict;
use warnings;
use Test::More tests => 8;

# INTEGER/INTEGER modulus is a hot arithmetic path.  These cases cover the
# result-sign rule and the values which must remain on the native-integer path.
is(7 % 3, 1, 'positive dividend and divisor');
is(-7 % 3, 2, 'positive divisor determines a negative dividend result sign');
is(7 % -3, -2, 'negative divisor determines a positive dividend result sign');
is(-7 % -3, -1, 'both negative operands preserve divisor sign');

my $large = 4_611_686_018_427_387_911;
is($large % 1_000_003, 837_681, 'large integer modulus remains exact');

my ($lexical, $global) = (11, 7);
for (1 .. 2_048) {
    $lexical = ($lexical * 33 + $_) % 1_000_003;
    $global = ($global + $lexical) % 1_000_003;
}
is($lexical ^ $global, 37_478, 'numeric workload recurrence remains stable');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    is(17 % 5, 2, 'ordinary integer modulus has the expected result with warnings enabled');
}
is_deeply(\@warnings, [], 'defined integer operands do not warn');
