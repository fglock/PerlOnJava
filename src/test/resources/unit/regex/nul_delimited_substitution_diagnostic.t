use strict;
use warnings;
use Test::More tests => 2;

my $nul = "\0";
my $malformed = 'use warnings; s' . $nul
    . '0(?(?!00000000000000000000000000·000000)\500000000'
    . $nul
    . '0000000000000000000000000000000000000000000000000000'
    . '·00000000000000000000000000000000'
    . $nul
    . '0';

eval $malformed;
like($@, qr/Switch \(\?\(condition\)\.\.\. not terminated/,
    'NUL-delimited substitution reaches the native pattern diagnostic');

eval 'qr/a/0';
like($@, qr/Unknown regexp modifier "\/0"/,
    'numeric modifier on a valid pattern remains an error');
