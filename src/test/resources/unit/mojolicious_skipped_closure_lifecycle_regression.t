use strict;
use warnings;
use File::Spec;
use Test::More;

BEGIN {
    $0 = File::Spec->rel2abs("src/test/resources/$0")
        if !-f $0 && -f "src/test/resources/$0";
    $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll';
    plan skip_all => 'Test::Mojo server setup is not available in the interpreter backend'
        if $ENV{JPERL_INTERPRETER};
    eval {
        require Mojolicious::Lite;
        Mojolicious::Lite->import;
        require Test::Mojo;
        1;
    } or plan skip_all => 'Mojolicious and Test::Mojo are required';
}

app->secrets(['issue-1115-secret']);

get '/session' => sub {
    my $c = shift;
    $c->render(text => 'user:' . ($c->session->{user} // 'nobody'));
};

my $t = Test::Mojo->new;

subtest before_skip => sub {
    $t->get_ok('/session')->status_is(200)->content_is('user:nobody');
};

subtest skipped_capture => sub {
    plan skip_all => 'exercise cleanup of a skipped callback';
    $t->reset_session;
};

subtest after_skip => sub {
    $t->get_ok('/session')->status_is(200)->content_is('user:nobody');
};

done_testing;
