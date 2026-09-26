use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

use integer;
my $matched;
given (3.14) { when (3) { $matched = 1 } }
ok $matched, 'integer given/when truncates numeric scalar operands';

$matched = 0;
given (3.14) { when ('3') { $matched = 1 } }
ok $matched, 'integer given/when truncates a numeric scalar against a string';

$matched = 0;
given ('3.14') { when (3) { $matched = 1 } }
ok $matched, 'integer given/when truncates a string against a numeric scalar';

$matched = 0;
given ('3.14') { when ('3') { $matched = 1 } }
ok !$matched, 'two strings retain string smartmatch semantics';

$matched = 0;
given ([3.14]) { when ([3]) { $matched = 1 } }
ok $matched, 'integer smartmatch applies recursively to array elements';

$matched = 0;
given (3.14) { when (qr/3\.14/) { $matched = 1 } }
ok $matched, 'integer smartmatch retains regex dispatch';

done_testing;
