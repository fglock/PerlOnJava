use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require AnyEvent; require AnyEvent::Tools; 1 }
        or plan skip_all => 'AnyEvent::Tools is not installed';
}

plan tests => 5;

AnyEvent::Tools->import(':mutex');

my $mutex = AnyEvent::Tools::rw_mutex();
my (@events, @reader_guards);
my $writer_guard;

$mutex->rlock(sub {
    push @events, 'reader 1';
    push @reader_guards, $_[0];
});
$mutex->rlock(sub {
    push @events, 'reader 2';
    push @reader_guards, $_[0];
});

is_deeply(\@events, ['reader 1', 'reader 2'],
    'both initial readers acquire before either releases');

$mutex->wlock(sub {
    push @events, 'writer';
    $writer_guard = $_[0];
});
$mutex->rlock(sub { push @events, 'reader 3' });

is_deeply(\@events, ['reader 1', 'reader 2'],
    'queued writer and reader wait while initial readers hold their guards');

undef $reader_guards[0];
is_deeply(\@events, ['reader 1', 'reader 2'],
    'writer waits until every active reader releases its guard');

undef $reader_guards[1];
is_deeply(\@events, ['reader 1', 'reader 2', 'writer'],
    'writer acquires before readers queued behind it');

undef $writer_guard;
is_deeply(\@events, ['reader 1', 'reader 2', 'writer', 'reader 3'],
    'queued reader acquires after the writer releases its guard');
