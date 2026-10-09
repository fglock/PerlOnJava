use strict;
use warnings;
no warnings 'redefine';
use Test::More;

# caller() reports the package of the call site. Code compiled by eval STRING
# calls from the package it was compiled in, even when the callee lives in
# another package. The callee's package must not leak into the caller's frame.

package Lib;
sub who    { my $c = caller; return $c }
sub import { my $c = caller; return $c }

package main;

sub caller_of_main { my $c = caller; return $c }

is caller_of_main(), 'main', 'a direct call from main reports main';

my $direct = eval qq{package Pat;\n#line 1 "gen"\nsub { Lib::who() }} or die $@;
is $direct->(), 'Pat', 'a sub compiled in eval STRING reports its package to a callee in another package';

my $import = eval qq{package Pat;\n#line 1 "gen"\nsub { Lib->import() }} or die $@;
is $import->(), 'Pat', 'an import() call from eval STRING reports the compiling package';

is eval q{package Foo; Lib::who()}, 'Foo',
    'an eval top-level call reports the package declared inside the eval';

is eval q{package Foo; main::caller_of_main()}, 'Foo',
    'an eval top-level call into main reports the package declared inside the eval';

done_testing;
