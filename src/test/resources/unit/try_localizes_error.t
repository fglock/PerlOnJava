use strict;
use warnings;
use feature 'try';

use Test::More;

eval { die "outer error\n" };

my $caught;
try {
    die "inner error\n";
}
catch ($error) {
    $caught = $error;
    is($@, '', 'catch body sees an empty localized error variable');
}

is($caught, "inner error\n", 'catch parameter receives the exception');
is($@, "outer error\n", 'try restores the caller error variable');

done_testing;
