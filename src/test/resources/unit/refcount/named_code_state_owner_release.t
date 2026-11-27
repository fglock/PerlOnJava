use strict;
use warnings;
use feature 'state';
use Scalar::Util qw(weaken);
use Test::More;
sub named_metadata { state $metadata = { value => 1 }; $metadata }
my $weak = named_metadata(); weaken($weak);
my $keeper = \&named_metadata;
undef *named_metadata;
ok(defined $weak, 'external CODE reference retains its state after glob removal');
is($keeper->()->{value}, 1, 'retained CODE state remains usable without its glob');
undef $keeper;
ok(!defined $weak, 'final CODE reference releases its state');
sub aliased_metadata { state $metadata = { value => 2 }; $metadata }
{
    no warnings 'once';
    *alias_metadata = \&aliased_metadata;
}
$weak = aliased_metadata(); weaken($weak);
undef *aliased_metadata;
ok(defined $weak, 'alias CODE slot retains the shared state');
is(alias_metadata()->{value}, 2, 'alias remains callable after original glob removal');
undef *alias_metadata;
ok(!defined $weak, 'last CODE alias releases its state');
our $active_weak;
sub active_metadata {
    state $metadata = { value => 3 };
    $active_weak = $metadata; weaken($active_weak);
    undef *active_metadata;
    ok(defined $active_weak, 'active call keeps state after its glob is removed');
    return $metadata->{value};
}
is(active_metadata(), 3, 'active call can finish after glob removal');
ok(!defined $active_weak, 'last active invocation releases its state');
done_testing();
