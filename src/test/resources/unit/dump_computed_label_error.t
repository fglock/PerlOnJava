use strict;
use warnings;
use Test::More;

my $label = 'missing_label';
eval { CORE::dump $label; 1 };

like($@, qr/Can't find label missing_label at .* line \d+\./,
    'dump reports the evaluated missing label');

done_testing;
