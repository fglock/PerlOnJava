#!/usr/bin/env perl
use strict;
use warnings;

use Errno qw(EAGAIN EWOULDBLOCK);
use Test::More;

pipe(my $rd, my $wr) or die "pipe failed: $!";

$wr->blocking(0);

my $total = 0;
my $writes = 0;
my $written;

while (1) {
    $written = syswrite($wr, "X" x 4096);
    last unless defined $written;

    $total += $written;
    $writes++;
    die "nonblocking pipe did not fill" if $writes > 1000;
}

ok($total > 0, 'nonblocking pipe accepted initial writes');
ok(!defined($written), 'nonblocking pipe syswrite returns undef when full');
ok($! == EAGAIN || $! == EWOULDBLOCK, 'nonblocking pipe syswrite reports EAGAIN when full');

my $wvec = "";
vec($wvec, fileno($wr), 1) = 1;
is(select(undef, $wvec, undef, 0), 0, 'full nonblocking pipe is not write-ready');

my $buf = "";
my $read = sysread($rd, $buf, 8192);
ok($read > 0, 'pipe can be drained after EAGAIN');

$wvec = "";
vec($wvec, fileno($wr), 1) = 1;
ok(select(undef, $wvec, undef, 0) >= 1, 'drained nonblocking pipe is write-ready');

$written = syswrite($wr, "done");
ok(defined($written) && $written > 0, 'nonblocking pipe syswrite resumes after drain');

pipe(my $print_rd, my $print_wr) or die "print pipe failed: $!";
$print_wr->blocking(0);

my $printed;
for (1 .. 1000) {
    $printed = $print_wr->write("X" x 4096);
    last unless defined $printed;
}

ok(!defined($printed), 'nonblocking pipe write method returns undef when full');
ok($! == EAGAIN || $! == EWOULDBLOCK, 'nonblocking pipe write method reports EAGAIN when full');

pipe(my $empty_rd, my $empty_wr) or die "second pipe failed: $!";
$empty_rd->blocking(0);

my $empty = read($empty_rd, my $empty_buf, 1);
ok(!defined($empty), 'nonblocking pipe read returns undef when empty');
ok($! == EAGAIN || $! == EWOULDBLOCK, 'nonblocking pipe read reports EAGAIN when empty');

pipe(my $closed_rd, my $closed_wr) or die "closed pipe failed: $!";
close $closed_rd;
local $SIG{PIPE} = 'IGNORE';
my $broken = syswrite($closed_wr, "x");
ok(!defined($broken), 'pipe syswrite returns undef after reader closes');
like("$!", qr/Broken pipe/i, 'pipe syswrite reports Broken pipe after reader closes');

done_testing;
