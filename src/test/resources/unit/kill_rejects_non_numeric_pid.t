use strict;
use warnings;
use Test::More;

for my $case (
    [ undef, 'undef' ],
    [ '', 'empty string' ],
    [ 'abcd', 'alphabetic string' ],
) {
    my ($pid, $name) = @$case;
    eval { kill 0, $pid; 1 };
    like $@, qr/^Can't kill a non-numeric process ID/, "kill rejects $name";
}

my $pid_text = "$$ ";
$pid_text =~ /(\d+)/;
my $captured_pid = $1;
is kill(0, $captured_pid), 1, 'kill accepts a numeric capture value';

done_testing;
