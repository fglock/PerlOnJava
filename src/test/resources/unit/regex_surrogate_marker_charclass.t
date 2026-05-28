#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;

no warnings 'utf8';

my $surrogate = chr(0xD800);
my ($captured) = $surrogate =~ /([^\x00-\x7f])/;

ok(defined $captured, 'negated character class matches surrogate scalar');
is(ord($captured), 0xD800, 'capture preserves surrogate scalar code point');

my $replaced = $surrogate;
$replaced =~ s/([^\x00-\x7f])/sprintf('[%04X]', ord($1))/eg;
is($replaced, '[D800]', 'substitution consumes the whole surrogate marker');

my $pair = chr(0xD800) . chr(0xDFFF);
$pair =~ s/([^\x00-\x7f])/sprintf('%04X', ord($1))/eg;
is($pair, 'D800DFFF', 'global substitution handles adjacent surrogate markers');
