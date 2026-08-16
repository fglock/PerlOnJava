use strict;
use warnings;
use Test::More tests => 4;

use Fcntl qw(O_ACCMODE O_RDONLY O_WRONLY O_RDWR);

is(O_ACCMODE, 3, 'O_ACCMODE is the POSIX access-mode mask');
is(O_RDONLY & O_ACCMODE, O_RDONLY, 'O_ACCMODE extracts read-only mode');
is(O_WRONLY & O_ACCMODE, O_WRONLY, 'O_ACCMODE extracts write-only mode');
is(O_RDWR & O_ACCMODE, O_RDWR, 'O_ACCMODE extracts read-write mode');
