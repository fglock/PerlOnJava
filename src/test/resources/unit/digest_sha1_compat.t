#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

eval {
    require Digest::SHA1;
    Digest::SHA1->import(qw(sha1 sha1_hex sha1_base64 sha1_transform));
    1;
} or plan skip_all => 'Digest::SHA1 required';

is(Digest::SHA1->new->add("abc")->hexdigest,
    "a9993e364706816aba3e25717850c26c9cd0d89d",
    "Digest::SHA1 object API uses SHA-1");

is(sha1("abc"), pack("H*", "a9993e364706816aba3e25717850c26c9cd0d89d"),
    "sha1 returns binary digest");

is(sha1_hex("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d",
    "sha1_hex returns hex digest");

is(sha1_base64("abc"), "qZk+NkcGgWq6PiVxeFDCbJzQ2J0",
    "sha1_base64 returns unpadded base64 digest");

is(sha1_transform(pack("H*", "dc71a8092d4b1b7b98101d58698d9d1cc48225bb")),
    pack("H*", "2e4c75ad39160f52614d122e6c7ec80446f68567"),
    "sha1_transform matches Digest::SHA1 vector");

my $digest = Digest::SHA1->new;
is($digest->hexdigest, "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    "empty digest works");

$digest->add("abc");
is($digest->clone->hexdigest, "a9993e364706816aba3e25717850c26c9cd0d89d",
    "clone preserves state");

$digest->add("d");
is($digest->hexdigest, "81fe8bfe87576c3ecb22426f8e57847382917acf",
    "continued add state matches SHA-1");

is($digest->hexdigest, "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    "digest resets after read");

done_testing;
