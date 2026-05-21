#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

use constant BIN => 'bin';

my @seen;
my $result = sub {
    my $path;
    BIN: for my $bin (0, 1) {
        push @seen, "before-$bin";
        $path = $bin and last BIN if $bin;
        push @seen, "after-$bin";
    }
    push @seen, 'after-loop';
    return $path;
}->();

is($result, 1, 'bare loop label is not shadowed by constant sub');
is_deeply(
    \@seen,
    ['before-0', 'after-0', 'before-1', 'after-loop'],
    'last LABEL exits the labeled loop when LABEL is also a constant',
);

my $dynamic_count = 0;
bin: for my $i (1) {
    $dynamic_count++;
    last(BIN);
    $dynamic_count = 99;
}
is($dynamic_count, 1, 'parenthesized constant remains a dynamic loop label expression');

done_testing();
