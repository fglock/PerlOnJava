use strict;
use warnings;

BEGIN { $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll' }

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

package LifetimeController;
use Mojo::Base 'Mojolicious::Controller';
sub DESTROY { shift->stash->{destroyed} = 1 }
package main;
app->controller_class('LifetimeController');
websocket '/push' => sub {
  my $c = shift;
  my $id = Mojo::IOLoop->recurring(0.1 => sub { $c->send('push') });
  $c->on(finish => sub { Mojo::IOLoop->remove($id) });
};
get '/longpoll' => sub {
  my $c = shift;
  $c->on(finish => sub { shift->stash->{finished} = 1 });
  $c->write_chunk('P' => sub {
    shift->write_chunk('oll!' => sub { shift->write_chunk('') });
  });
};
get '/plugin/upper_case' => {text => 'WELCOME aboard!'};
get '/plugin/camel_case' => {text => 'Welcome aboard!'};
get '/staged' => sub {
  my $c = shift;
  return $c->render(text => 'Go away!') unless $c->req->headers->header('X-Pass');
  $c->render(text => 'Welcome aboard!');
};
get '/suspended' => sub {
  my $c = shift;
  $c->res->headers->header('X-Suspended' => '0');
  Mojo::IOLoop->next_tick(sub {
    $c->res->headers->append('X-Suspended' => '1, 1, 2');
    $c->render(text => '<p>Have fun!</p>');
  });
};

my $t = Test::Mojo->new;
$t->websocket_ok('/push')->message_ok->message_is('push')->finish_ok;
$t->get_ok('/plugin/upper_case')->status_is(200)->content_is('WELCOME aboard!');
$t->get_ok('/plugin/camel_case')->status_is(200)->content_is('Welcome aboard!');
$t->get_ok('/staged' => {'X-Pass' => 1})->status_is(200)->content_is('Welcome aboard!');
$t->get_ok('/staged')->status_is(200)->content_is('Go away!');
$t->get_ok('/suspended')->status_is(200)->content_is('<p>Have fun!</p>');
my $stash;
$t->app->plugins->once(before_dispatch => sub { $stash = shift->stash });
$t->get_ok('/longpoll')->status_is(200)->content_is('Poll!');
Mojo::IOLoop->one_tick until $stash->{finished};
is($stash->{finished}, 1, 'long-poll response has finished');
ok($stash->{destroyed}, 'long-poll controller is destroyed after response finish');
done_testing;
