use strict;
use warnings;
use Test::More tests => 1;

$SIG{ALRM} = sub { $! = -1 };
alarm 1;
sleep 2;
isnt(0 + $!, -1, 'interrupted sleep restores EAGAIN after signal dispatch');
