use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 4;

sub temporary_values {
    my $base = 1;
    $base + 1, $base + 4, $base + 6;
}

my @references = map { \$_ } temporary_values();
is(scalar @references, 3, 'map returns references to each temporary value');

for my $index (0 .. $#references) {
    weaken($references[$index]);
    ok(!defined $references[$index], 'temporary map input weakens away');
}
