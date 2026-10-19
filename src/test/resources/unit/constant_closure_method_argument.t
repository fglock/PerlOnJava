use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';

# Rose::HTML::Objects loads its message module with `use`, whose BEGIN-time
# setup calls add_message with read-only arguments and installs a constant CV.
use Issue1785::MethodMessages;

is(Issue1785::MethodMessages->GENERATED_ISSUE_1785(), 99999,
    'constant closure captures a read-only method argument during module BEGIN');

done_testing;
