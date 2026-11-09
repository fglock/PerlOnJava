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

package MyTestApp::Controller;
use Mojo::Base 'Mojolicious::Controller';
sub DESTROY { shift->stash->{destroyed} = 1 }

package main;
app->controller_class('MyTestApp::Controller');

get '/render' => sub {
    my $c = shift;
    my $stream = Mojo::IOLoop->stream($c->tx->connection);
    $c->on(finish => sub {
        shift->stash->{writing} = $stream->is_writing;
    });
    $c->render_later;
    Mojo::IOLoop->next_tick(sub {
        $c->render(msg => 'Finish!');
    });
};

my $stash;
my $test = Test::Mojo->new;
$test->app->plugins->once(before_dispatch => sub { $stash = shift->stash });
$test->get_ok('/render')->status_is(200)->content_is('Finish!');

my $wait_expired = 0;
my $timer = Mojo::IOLoop->timer(5 => sub { $wait_expired = 1 });
Mojo::IOLoop->one_tick while !$stash->{destroyed} && !$wait_expired;
Mojo::IOLoop->remove($timer);
ok($stash->{destroyed}, 'controller is destroyed after deferred rendering');
ok(!$stash->{writing}, 'finish callback sees a drained response');

done_testing;

__DATA__
@@ render.html.ep
<%= $msg %>\
