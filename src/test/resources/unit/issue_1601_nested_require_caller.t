use strict;
use warnings;
use Test::More;

use lib 'src/test/resources/unit/issue_1601_nested_require_caller';
use Data::Util;

is($Data::Util::PurePerl::REQUIRE_CALLER, 'Data::Util',
    'nested module require sees its requiring package at file scope');

done_testing;
