#!/usr/bin/env perl
use strict;
use warnings;
no warnings 'once';
use Test::More;
use IPC::Open3;
use File::Temp ();
use Symbol qw(gensym);

plan tests => 9;

SKIP: {
    skip 'signal wait status is not available on native Windows', 2
        if $^O eq 'MSWin32';

    my ($stdin, $stdout, $stderr) = (undef, undef, gensym);
    my $pid = open3($stdin, $stdout, $stderr, $^X, '-e', 'kill 9, $$');
    close $stdin;
    local $SIG{ALRM} = sub { die "signal child timed out\n" };
    alarm 10;
    is(waitpid($pid, 0), $pid, 'signal-terminated child is reaped');
    alarm 0;
    is($? & 127, 9, 'wait status preserves signal 9');
    close $stdout;
    close $stderr;
}

{
    my ($stdin, $stdout, $stderr) = (undef, undef, gensym);
    my $pid = open3($stdin, $stdout, $stderr, $^X, '-e', 'exit 129');
    close $stdin;
    is(waitpid($pid, 0), $pid, 'child exiting with 129 is reaped');
    is($? >> 8, 129, 'normal exit 129 remains an exit status');
    is($? & 127, 0, 'normal exit 129 is not reported as a signal');
    close $stdout;
    close $stderr;
}

{
    my ($stdin, $stdout, $stderr) = (undef, undef, gensym);
    my $pid = open3($stdin, $stdout, $stderr, $^X, '-e', 'exit 255');
    close $stdin;
    is(waitpid($pid, 0), $pid, 'normally exiting child is reaped');
    is($? >> 8, 255, 'normal exit 255 remains an exit status');
    close $stdout;
    close $stderr;
}

my $temp_fh = File::Temp->new;
$temp_fh->autoflush(1);
$temp_fh->print("forwarded input\n");
$temp_fh->seek(0, 0);
my ($pid, $stdin, $stdout, $stderr) =
    Local::Open3Caller::run($temp_fh, $^X, '-ne', 'print');
undef $temp_fh;
my $output = do { local $/; <$stdout> };
close $stdout;
close $stderr;
is(waitpid($pid, 0), $pid, 'stdin redirected child is reaped');
is($output, "forwarded input\n", 'open3 forwards redirected filehandle input');
close TEMP;

done_testing;

{
    package Local::Open3Caller;
    use IPC::Open3 ();

    sub run {
        my ($input, @command) = @_;
        local *TEMP;
        open TEMP, '<&=', $input or die "duplicate input fixture: $!";
        my ($stdin, $stdout, $stderr) = ('<&TEMP', undef, Symbol::gensym());
        my $pid = IPC::Open3::open3($stdin, $stdout, $stderr, @command);
        return ($pid, $stdin, $stdout, $stderr);
    }
}
