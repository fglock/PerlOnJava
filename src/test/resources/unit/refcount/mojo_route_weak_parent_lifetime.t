use strict;
use warnings;
use Scalar::Util qw(isweak);
use Test::More;

my $loaded = eval {
    require Mojolicious::Controller;
    require Mojolicious::Routes;
    require Mojolicious::Routes::Match;
    1;
};
plan skip_all => 'Mojolicious is not installed' unless $loaded;

# Reparent an existing route subtree before adding a WebSocket branch. This
# exercises the weak parent links after array removal/reinsertion, as in the
# upstream Mojolicious::Routes::Match WebSocket case.
my $root = Mojolicious::Routes->new;
my $source = $root->any('/source')->to('source#');
my $first = $source->any('/', [format => 0])->any('/first')->to('#first');
$source->any('/second', [format => ['xml']])->to('#second', format => undef);
my $target = $root->remove->any('/target')->to('target#');
my $second = $root->find('second');
is($second->render({}), '/source/second', 'source route starts at its original parent');
$second->remove;
is($second->render({}), '/second', 'removed route has no parent');
$target->add_child($first)->add_child($second);
is($second->render({}), '/target/second', 'reparented route follows its new parent');

$root->websocket('/websocket' => {testcase => 'ws'})
    ->any('/')->to(action => 'just')->any->to(works => 1);

my $endpoint;
{
    my $websocket = $root->children->[-1];
    my $nested = $websocket->children->[0];
    $endpoint = $nested->children->[0];
    ok(isweak($endpoint->{parent}), 'route parent is weak before matching');
    ok(defined($endpoint->parent), 'route tree retains the weak parent before matching');
    is($endpoint->root, $root, 'endpoint resolves its root before matching');
}

my $controller = Mojolicious::Controller->new;
my $match = Mojolicious::Routes::Match->new(root => $root);
$match->find($controller => {method => 'GET', path => '/websocket'});
is_deeply($match->stack, [], 'non-WebSocket request does not match');
ok(isweak($endpoint->{parent}), 'failed match preserves the weak route parent');
ok(defined($endpoint->parent), 'failed match keeps the route parent alive');

$match->find($controller => {
    method => 'GET', path => '/websocket', websocket => 1,
});
is_deeply(
    $match->stack,
    [{testcase => 'ws', action => 'just', works => 1}],
    'WebSocket request matches through the nested route tree',
);
ok(isweak($match->endpoint->{parent}), 'matching preserves the weak route parent');
ok(defined($match->endpoint->parent), 'matching preserves the parent referent');
is($match->path_for->{path}, '/websocket', 'render retains the ancestor path');
ok($match->path_for->{websocket}, 'render retains WebSocket ancestry');

done_testing();
