#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use IO::File;
use IO::Select;
use IPC::Open2;

# Regression for #1597: MIME::Decoder::filter pumps a child process through
# open2() and IO::Select, copying each ready handle into a loop-scoped lexical.
# Leaving that scope must not drop the child's pipe from the fd table.  If it
# does, select() stops reporting the pipe and the filter stalls (JVM backend).

plan skip_all => 'uses tr(1) as the filter child' if $^O eq 'MSWin32';

# Same I/O shape as MIME::Decoder::filter.  The select timeout is finite so a
# regression fails instead of blocking the test run.
sub filter {
    my ($in, $out, @cmd) = @_;

    my $kidpid = open2(my $child_out, my $child_in, @cmd)
        or die "@cmd: open2 failed: $!";
    my $rsel = IO::Select->new($child_out);
    my $wsel = IO::Select->new($child_in);

    while (1) {
        my ($read, $write) = IO::Select->select($rsel, $wsel, undef, 10);
        die "@cmd: select stalled" if !defined $read && !defined $write;

        if (my $fh = shift @$read) {
            if ($fh->sysread(my $buf, 1024)) {
                $out->print($buf);
            } else {
                $rsel->remove($fh);
                $fh->close();
            }
        }

        if (my $fh = shift @$write) {
            if ($in->read(my $buf, 1024)) {
                $fh->syswrite($buf);
            } else {
                $wsel->remove($fh);
                $fh->close();
            }
        }

        last unless ($rsel->count() || $wsel->count());
    }

    waitpid($kidpid, 0) == $kidpid or die "@cmd: couldn't reap child $kidpid";
    $? == 0 or die "@cmd: bad exit status: \$? = $?";
    return 1;
}

# Several hundred 1 KiB chunks, so the select loop runs many iterations.
my $payload = join '', map { sprintf("line %05d the quick brown fox\n", $_) } 1 .. 4000;

my ($plain_fh, $plain_file) = tempfile(UNLINK => 1);
print {$plain_fh} $payload;
close $plain_fh;

my ($out_fh, $out_file) = tempfile(UNLINK => 1);
close $out_fh;

my $in = IO::File->new($plain_file, 'r') or die "open $plain_file: $!";
binmode $in;
my $out = IO::File->new($out_file, 'w') or die "open $out_file: $!";
binmode $out;

ok(eval { filter($in, $out, 'tr', 'a-z', 'A-Z'); 1 }, 'filter through open2 completes')
    or diag $@;
$out->close;

open my $got_fh, '<', $out_file or die "open $out_file: $!";
binmode $got_fh;
my $got = do { local $/; <$got_fh> };
close $got_fh;

is(length($got), length($payload), 'filtered output has the expected length');
is($got, uc($payload), 'filtered output matches the child transformation');

done_testing();
