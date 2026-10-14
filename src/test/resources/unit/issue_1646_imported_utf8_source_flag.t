use strict;
use warnings;
use lib 'src/test/resources/unit/lib';
use Test::More;

# Mojolicious::Base -strict imports utf8 into its caller this way.
use Issue1646::Utf8Base -strict;

my $heart = '♥';
is(length($heart), 1, 'utf8 imported by a base class decodes following source literals');
is(ord($heart), 0x2665, 'the imported UTF-8 literal has the expected code point');
ok(utf8::is_utf8($heart), 'the imported UTF-8 literal carries the Unicode flag');

done_testing;
