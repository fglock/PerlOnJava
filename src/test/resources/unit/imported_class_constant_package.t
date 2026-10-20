use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';

BEGIN { use_ok('CLASS'); }

package ImportedConstant;
use CLASS;
sub bar { 23 }

::is(CLASS, __PACKAGE__, 'imported CLASS constant retains its importing package');
::is(CLASS(), __PACKAGE__, 'parenthesized CLASS constant retains its importing package');
::is(CLASS->bar, 23, 'CLASS->method dispatch uses its importing package');

package main;
done_testing;
