#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

BEGIN {
    $INC{'indirect.pm'} = __FILE__;
    $INC{'multidimensional.pm'} = __FILE__;
    $INC{'bareword/filehandles.pm'} = __FILE__;
}

for my $pragma (qw(indirect multidimensional bareword::filehandles)) {
    ok($pragma->can('import'), "$pragma import exists without loading a .pm file");
    ok($pragma->can('unimport'), "$pragma unimport exists without loading a .pm file");
    ok(eval { $pragma->import; 1 }, "$pragma import can be called");
    ok(eval { $pragma->unimport; 1 }, "$pragma unimport can be called");
}

ok(eval { indirect->unimport(':fatal'); 1 }, 'indirect unimport accepts strictures-style arguments');

done_testing;
