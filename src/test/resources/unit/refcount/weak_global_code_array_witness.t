#!/usr/bin/env perl
use strict;
use warnings;
no warnings qw(once redefine);

use Scalar::Util qw(weaken);
use Test::More tests => 9;

my ($weak_retained, $weak_new, $weak_replaced);

{
    my @owners = ({});
    weaken($weak_retained = $owners[0]);

    *main::hold_global_array_witness = sub { scalar @owners };
    *main::drop_global_array_witness = sub { pop @owners; scalar @owners };
}

is(hold_global_array_witness(), 1, 'global CODE closure retains its captured array slot');
ok(defined($weak_retained), 'captured array slot keeps its weak observer alive');
is(drop_global_array_witness(), 0, 'callback removes the captured owner slot');
ok(!defined($weak_retained), 'weak observer clears after the captured owner slot is removed');

{
    my @owners = ({});
    weaken($weak_new = $owners[0]);
    *main::hold_new_global_array_witness = sub { scalar @owners };
}

is(hold_new_global_array_witness(), 1, 'new global CODE closure runs');
ok(defined($weak_new), 'new weak candidate is retained through its current captured slot');

{
    my @owners = ({});
    weaken($weak_replaced = $owners[0]);
    *main::replace_global_array_witness = sub { scalar @owners };
}

is(replace_global_array_witness(), 1, 'replaceable global CODE closure runs');
ok(defined($weak_replaced), 'replaceable closure owns its captured value');
*main::replace_global_array_witness = sub { 0 };
ok(!defined($weak_replaced), 'replacing the CODE root releases its captured owner');
