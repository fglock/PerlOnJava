use strict;
use warnings;
use Test::More tests => 4;

use re 'eval';

my $true_condition = qr/(?(?{ 1 })yes|no)/;
my $false_condition = qr/(?(?{ 0 })yes|no)/;
like('yes', $true_condition, 'executable conditional true branch');
like('no', $false_condition, 'executable conditional false branch');

my $grapheme = "e\x{301}";
is(scalar(() = $grapheme =~ /\X/g), 1, 'extended grapheme cluster');
ok("\x{1F600}" =~ /\p{Extended_Pictographic}/, 'Extended_Pictographic property');
