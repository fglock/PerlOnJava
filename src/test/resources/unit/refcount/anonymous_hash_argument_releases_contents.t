use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

{
    package Refcount::AnonymousHashArgumentTarget;
    sub new { bless {}, shift }
}

sub consume_hash { return scalar keys %{ $_[0] } }
sub consume_nested_hash { return scalar keys %{ $_[0]{args} } }

my $target = Refcount::AnonymousHashArgumentTarget->new;
my $weak = $target;
weaken($weak);
consume_hash({
    self_result_object => $target,
    condition => sub { 1 },
    rel_name => 'artist',
    self_alias => 'me',
    foreign_alias => 'artist',
    require_join_free_condition => 1,
});
undef $target;
ok(!defined $weak, 'anonymous hash argument releases its strong values');

my $nested_target = Refcount::AnonymousHashArgumentTarget->new;
my $nested_weak = $nested_target;
weaken($nested_weak);
consume_nested_hash({
    args => {
        self_result_object => $nested_target,
        condition => sub { 1 },
        rel_name => 'artist',
        self_alias => 'me',
        foreign_alias => 'artist',
        require_join_free_condition => 1,
    },
});
undef $nested_target;
ok(!defined $nested_weak, 'nested anonymous hash argument releases its strong values');
