use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';
use Issue1377::Levels;

is(INFO, 6, 'bare imported AUTOLOAD constant resolves to its value');
is(INFO(), 6, 'parenthesized imported AUTOLOAD constant resolves to its value');

done_testing;
