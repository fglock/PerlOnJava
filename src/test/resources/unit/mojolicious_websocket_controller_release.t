use strict;
use warnings;

BEGIN { $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll' }

use Test::More;

BEGIN {
  $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll';
  plan skip_all => 'Test::Mojo server setup is not available in the interpreter backend'
    if $ENV{JPERL_INTERPRETER};
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

package WebSocketLifetimeController;
use Mojo::Base 'Mojolicious::Controller';
sub DESTROY { $main::controller_destroyed = 1 }

package main;
our $timer_id;
our $controller_destroyed = 0;
app->controller_class('WebSocketLifetimeController');
websocket '/lifetime' => sub {
  my $controller = shift;
  $timer_id = Mojo::IOLoop->recurring(0.05 => sub { $controller->send('tick') });
};

my $t = Test::Mojo->new;
$t->websocket_ok('/lifetime')->message_ok->message_is('tick');
ok(!$controller_destroyed, 'controller stays alive while its WebSocket is open');
Mojo::IOLoop->remove($timer_id);
$t->finish_ok;

my $wait_expired = 0;
Mojo::IOLoop->timer(0.25 => sub { $wait_expired = 1 });
Mojo::IOLoop->one_tick until $controller_destroyed || $wait_expired;
ok($controller_destroyed, 'controller is released after its WebSocket closes');
done_testing;
