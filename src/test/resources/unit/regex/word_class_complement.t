#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

for my $code_point (0x7f, 0x80, 0xaa, 0xff, 0x100, 0x200c) {
    my $character = chr $code_point;
    utf8::upgrade($character);
    my $word = $character =~ /[\w]/ ? 1 : 0;
    my $not_word = $character =~ /[\W]/ ? 1 : 0;
    is($word + $not_word, 1,
       sprintf('U+%04X belongs to exactly one Unicode word class', $code_point));
    is($word, $character =~ /\w/ ? 1 : 0,
       sprintf('U+%04X has the same plain and class word result', $code_point));
}

for my $code_point (0x7f, 0x80, 0xaa, 0xff) {
    my $byte_character = chr $code_point;
    my $word = $byte_character =~ /[\w]/ ? 1 : 0;
    my $not_word = $byte_character =~ /[\W]/ ? 1 : 0;
    is($word + $not_word, 1,
       sprintf('byte U+%04X belongs to exactly one word class', $code_point));
    is($word, $byte_character =~ /\w/ ? 1 : 0,
       sprintf('byte U+%04X has the same plain and class word result', $code_point));
}

done_testing;
