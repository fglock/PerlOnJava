#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use IPC::Open2;
use IO::Select;
use Time::HiRes qw(time);

if (!grep { -x "$_/gzip" } split /:/, ($ENV{PATH} // '')) {
    plan skip_all => 'gzip is required for the process filter regression';
}

plan tests => 3;

# gzip does not produce output until stdin reaches EOF. IO::Select must keep
# reporting its writable stdin across polls while the decoder feeds it.
my $payload = 'process-select-regression-' x 300;
my $pid = open2(my $child_out, my $child_in, 'gzip', '-c');
my $read_set = IO::Select->new($child_out);
my $write_set = IO::Select->new($child_in);
my ($sent, $compressed, $timed_out) = (0, '', 0);
my $deadline = time() + 5;

while ($read_set->count || $write_set->count) {
    if (time() >= $deadline) {
        $timed_out = 1;
        last;
    }

    my ($readable, $writable) = IO::Select->select(
        $read_set, $write_set, undef, 0.2);
    if (my $fh = shift @{$readable // []}) {
        my $count = $fh->sysread(my $buffer, 4096);
        if ($count) {
            $compressed .= $buffer;
        } else {
            $read_set->remove($fh);
            close $fh;
        }
    }
    if (my $fh = shift @{$writable // []}) {
        if ($sent < length $payload) {
            my $chunk = substr($payload, $sent, 512);
            my $count = $fh->syswrite($chunk);
            $sent += $count if $count;
        } else {
            $write_set->remove($fh);
            close $fh;
        }
    }
}

close $child_in if $write_set->count;
close $child_out if $read_set->count;
waitpid($pid, 0);

is($sent, length($payload), 'all input was sent through the selected pipe');
ok(!$timed_out, 'process pipe selection made progress before timeout');
ok(length($compressed) > 0 && $? == 0, 'gzip produced output and exited successfully');
