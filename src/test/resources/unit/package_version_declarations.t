use strict;
use warnings;
use Test::More;

use v5.40;
pass('bare use v-version retains language-version semantics');

package PackageVersion::Padded v0.19.045;

package main;

is(
    $PackageVersion::Padded::VERSION,
    'v0.19.045',
    'package declarations accept and retain zero-padded dotted versions',
);

BEGIN {
    $INC{'Fixture/LargeVersion.pm'} = __FILE__;
    $Fixture::LargeVersion::VERSION = 'v20171214';
}

use Fixture::LargeVersion v20171214;
pass('use MODULE accepts a large bare v-version requirement');

package AI::Prolog::TermList::Clause;

package main;
pass('bare multi-component package declaration compiles');

done_testing;
