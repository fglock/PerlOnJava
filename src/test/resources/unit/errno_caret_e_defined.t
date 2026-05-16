#!/usr/bin/env perl

use strict;
use warnings;

use Test::More;

# $^E must be a real errno slot (like $!), not a fresh undef global — regression
# for File::Copy and anything using $^E + 0 under warnings.

my $warn = 0;
local $SIG{__WARN__} = sub { $warn++ };

my $n = $^E + 0;
is($warn, 0, '$^E + 0 triggers no uninitialized warning');

SKIP: {
    skip 'MSWin32 uses a separate Win32-error $^E from errno $!', 1 if $^O eq 'MSWin32';
    $! = 2;
    cmp_ok(0 + $^E, '==', 0 + $!, '$^E numeric matches $! after $! assignment (POSIX)');
}

done_testing;
