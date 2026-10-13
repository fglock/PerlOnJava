use strict;
use warnings;
use Test::More;

use lib 'src/test/resources/unit/issue_1630_forward_coderef_alias';
use Issue1630ForwardAlias::Config;

is(Issue1630ForwardAlias::Property::convert('value'), 'converted:value',
    'nested module preserves a CODE alias to a later named sub');

done_testing;
