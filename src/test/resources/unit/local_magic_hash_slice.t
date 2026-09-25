use strict;
use warnings;
use Test::More;

{
    package LocalMagicHashSlice;
    m??; # Make the package stash magical, as in perl5_t/t/op/local.t.

    my @keys = <local_magic_hash_slice_missing_one local_magic_hash_slice_missing_two>;
    main::ok(!exists $LocalMagicHashSlice::{local_magic_hash_slice_missing_one},
        'glob pattern does not create an IO glob for its first word');
    main::ok(!exists $LocalMagicHashSlice::{local_magic_hash_slice_missing_two},
        'glob pattern does not create an IO glob for later words');
    {
        local @LocalMagicHashSlice::{@keys};
    }

    main::ok(!exists $LocalMagicHashSlice::{local_magic_hash_slice_missing_one},
        'local hash slice removes the first absent magic-stash key');
    main::ok(!exists $LocalMagicHashSlice::{local_magic_hash_slice_missing_two},
        'local hash slice removes every absent magic-stash key');
}

done_testing;
