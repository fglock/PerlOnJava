use strict;
use warnings;
require Test::More;

Test::More::is(ref($^V), 'version', '$^V is a version object');
Test::More::like("$^V", qr/^v\d+(?:\.\d+){2,}$/,
    '$^V stringifies as its dotted version');
Test::More::ok(version->parse($^V) >= version->parse('5.14.0'),
    'version comparisons work with $^V');
Test::More::done_testing();
