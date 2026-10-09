#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Regression for #1703: the first load of mro under -w must not report
# mro::get_mro as redefined.  The mro primitives are registered at startup and
# again by mro.pm's XSLoader bootstrap; that re-registration is not a
# redefinition.  Test::More loads mro, so the first load runs in a fresh
# interpreter.

plan skip_all => 'uses sh -c to capture child stderr' if $^O eq 'MSWin32';

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $output = qx{$launcher -w -e 'require mro; print "loaded\\n"' 2>&1};

like($output, qr/loaded/, 'mro loads under -w');
unlike($output, qr/get_mro redefined/, 'loading mro under -w does not report get_mro as redefined');

is(mro::get_mro('main'), 'dfs', 'mro::get_mro still returns the default MRO');

done_testing();
