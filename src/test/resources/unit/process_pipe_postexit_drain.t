use strict;
use warnings;
use Test::More;
use IPC::Open3;
use IO::Select;
use Symbol qw(gensym);

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $error = gensym;
my $payload = 'x' x 65_536;
my $pid = open3(my $input, my $output, $error, $launcher, '-e',
    'print STDERR q(x) x 65536');
close $input;

my $selector = IO::Select->new($output, $error);
my $stdout_fd = fileno($output);
my $stderr_fd = fileno($error);
my %captured = ($stdout_fd => '', $stderr_fd => '');
while (my @ready = $selector->can_read) {
    for my $handle (@ready) {
        my $chunk = '';
        my $bytes = sysread $handle, $chunk, 8192;
        if (!defined($bytes) || $bytes == 0) {
            $selector->remove($handle);
            close $handle;
            next;
        }
        $captured{fileno($handle)} .= $chunk;
    }
}
waitpid $pid, 0;

is($?, 0, 'child exited successfully');
is($captured{$stderr_fd}, $payload,
    'select/sysread drains stderr after child exit');
is($captured{$stdout_fd}, '', 'child did not write stdout');

done_testing;

