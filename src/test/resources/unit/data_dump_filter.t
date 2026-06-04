#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

package DataDumpFilterWatch;

sub DESTROY {
    ${$_[0]}->();
}

package main;

BEGIN {
    my $hook = sub {
        require Data::Dump::Filtered;
        Data::Dump::Filtered::add_dump_filter(sub {
            my ($ctx, $obj) = @_;
            return undef unless $ctx->class eq 'Local::Point';
            return {
                dump => sprintf(
                    'Local::Point(%s, %s)',
                    Data::Dump::dump($obj->[0]),
                    Data::Dump::dump($obj->[1]),
                ),
            };
        });
    };
    $Data::Dump::DEBUG = bless \$hook, 'DataDumpFilterWatch';
}

use Data::Dump qw(pp);

my $point = bless [10, 20], 'Local::Point';
is(pp($point), 'Local::Point(10, 20)', 'late Data::Dump filter hook formats blessed object');

done_testing();
