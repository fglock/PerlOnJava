use strict;
use warnings;
use Test::More;

my $result = eval { my @sorted = sort { goto missing_label } 1, 2; 1 };
ok(!defined($result), 'goto from a sort block fails');
like($@, qr/^Can't "goto" out of a pseudo block/, 'sort block reports its pseudo-block boundary');

done_testing();
