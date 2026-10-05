use strict;
use warnings;
use Test::More;
use IPC::Open2;
use IO::Select;
use File::Temp qw(tempfile);

plan skip_all => 'requires POSIX process pipes and gzip'
    if $^O eq 'MSWin32';
my $gzip = grep { -x "$_/gzip" } split /:/, ($ENV{PATH} // '');
plan skip_all => 'gzip is not installed' unless $gzip;

# Keep two regular file descriptors open before the process pipes. MIME-tools
# uses this same filter shape: read the input while both writing to and reading
# from a child process.
open my $input, '<', __FILE__ or die "open test source: $!";
my ($output, $output_path) = tempfile();
my ($child_out, $child_in);
my $pid = open2($child_out, $child_in, 'gzip -c');
my $read_select = IO::Select->new($child_out);
my $write_select = IO::Select->new($child_in);
my $bytes_read = 0;
my $timed_out = 0;
my $iterations = 0;
my $writer_fd_survived_ready_loop = 0;
my $reader_fd_survived_ready_loop = 0;

while ($read_select->count || $write_select->count) {
    $iterations++;
    my ($read_ready, $write_ready) = IO::Select->select(
        $read_select, $write_select, undef, 1);
    if (!defined $read_ready && !defined $write_ready) {
        $timed_out = 1;
        diag("process pipe select timed out at iteration $iterations; "
            . $write_select->as_string . '; fileno='
            . (defined fileno($child_in) ? fileno($child_in) : 'undef'));
        last;
    }

    for my $fh (@{$read_ready // []}) {
        $reader_fd_survived_ready_loop ||= defined fileno($child_out);
        my $buffer;
        my $count = sysread($fh, $buffer, 1024);
        if ($count) {
            print {$output} $buffer or die "write compressed output: $!";
            $bytes_read += $count;
        } else {
            $read_select->remove($fh);
            close $fh;
        }
    }

    for my $fh (@{$write_ready // []}) {
        $writer_fd_survived_ready_loop ||= defined fileno($child_in);
        my $buffer;
        my $count = read($input, $buffer, 1024);
        if ($count) {
            syswrite($fh, $buffer) == $count or die "write gzip input: $!";
        } else {
            $write_select->remove($fh);
            close $fh;
        }
    }
}

close $child_in if $write_select->count;
close $child_out if $read_select->count;
waitpid($pid, 0);
ok(!$timed_out, 'IO::Select reports the process pipe writer ready');
ok($bytes_read > 0, 'the child gzip output was drained');
ok($writer_fd_survived_ready_loop, 'writer fileno remains registered while ready');
ok($reader_fd_survived_ready_loop, 'reader fileno remains registered while ready');
ok(!defined fileno($child_in), 'closing the writer unregisters its fileno');
ok(!defined fileno($child_out), 'closing the reader unregisters its fileno');
close $input;
close $output;
unlink $output_path;

done_testing();
