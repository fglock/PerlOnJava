use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

my $destroyed = 0;
my $weak_node;
{
    package Local::CapturedScopeValue;
    sub DESTROY { ++$destroyed }
}

sub discard_captured_lexical {
    my $node = bless {}, 'Local::CapturedScopeValue';
    $weak_node = $node;
    weaken($weak_node);
    my $callback = sub { $node };
    $callback->();
}

discard_captured_lexical();
is($destroyed, 1, 'captured lexical value is destroyed after its closure leaves scope');
ok(!defined $weak_node, 'weak reference clears when the captured lexical is released');
