use strict;
use warnings;
use feature 'state';
use Test::More;

{
    package Issue1644::UserAgentServer;

    sub app {
        my ($self, $app) = @_;
        state $singleton;

        # This mirrors Mojo::UserAgent::Server::app, which keeps the
        # Mojolicious::Lite application in a state scalar shared by class and
        # instance calls.
        return $singleton = $app ? $app : $singleton unless ref $self;
        return $self->{app} || $singleton unless $app;
        $self->{app} = $app;
        return $self;
    }
}

my $app = bless {}, 'Issue1644::Application';
Issue1644::UserAgentServer->app($app);

my $server = bless {}, 'Issue1644::UserAgentServer';
is($server->app, $app,
    'application stored by the class accessor survives for instance startup');
is($server->app, $app,
    'repeated application lookup preserves the same application object');

my $replacement = bless {}, 'Issue1644::ReplacementApplication';
Issue1644::UserAgentServer->app($replacement);
is($server->app, $replacement,
    'assigning a replacement updates the persistent application slot');

done_testing;
