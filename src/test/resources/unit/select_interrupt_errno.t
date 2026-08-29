use strict;
use warnings;
use Test::More tests => 2;

pipe(my $reader, my $writer) or die "pipe: $!";
my $read_bits = '';
vec($read_bits, fileno($reader), 1) = 1;

local $SIG{ALRM} = sub { };
$! = 0;
alarm 1;
my $ready = select($read_bits, undef, undef, undef);
alarm 0;

is($ready, -1, 'an interrupted blocking select returns -1');
ok($!{EINTR}, 'an interrupted blocking select sets EINTR');
