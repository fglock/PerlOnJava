#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Regression: Perl regex \e must match ESC (0x1B). Text::ANSI::Util uses \e\[[0-9;]+m.

my $esc = "\e";
ok($esc =~ /\e/, 'm// matches ESC via \\e');
ok($esc =~ qr/\e/, 'qr// matches ESC via \\e');

my $ansi_reset = "\e[0m";
ok($ansi_reset =~ /\e\[[0-9;]+m/, 'ANSI SGR reset matches common detection regex');

done_testing();
