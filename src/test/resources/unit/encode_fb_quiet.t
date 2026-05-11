#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 10;
use Encode qw(encode decode find_encoding FB_QUIET LEAVE_SRC STOP_AT_PARTIAL);

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

my $utf8 = find_encoding("UTF-8");
my $partial = "\xc4\x8a\xc4";
my $decoded_partial = $utf8->decode($partial, STOP_AT_PARTIAL);
is($decoded_partial, "\x{10a}", "STOP_AT_PARTIAL decodes complete UTF-8 character");
is($partial, "\xc4", "STOP_AT_PARTIAL leaves incomplete trailing byte in source");

$partial .= "\x8b";
$decoded_partial = $utf8->decode($partial, STOP_AT_PARTIAL);
is($decoded_partial, "\x{10b}", "STOP_AT_PARTIAL completes deferred UTF-8 character");
is($partial, "", "STOP_AT_PARTIAL consumes source after completion");
