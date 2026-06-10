use strict;
use warnings;
use Test::More tests => 6;

sub close_pipe_status {
    my ($exit) = @_;
    open my $fh, "|$^X -e \"exit $exit\"" or die "open pipe: $!";
    my $ok = close $fh;
    return ($ok ? 1 : 0, $? >> 8);
}

my ($ok0, $status0) = close_pipe_status(0);
is($ok0, 1, 'close returns true for zero pipe exit');
is($status0, 0, 'zero pipe exit is stored in $?');

my ($ok1, $status1) = close_pipe_status(1);
is($ok1, 0, 'close returns false for exit 1 pipe');
is($status1, 1, 'exit 1 pipe status is stored in $?');

my ($ok2, $status2) = close_pipe_status(2);
is($ok2, 0, 'close returns false for exit 2 pipe');
is($status2, 2, 'exit 2 pipe status is stored in $?');
