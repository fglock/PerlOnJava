use strict;
use warnings;
BEGIN { $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll' }

use Test::More;

BEGIN {
    plan skip_all => 'Mojolicious is not installed'
        unless eval { require Mojolicious::Lite; require Test::Mojo; require Mojo::IOLoop; 1 };
}

use Mojolicious::Lite;
use Test::Mojo;
use Mojo::IOLoop;

{
    package Issue1646::Controller;
    use Mojo::Base 'Mojolicious::Controller';

    sub DESTROY { shift->stash->{destroyed} = 1 }
}

app->controller_class('Issue1646::Controller');
get '/longpoll' => sub {
    my $controller = shift;
    $controller->on(finish => sub { shift->stash->{finished}++ });
    $controller->write_chunk('P' => sub {
        shift->write_chunk('oll!' => sub { shift->write_chunk('') });
    });
};

my $test = Test::Mojo->new;
my $stash;
$test->app->plugins->once(before_dispatch => sub { $stash = shift->stash });
$test->get_ok('/longpoll')->status_is(200)->content_is('Poll!');
Mojo::IOLoop->one_tick until $stash->{finished};
is($stash->{finished}, 1, 'finish event is emitted once');
ok($stash->{destroyed}, 'controller is destroyed after the response completes');

done_testing;
