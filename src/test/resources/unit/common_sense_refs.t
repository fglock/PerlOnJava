use Test::More tests => 2;

use common::sense;

eval q{ ${"common_sense_symbolic_ref"} = 1 };
is($@, '', 'common::sense does not enable strict refs');
is($main::common_sense_symbolic_ref, 1, 'symbolic reference assignment worked');
