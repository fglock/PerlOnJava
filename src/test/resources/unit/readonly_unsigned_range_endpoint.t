use strict;
use warnings;
use Test::More;

my $max_int = ~0 >> 1;
my $error;
eval {
    for ($max_int - 10 .. ~0) {
    }
};
$error = $@;

like($error, qr/Range iterator outside integer range/,
    'unsigned cached endpoint beyond IV range is rejected');

eval {
    for (~0 .. $max_int) {
    }
};
like($@, qr/Range iterator outside integer range/,
    'unsigned cached start beyond IV range is rejected');

done_testing();
