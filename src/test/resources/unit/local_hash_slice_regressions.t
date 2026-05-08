#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

{
    my %methods = (
        method => 'from role',
        keep   => 'still here',
    );

    {
        local @methods{qw(method)};
        delete @methods{qw(method)};
        ok(!exists $methods{method}, 'localized plain hash slice key can be deleted inside scope');
    }

    is($methods{method}, 'from role', 'localized plain hash slice restores a deleted key');
    is(join(',', sort keys %methods), 'keep,method', 'localized plain hash slice restore keeps hash keys intact');
}

{
    my $key_a = 'PERLONJAVA_LOCAL_HASH_SLICE_A';
    my $key_b = 'PERLONJAVA_LOCAL_HASH_SLICE_B';
    my $key_c = 'PERLONJAVA_LOCAL_HASH_SLICE_C';

    local $ENV{$key_a} = 1;
    local $ENV{$key_b} = 2;
    delete $ENV{$key_c};

    {
        local @ENV{$key_a, $key_c};
        ok(exists $ENV{$key_a}, 'localized tied hash slice keeps existing key visible inside scope');
        ok(exists $ENV{$key_b}, 'localized tied hash slice leaves unrelated key visible inside scope');
        ok(exists $ENV{$key_c}, 'localized tied hash slice creates missing key inside scope');
    }

    ok(exists $ENV{$key_a}, 'localized tied hash slice restores existing key after scope');
    ok(exists $ENV{$key_b}, 'localized tied hash slice keeps unrelated key after scope');
    ok(!exists $ENV{$key_c}, 'localized tied hash slice removes originally missing key after scope');
}

done_testing;
