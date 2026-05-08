#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

my $input = "\x{3c3}foo.bar";
my $output;

($output = $input) =~ s/(\p{IsWord}+)/uc($1)/ge;
is($output, "\x{3a3}FOO.BAR", 'built-in IsWord property is not treated as an undefined user sub');
ok(!exists &main::IsWord, 'probing built-in IsWord does not create a user property CODE slot');

done_testing;
