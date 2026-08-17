use strict;
use warnings;

use Test::More tests => 2;
use Fcntl qw(O_BINARY O_TEXT);

is(O_BINARY, 0, 'O_BINARY is a portable no-op');
is(O_TEXT, 0, 'O_TEXT is a portable no-op');
