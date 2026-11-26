use strict;
use warnings;
use Test::More tests => 1;
use lib 'src/test/resources/unit/lib';
use Issue2118::UnicodePropertyRuntimePackage;

ok(Issue2118::UnicodePropertyRuntimePackage->fold(),
    'deferred property in an interpolated runtime regex uses the defining package');
