#!/usr/bin/env perl
# A nonblocking flock conflict is EAGAIN/EWOULDBLOCK on the host platform.
# Reporting EDEADLK makes lock-waiting callers such as DBICTest's await_flock
# misdiagnose ordinary contention as a deadlock.
use strict;
use warnings;
use Test::More tests => 2;
use Fcntl qw(:DEFAULT :flock);
use File::Spec;
use Errno qw(EAGAIN);

my $path = File::Spec->catfile(File::Spec->tmpdir, "perlonjava-flock-$$.lock");
END { unlink $path if defined $path && -e $path }

sysopen(my $shared, $path, O_RDWR | O_CREAT) or die "open shared lock: $!";
flock($shared, LOCK_SH) or die "acquire shared lock: $!";
sysopen(my $exclusive, $path, O_RDWR) or die "open exclusive lock: $!";
my $acquired = flock($exclusive, LOCK_EX | LOCK_NB);
my $errno = 0 + $!;

ok(!$acquired, 'nonblocking exclusive lock reports contention');
is($errno, EAGAIN, 'contention reports the host EAGAIN errno');

flock($shared, LOCK_UN);
close $shared;
close $exclusive;
