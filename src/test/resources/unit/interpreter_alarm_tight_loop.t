use strict;
use warnings;
use Test::More;

local $SIG{ALRM} = sub { die "alarm fired\n" };

for my $iteration (1, 2) {
    alarm 1;
    eval { 1 while 1 };
    alarm 0;

    is($@, "alarm fired\n", "alarm $iteration interrupts an interpreter tight loop");
}

done_testing;
