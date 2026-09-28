use strict;
use warnings;
use Test::More;

is(<DATA>, "qualified marker payload\n", 'CORE-qualified data marker populates DATA');

done_testing;

CORE::__DATA__
qualified marker payload
