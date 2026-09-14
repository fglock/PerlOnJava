use strict;
use warnings;
use Test::More tests => 5;

is((~0x0f) & 0xff, 0xf0, 'numeric not-and masks the complemented native integer');
is((~0) & 0x7fffffff, 0x7fffffff, 'positive mask preserves low bits');
is((~0x12345678) & 0xffffffff, 0xedcba987, '32-bit word result remains unsigned');

my $left = 'A';
my $right = "\x0f";
is((~$left) & $right, ((~'A') & "\x0f"), 'string bitwise operands retain ordinary semantics');

my $tied = 3;
tie my $value, 'BitwiseNotAndTie', \$tied;
is((~$value) & 0xff, 0xfc, 'tied operand fetches through ordinary fallback');

package BitwiseNotAndTie;
sub TIESCALAR { bless { target => $_[1] }, $_[0] }
sub FETCH { ${ $_[0]{target} } }
