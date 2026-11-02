use strict;
use warnings;
use Test::More tests => 7;
use Scalar::Util qw(weaken);

our @DESTROYED;

{
    package StashAliasRebindVoidDeleteOwner::Probe;

    sub DESTROY {
        push @main::DESTROYED, 'value';
    }
}

my $object = bless {}, 'StashAliasRebindVoidDeleteOwner::Probe';
my $weak = $object;
weaken($weak);
my ($source_stash, $alias_stash);
{
    no strict 'refs';
    ${'StashAliasRebindVoidDeleteOwnerSource::item'} = $object;
    $source_stash = \%StashAliasRebindVoidDeleteOwnerSource::;
    *StashAliasRebindVoidDeleteOwnerCopy:: = $source_stash;
    undef $object;
    *StashAliasRebindVoidDeleteOwnerSource:: = \%StashAliasRebindVoidDeleteOwnerOther::;
    $alias_stash = \%StashAliasRebindVoidDeleteOwnerCopy::;
}

ok(defined $weak, 'the preserved alias retains the source value after rebinding');
ok(exists $alias_stash->{item}, 'the alias stash retains the moved symbol');
is_deeply(\@DESTROYED, [], 'rebinding does not destroy the moved value');

delete $alias_stash->{item};

is($weak, undef, 'void-context deletion releases the detached glob value');
is_deeply(\@DESTROYED, ['value'], 'the moved value is destroyed exactly once');

my $namespace_object = bless {}, 'StashAliasRebindVoidDeleteOwner::Probe';
my $namespace_weak = $namespace_object;
weaken($namespace_weak);
{
    no strict 'refs';
    ${'StashAliasRebindVoidDeleteOwnerNamespace::item'} = $namespace_object;
}
undef $namespace_object;

delete $main::{'StashAliasRebindVoidDeleteOwnerNamespace::'};

is($namespace_weak, undef, 'void-context namespace deletion releases moved values');
is_deeply(\@DESTROYED, ['value', 'value'], 'namespace contents are destroyed once');

done_testing;
