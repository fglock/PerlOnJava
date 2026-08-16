use strict;
use warnings;
use Test::More tests => 4;

my $blank = qr/[[:blank:]\h]/i;

ok(' ' =~ $blank, 'case-insensitive POSIX blank class matches space');
ok("\t" =~ $blank, 'case-insensitive POSIX blank class matches tab');
ok('k' !~ $blank, 'POSIX class name is not treated as class contents');
ok("\x{212a}" !~ $blank, 'Kelvin fold is not injected into POSIX class syntax');
