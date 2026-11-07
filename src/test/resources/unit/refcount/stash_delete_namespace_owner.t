use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

our @DESTROYED;

{
    package NamespaceDeleteOwnerProbe;

    sub DESTROY {
        push @main::DESTROYED, 'value';
    }
}

my $object = bless {}, 'NamespaceDeleteOwnerProbe';
my $weak = $object;
weaken($weak);
{
    no strict 'refs';
    ${'NamespaceDeleteOwner::item'} = $object;
}
undef $object;

my $detached_namespace = delete $main::{'NamespaceDeleteOwner::'};
ok(defined $weak, 'the detached namespace retains its scalar referent');
is_deeply(\@DESTROYED, [], 'namespace deletion does not destroy a moved value');

undef $detached_namespace;
is($weak, undef, 'releasing the detached namespace releases its moved scalar');
is_deeply(\@DESTROYED, ['value'], 'the moved value is destroyed exactly once');

done_testing;
