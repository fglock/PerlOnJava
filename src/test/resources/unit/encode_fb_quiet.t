#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 6;
use Encode qw(encode decode find_encoding FB_QUIET LEAVE_SRC);

my $enc_src = "abc";
my $encoded = encode("UTF-8", $enc_src, FB_QUIET);
is($encoded, "abc", "encode FB_QUIET returns encoded bytes");
is($enc_src, "", "encode FB_QUIET consumes source on success");

my $dec_src = "abc";
my $decoded = decode("UTF-8", $dec_src, FB_QUIET);
is($decoded, "abc", "decode FB_QUIET returns decoded string");
is($dec_src, "", "decode FB_QUIET consumes source on success");

my $leave_src = "abc";
is(decode("UTF-8", $leave_src, FB_QUIET | LEAVE_SRC), "abc",
   "decode FB_QUIET with LEAVE_SRC still decodes");
is($leave_src, "abc", "LEAVE_SRC preserves source");
