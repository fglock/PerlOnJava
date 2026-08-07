#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Encode qw(_utf8_on is_utf8);

my $left  = "\xFF\xFE";
my $right = "\x01\x02";

my $and = $left & $right;
is(unpack('H*', $and), '0102', 'string AND preserves the expected octets');
ok(!is_utf8($and), 'string AND of byte operands keeps the UTF-8 flag off');

my $or = $left | $right;
is(unpack('H*', $or), 'fffe', 'string OR preserves the expected octets');
ok(!is_utf8($or), 'string OR of byte operands keeps the UTF-8 flag off');

my $xor = $left ^ $right;
is(unpack('H*', $xor), 'fefc', 'string XOR preserves the expected octets');
ok(!is_utf8($xor), 'string XOR of byte operands keeps the UTF-8 flag off');

my $not = ~$left;
is(unpack('H*', $not), '0001', 'string complement preserves the expected octets');
ok(!is_utf8($not), 'string complement returns a byte string');

my $utf8 = 'AB';
_utf8_on($utf8);
my $mixed = $utf8 ^ '12';
ok(is_utf8($mixed), 'string XOR keeps the UTF-8 flag when an operand has it');

my $utf8_not = ~$utf8;
ok(!is_utf8($utf8_not), 'string complement of a UTF-8 operand returns bytes');

done_testing();
