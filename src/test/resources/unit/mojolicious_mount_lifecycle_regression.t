use strict;
use warnings;
use utf8;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

BEGIN {
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

my $external_home = tempdir(CLEANUP => 1);
make_path("$external_home/lib", "$external_home/script");

open my $module, '>', "$external_home/lib/MyApp.pm" or die $!;
print {$module} <<'APP';
package MyApp;
use Mojo::Base 'Mojolicious';
sub startup {
    my $self = shift;
    $self->routes->get('/secondary' => sub {
        my $c = shift;
        $c->render(text => ++$c->session->{secondary});
    });
}
1;
APP
close $module;

open my $script, '>', "$external_home/script/my_app" or die $!;
print {$script} <<'SCRIPT';
use strict;
use warnings;
use Mojo::File qw(curfile);
use lib curfile->dirname->sibling('lib')->to_string;
use Mojolicious::Commands;
Mojolicious::Commands->start_app('MyApp');
SCRIPT
close $script;

my $external = "$external_home/script/my_app";
plugin Mount => {'/x/1' => $external};
plugin Mount => {'/x/♥' => $external};
plugin Mount => {'MOJOLICIOUS.ORG/' => $external};
plugin Mount => {'*.foo-bar.de/♥/123' => $external};

get '/hello' => sub { shift->render(text => 'hello') };
get '/primary' => sub {
    my $c = shift;
    $c->render(text => ++$c->session->{primary});
};

my $t = Test::Mojo->new;
$t->get_ok('/hello')->status_is(200)->content_is('hello');
$t->get_ok('/primary')->status_is(200)->content_is(1);
$t->get_ok('/primary')->status_is(200)->content_is(2);
$t->get_ok('/x/1/secondary')->status_is(200)->content_is(1);
$t->get_ok('/primary')->status_is(200)->content_is(3);
$t->get_ok('/x/1/secondary')->status_is(200)->content_is(2);

done_testing;
