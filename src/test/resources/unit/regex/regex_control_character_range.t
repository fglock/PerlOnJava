use strict;
use warnings;
use Test::More tests => 4;

ok("\x00" =~ /[\c@-\c_]/, 'NUL is in the control-character range');
ok("\x1f" =~ /[\c@-\c_]/, 'unit separator is in the control-character range');
ok("\x20" !~ /[\c@-\c_]/, 'space is outside the control-character range');

my $controls = "\x20\x00\x30\x01\x40\x1a\x50\x1f\x60";
is($controls =~ s/[\c@-\c_]//gr, "\x20\x30\x40\x50\x60",
   'substitution removes the complete control-character range');
