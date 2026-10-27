use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our $destroyed = 0;
{ package ConditionalLoopOwner; sub DESTROY { ++$main::destroyed } }
sub consume_items {
    my ($owner) = @_;
    if (my $items = delete $owner->{items}) {
        $_->{error} = 7 for values %$items;
    }
}
my $guard = bless {}, 'ConditionalLoopOwner';
my $owner = { items => { one => $guard } };
my $weak_items = $owner->{items};
weaken($weak_items);
my $weak_guard = $guard;
weaken($weak_guard);
undef $guard;
consume_items($owner);
ok(!exists $owner->{items}, 'conditional initializer removes the source container slot');
ok(!defined $weak_items, 'conditional lexical releases its container after postfix loop');
ok(!defined $weak_guard, 'conditional container releases its child slot');
is($destroyed, 1, 'conditional scope destroys the child exactly once');
is_deeply($owner, {}, 'callee cleanup preserves the caller aggregate');
sub condition_hash_result {
    if (my %items = (one => bless({ value => 9 }, 'ConditionalLoopOwner'))) {
        \%items;
    }
}
my $hash_destroyed_before = $destroyed;
my $returned = condition_hash_result();
is($destroyed, $hash_destroyed_before, 'conditional return keeps its child alive before caller acquisition');
is($returned->{one}{value}, 9, 'conditional result preserves a reference to its lexical hash');
my $weak_returned_child = $returned->{one};
weaken($weak_returned_child);
undef $returned;
ok(!defined $weak_returned_child, 'caller releases the returned conditional hash and its child');
sub conditional_capture {
    if (my $item = bless { value => 12 }, 'ConditionalLoopOwner') {
        sub { $item->{value} };
    }
}
my $callback = conditional_capture();
is($callback->(), 12, 'implicit conditional return keeps its captured lexical alive');
my $before = $destroyed;
undef $callback;
is($destroyed, $before + 1, 'final callback owner releases its conditional capture');
{
    package FalseConditionalOwner;
    use overload bool => sub { 0 }, fallback => 1;
    sub DESTROY { ++$main::destroyed }
}
sub false_condition_result {
    if (my $item = bless { value => 17 }, 'FalseConditionalOwner') {
        die "false conditional branch executed\n";
    }
}
my $false_result = false_condition_result();
is($false_result->{value}, 17, 'untaken branch preserves its false reference-valued condition');
my $weak_false = $false_result;
weaken($weak_false);
undef $false_result;
ok(!defined $weak_false, 'caller releases the false conditional result');
done_testing();
