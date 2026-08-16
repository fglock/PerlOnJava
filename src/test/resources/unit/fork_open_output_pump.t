use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempfile);

my $capture_path = $ENV{PERLONJAVA_FORK_OPEN_CAPTURE};
if (!defined $capture_path) {
    my ($initial, $path) = tempfile();
    close $initial;
    $capture_path = $ENV{PERLONJAVA_FORK_OPEN_CAPTURE} = $path;
}
open my $capture, '>>', $capture_path or die "open capture: $!";
open my $saved_stdout, '>&', \*STDOUT or die "save stdout: $!";
open STDOUT, '>&', $capture or die "redirect stdout: $!";

# A replay child must not see the parent-to-child pipe before the fork point.
# Real-world filters commonly probe or drain their original STDIN while doing
# pre-fork setup.
my $pre_fork_input = <STDIN>;

my $pid = open STDOUT, '|-';
if (defined($pid) && !$pid) {
    exec 'cat';
    die "exec cat: $!";
}
if (defined $pid) {
    print STDOUT "pumped output\n";
    close STDOUT or die "close pipe: $!";
}

open STDOUT, '>&', $saved_stdout or die "restore stdout: $!";
close $capture;
ok(defined($pid) && $pid > 0, 'output fork-open creates a child');
open my $readback, '<', $capture_path or die "open capture: $!";
is(do { local $/; <$readback> }, "pumped output\n",
    'close drains child output before returning');
