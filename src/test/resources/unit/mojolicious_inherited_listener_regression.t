use strict;
use warnings;
use Test::More;

BEGIN {
    eval {
        require IO::Socket::INET;
        require Mojo::IOLoop;
        require Mojo::Server::Daemon;
        require Mojolicious;
        1;
    } or plan skip_all => 'Mojolicious is not installed';
}

my $app = Mojolicious->new;
$app->routes->get('/port' => sub {
    my $c = shift;
    $c->render(text => $c->req->url->to_abs->port);
});

my $listener = IO::Socket::INET->new(
    Listen    => 5,
    LocalAddr => '127.0.0.1',
    Proto     => 'tcp',
) or die "listen: $!";
my $port = $listener->sockport;
my $daemon = Mojo::Server::Daemon->new(
    app    => $app,
    listen => ["http://127.0.0.1?fd=" . fileno($listener)],
    silent => 1,
)->start;

is $daemon->ports->[0], $port, 'daemon preserves the inherited listener port';
my ($response, $error, $finished);
my $loop = Mojo::IOLoop->singleton;
my $timer = $loop->timer(5 => sub {
    $error ||= 'timed out waiting for inherited listener response';
    $loop->stop;
});
my $id;
$id = $loop->client({address => '127.0.0.1', port => $port} => sub {
    my ($loop, $err, $stream) = @_;
    if ($err) {
        $error = $err;
        return $loop->stop;
    }
    $stream->on(read => sub {
        my ($stream, $chunk) = @_;
        $response .= $chunk;
        return unless $response =~ /\r?\n\r?\n.*\Q$port\E/s;
        $finished = 1;
        $loop->remove($id);
        $loop->stop;
    });
    $stream->write("GET /port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n");
});
$loop->start;
$loop->remove($timer);

ok !$error,   'request through inherited listener succeeds';
ok $finished, 'request through inherited listener receives a response';
like $response, qr/\r?\n\r?\n\Q$port\E\z/, 'request is served by inherited listener';

done_testing;
