use strict;
use warnings;
use threads;
use threads::shared;

print "1..23\n";
my $n;
sub ok_ { my ($v, $name) = @_; ++$n; print($v ? "ok " : "not ok ", "$n - $name\n") }

my $self = threads->self;
ok_($threads::threads, 'threads capability marker');
ok_($self->tid == 0, 'main tid');
ok_($self->is_running, 'main running');
ok_(!$self->is_joinable, 'main not joinable');
ok_($self->is_detached, 'main detached');
ok_(threads->object(0) == $self, 'object finds main');
ok_(!defined threads->object(999999), 'object rejects unknown tid');

my $ready :shared = 0;
my $active = threads->create(sub { $ready = 1; sleep 1; return 4 });
1 until $ready;
my $alias = threads->object($active->tid);
ok_(defined($alias) && $alias == $active, 'object finds active child');
ok_($active->join == 4, 'active child joins');
ok_(!defined threads->object($active->tid), 'joined child is no longer active');

my $list = threads->create({ context => 'list' }, sub {
    return (threads->wantarray ? 'list' : 'wrong', 2, 3);
});
my @list_values = $list->join;
ok_(@list_values == 3 && $list_values[0] eq 'list', 'explicit list context');
ok_($list->wantarray, 'object reports list context');
my $scalar = threads->create({ scalar => 1 }, sub {
    return defined(threads->wantarray) && !threads->wantarray ? (5, 6) : 0;
});
ok_($scalar->join == 6, 'explicit scalar context');
ok_(defined($scalar->wantarray) && !$scalar->wantarray, 'object reports scalar context');
my $void = threads->create({ void => 1 }, sub { return 8 });
ok_(!defined($void->join), 'void context joins as undef');
ok_(!defined($void->wantarray), 'object reports void context');

my $detach_result :shared = 1;
my $detach_done :shared = 0;
my $detached = threads->create(sub {
    $detach_result = defined threads->detach;
    $detach_done = 1;
    return;
});
1 until $detach_done;
ok_($detached->is_detached, 'class detach marks child detached');
ok_(!$detach_result, 'class detach returns undef');

my $signal_ready :shared = 0;
my $seen :shared = 0;
my $signalled = threads->create(sub {
    $SIG{USR1} = sub { $seen = 1 };
    $signal_ready = 1;
    sleep 1 until $seen;
    return $seen;
});
1 until $signal_ready;
ok_($signalled->kill('USR1') == $signalled, 'kill returns thread object');
ok_($signalled->join == 1, 'signal handler runs in child');

my $old = threads->set_stack_size(1024 * 1024);
my $stacked = threads->create({ stack_size => 2 * 1024 * 1024 }, sub { return 1 });
ok_($stacked->get_stack_size >= 2 * 1024 * 1024, 'object reports requested stack size');
ok_($stacked->join == 1, 'stack-sized child joins');
threads->set_stack_size($old);

my $exited = threads->create({ exit => 'thread_only' }, sub { exit 7 });
ok_(!defined($exited->join) && !defined($exited->error), 'thread-only exit is normal');
