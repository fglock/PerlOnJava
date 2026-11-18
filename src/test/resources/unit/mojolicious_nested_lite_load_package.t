use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

BEGIN {
    $ENV{MOJO_MODE}    = 'testing';
    $ENV{MOJO_REACTOR} = 'Mojo::Reactor::Poll';
}

my $have_mojolicious = eval {
    require Mojolicious::Lite;
    require Mojolicious::Plugin::Mount;
    1;
};
plan skip_all => 'Mojolicious is optional' unless $have_mojolicious;

Mojolicious::Lite->import();

my ($app_file, $path) = tempfile(SUFFIX => '.pl', UNLINK => 1);
print {$app_file} <<'APP' or die "write app: $!";
use Mojolicious::Lite;
get '/' => {text => 'mounted'};
app->start;
APP
close $app_file or die "close app: $!";

my ($route, $load_error);
{
    local $@;
    eval { $route = app()->plugin(Mount => {'/mounted' => $path}); 1 }
        or $load_error = $@;
}

ok(!$load_error && $route, 'Mount loads a nested Lite application')
    or diag($load_error // 'Mount returned no route');
isa_ok($route->to->{app}, 'Mojolicious', 'mounted application') if $route;

done_testing;
