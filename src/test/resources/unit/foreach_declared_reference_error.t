use strict;
use warnings;
use Test::More;

use feature qw(refaliasing declared_refs);
no warnings qw(experimental::refaliasing experimental::declared_refs);

eval q{ foreach my \$item ('not a reference') { } 1 };
like($@, qr/^Assigned value is not a reference/, 'scalar declared-reference foreach rejects non-reference');

eval q{ foreach my \@item (\undef) { } 1 };
like($@, qr/^Assigned value is not an ARRAY reference/, 'array declared-reference foreach rejects scalar reference');

eval q{ foreach my \%item ([]) { } 1 };
like($@, qr/^Assigned value is not a HASH reference/, 'hash declared-reference foreach rejects array reference');

done_testing;
