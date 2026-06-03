#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 8;
use Encode qw(_utf8_on _utf8_off is_utf8);

sub ords {
    return [ map { ord($_) } split //, $_[0] ];
}

my $s = "s\xC3\xA1";
ok(!is_utf8($s), 'raw UTF-8 octets start with UTF-8 flag off');
is_deeply(ords($s), [0x73, 0xC3, 0xA1], 'raw UTF-8 octets before _utf8_on');

my $was = _utf8_on($s);
ok(!$was, '_utf8_on returns previous false flag state');
ok(is_utf8($s), '_utf8_on turns UTF-8 flag on');
is_deeply(ords($s), [0x73, 0xE1], '_utf8_on decodes valid UTF-8 octets');

$was = _utf8_off($s);
is($was, 1, '_utf8_off returns previous true flag state');
ok(!is_utf8($s), '_utf8_off turns UTF-8 flag off');
is_deeply(ords($s), [0x73, 0xC3, 0xA1], '_utf8_off restores raw UTF-8 octets');
