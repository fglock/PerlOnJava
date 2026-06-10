#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Fcntl qw(:flock);

sub exercise_flock {
    my ($fh, $filename) = tempfile(UNLINK => 1);
    print {$fh} "lock target\n";

    ok(flock($fh, LOCK_EX), 'exclusive flock succeeds');
    ok(flock($fh, LOCK_UN), 'exclusive flock unlock succeeds');
    ok(flock($fh, LOCK_SH), 'shared flock succeeds');
    ok(flock($fh, LOCK_UN), 'shared flock unlock succeeds');

    close $fh;
}

exercise_flock();

SKIP: {
    skip 'nested jperl interpreter check only runs under jperl', 2
        unless $^X =~ /jperl\z/ || $^X =~ m{/jperl\z};
    skip 'nested jperl interpreter check is unavailable on Windows', 2
        if $^O eq 'MSWin32';

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} <<'END_CHILD';
use strict;
use warnings;

my $tmpdir = $ENV{TMPDIR} || '/tmp';
my $filename = "$tmpdir/perlonjava_io_flock_$$.tmp";
open(my $fh, '>', $filename) or die "open $filename: $!";
print {$fh} "lock target\n";
flock($fh, 2) or die "exclusive flock failed: $!";
flock($fh, 8) or die "flock unlock failed: $!";
close $fh or die "close $filename: $!";
unlink $filename;
print "interpreter-flock-ok\n";
END_CHILD
    close $script_fh or die "close child script: $!";

    my ($out_fh, $out_name) = tempfile();
    open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
    open(my $saved_stderr, '>&', \*STDERR) or die "save stderr: $!";
    open(STDOUT, '>&', $out_fh) or die "redirect stdout: $!";
    open(STDERR, '>&', $out_fh) or die "redirect stderr: $!";
    my $status = system('timeout', '60', $^X, '--interpreter', $script_name);
    open(STDERR, '>&', $saved_stderr) or die "restore stderr: $!";
    open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
    close $saved_stderr;
    close $saved_stdout;

    seek($out_fh, 0, 0);
    my $output = do { local $/; <$out_fh> };
    close $out_fh;
    unlink $out_name;
    unlink $script_name;

    is($status, 0, 'interpreter flock script exits successfully')
        or diag $output;
    is($output, "interpreter-flock-ok\n", 'interpreter executes FLOCK opcode');
}

done_testing;
