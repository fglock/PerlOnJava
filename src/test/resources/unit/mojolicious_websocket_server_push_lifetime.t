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

package Issue1794WebSocketController;
use Mojo::Base 'Mojolicious::Controller';
sub DESTROY { ++$main::controller_destroyed }

package main;
app->controller_class('Issue1794WebSocketController');
websocket '/push' => sub {
    my $controller = shift;
    my $timer = Mojo::IOLoop->recurring(
        0.1 => sub { ++$main::push_count; $controller->send('push') }
    );
    $controller->on(finish => sub { Mojo::IOLoop->remove($timer) });
};

our $controller_destroyed = 0;
our $push_count = 0;
my $test = Test::Mojo->new;
$test->websocket_ok('/push')
    ->message_ok->message_is('push')
    ->finish_ok;

# Let the server process the WebSocket close and release the controller after
# the finish callback removes the recurring closure that captured it. The
# one-shot timer bounds the wait even if the close event is lost.
my $push_count_at_finish = $push_count;
my $wait_timer = Mojo::IOLoop->timer(0.5 => sub { Mojo::IOLoop->stop });
Mojo::IOLoop->start unless $controller_destroyed;
Mojo::IOLoop->remove($wait_timer);
is($push_count, $push_count_at_finish,
    'finish callback stops recurring WebSocket pushes');
ok($controller_destroyed > 0,
    'WebSocket server-push controller is destroyed after finish');

done_testing;
