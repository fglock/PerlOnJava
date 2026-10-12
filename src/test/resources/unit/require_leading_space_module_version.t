use strict;
use warnings;
use Test::More tests => 2;

use lib 'src/test/resources/unit/lib';
use Issue1419LeadingSpaceVersion 4;

is($Issue1419LeadingSpaceVersion::VERSION, ' 4.079',
    'module version with legacy leading whitespace loads');
ok($INC{'Issue1419LeadingSpaceVersion.pm'}, 'versioned module is loaded');
