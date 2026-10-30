use strict;
use warnings;
use Test::More;

BEGIN {
  $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll';
  eval {
    require IO::Socket::INET;
    require Mojolicious::Lite;
    Mojolicious::Lite->import;
    require Test::Mojo;
    require Mojo::IOLoop;
    1;
  } or plan skip_all => 'Mojolicious and Test::Mojo are required';

  my $probe = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Listen    => 1,
    Proto     => 'tcp',
  ) or plan skip_all => 'loopback sockets are unavailable';
  close $probe;
}

package MojoliciousControllerDestroyAfterFinish;
use Mojo::Base 'Mojolicious::Controller';
sub DESTROY { shift->stash->{destroyed} = 1 }

package main;
app->controller_class('MojoliciousControllerDestroyAfterFinish');
get '/longpoll' => sub {
  my $controller = shift;
  $controller->on(finish => sub { shift->stash->{finished} = 1 });
  $controller->write_chunk('P' => sub {
    shift->write_chunk('oll!' => sub { shift->write_chunk('') });
  });
};

my $stash;
my $tick_count = 0;
my $timer = Mojo::IOLoop->recurring(0.1 => sub { ++$tick_count });
my $test = Test::Mojo->new;
$test->app->plugins->once(before_dispatch => sub { $stash = shift->stash });
$test->get_ok('/longpoll')->status_is(200)->content_is('Poll!');

Mojo::IOLoop->one_tick while !$stash->{finished} && $tick_count < 50;
is($stash->{finished}, 1, 'long-poll response finish event runs');

$tick_count = 0;
Mojo::IOLoop->one_tick while !$stash->{destroyed} && $tick_count < 50;
Mojo::IOLoop->remove($timer);
ok($stash->{destroyed}, 'controller is destroyed after response finish');

done_testing;
