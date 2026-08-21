use strict;
use warnings;
use threads;
use Test::More;
use Scalar::Util qw(refaddr);
use re 'eval';

my $cell = { genuine => 0, dynamic => 0, nested => 0, trace => [] };
my $state = { left => $cell, right => $cell };
$state->{self} = $state;

my $leaf = qr/(?{
    ++$state->{right}{nested};
    push @{$state->{left}{trace}}, 'n';
})A/;
my $pattern = qr/^(?{
    ++$state->{left}{genuine};
    push @{$state->{right}{trace}}, 'g';
})(??{
    ++$state->{right}{dynamic};
    push @{$state->{left}{trace}}, 'd';
    $leaf;
})$/;

my $ordinary_leaf = { label => 'parent', hits => 0 };
my $ordinary = { left => $ordinary_leaf, right => $ordinary_leaf };
$ordinary->{self} = $ordinary;
my $ordinary_code = sub {
    my ($label) = @_;
    $ordinary->{right}{label} = $label;
    ++$ordinary->{left}{hits};
    return join(':', $ordinary->{left}{label}, $ordinary->{right}{hits},
        refaddr($ordinary->{left}) == refaddr($ordinary->{right}),
        refaddr($ordinary->{self}) == refaddr($ordinary));
};
my $plain = qr/^A$/;

ok('A' =~ $pattern, 'parent warms genuine, dynamic, and nested callback qr');
is(join(':', @{$cell}{qw(genuine dynamic nested)}, join('', @{$cell->{trace}})),
   '1:1:1:gdn', 'warm match updates the one captured graph');
is($ordinary_code->('parent'), 'parent:1:1:1',
   'ordinary closure graph is warmed as clone control');
ok('A' =~ $plain, 'noncallback qr is warmed as clone control');

sub child_result {
    my ($label) = @_;
    my $before = join(':', @{$cell}{qw(genuine dynamic nested)},
        join('', @{$cell->{trace}}));
    my $identity = join(':',
        refaddr($state->{left}) == refaddr($state->{right}),
        refaddr($state->{self}) == refaddr($state));
    my $first = 'A' =~ $pattern ? 1 : 0;
    my $second = 'A' =~ $pattern ? 1 : 0;
    my $after = join(':', @{$cell}{qw(genuine dynamic nested)},
        join('', @{$cell->{trace}}));
    my $ordinary_result = $ordinary_code->($label);
    my $plain_result = join(':', 'A' =~ $plain ? 1 : 0, 'B' !~ $plain ? 1 : 0);
    return [$before, $identity, $first, $second, $after,
        $ordinary_result, $plain_result];
}

sub expected_child {
    my ($label) = @_;
    return ['1:1:1:gdn', '1:1', 1, 1, '3:3:3:gdngdngdn',
        "$label:2:1:1", '1:1'];
}

my $first = threads->create(\&child_result, 'child-one')->join;
is_deeply($first, expected_child('child-one'),
    'first child gets one coherent cloned callback and closure graph');
is(join(':', @{$cell}{qw(genuine dynamic nested)}, join('', @{$cell->{trace}}),
        $ordinary_leaf->{label}, $ordinary_leaf->{hits}),
   '1:1:1:gdn:parent:1', 'first child cannot mutate parent graphs');

my $second = threads->create(\&child_result, 'child-two')->join;
is_deeply($second, expected_child('child-two'),
    'second child starts from the parent snapshot, not the first child');
is(join(':', @{$cell}{qw(genuine dynamic nested)}, join('', @{$cell->{trace}}),
        $ordinary_leaf->{label}, $ordinary_leaf->{hits}),
   '1:1:1:gdn:parent:1', 'repeated child leaves parent graphs isolated');

ok('A' =~ $pattern, 'parent callback qr remains reusable after repeated children');
is(join(':', @{$cell}{qw(genuine dynamic nested)}, join('', @{$cell->{trace}})),
   '2:2:2:gdngdn', 'parent retains its own callback cells');

done_testing;
