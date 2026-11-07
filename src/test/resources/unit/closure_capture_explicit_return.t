use strict;
use warnings;
use Test::More tests => 6;
use Scalar::Util qw(weaken);

my $destroyed = 0;
my $weak_node;

{
    package Local::ExplicitReturnCapture;
    sub DESTROY { ++$destroyed }
}

sub discard_captured_lexical_with_explicit_return {
    my $node = bless {}, 'Local::ExplicitReturnCapture';
    $weak_node = $node;
    weaken($weak_node);
    my $callback = sub { $node };
    return;
}

discard_captured_lexical_with_explicit_return();
is($destroyed, 1, 'captured lexical is released after explicit return');
ok(!defined $weak_node, 'weak reference clears after explicit return');

$destroyed = 0;
$weak_node = undef;
sub return_callback_owning_aggregate {
    my @nodes = (bless {}, 'Local::ExplicitReturnCapture');
    $weak_node = $nodes[0];
    weaken($weak_node);
    return sub { scalar @nodes };
}

my $callback = return_callback_owning_aggregate();
is($callback->(), 1, 'returned closure retains its captured aggregate');
is($destroyed, 0, 'captured aggregate survives the returning call frame');
undef $callback;
is($destroyed, 1, 'captured aggregate is released with the returned closure');
ok(!defined $weak_node, 'weak reference clears after returned closure release');
