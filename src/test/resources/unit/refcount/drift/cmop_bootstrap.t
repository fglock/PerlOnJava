# D-W6.4 — Mimic the exact MOP pattern: weak attribute back-ref + global hash.
#
# The Class::MOP failure shape:
#   - $METAS{$pkg} = $meta;          # strong ref in package-global hash
#   - $attr->attach_to_class($meta); # makes $attr->{associated_class} = $meta + weaken
#   - The metaclass MUST stay alive while %METAS holds it strongly.
#
# Earlier hash_slot.t and function_hash_store.t both pass without the gate.
# This file specifically tests the COMBINATION that breaks during real
# Class::MOP bootstrap: weak ref to a hash-stored object, with the strong
# holder being a function-internal store.
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

my $destroyed = 0;
package Probe;
sub new { bless { id => ++$Probe::N }, shift }
sub DESTROY { $destroyed++ }

package Attr;
sub new { my ($c, $meta) = @_; my $s = bless { meta => $meta }, $c;
          Scalar::Util::weaken($s->{meta}); $s }
sub meta { $_[0]->{meta} }

package Registry;
our %METAS;
sub store { $METAS{$_[0]} = $_[1] }
sub get   { $METAS{$_[0]} }

package main;

# ---- Pattern A: build a metaclass + an attribute with weak back-ref -----
{
    %Registry::METAS = ();
    $destroyed = 0;

    my $attr;
    {
        my $meta = Probe->new;
        Registry::store('Foo', $meta);
        $attr = Attr->new($meta);
        # $meta scope ends. Strong holders: %Registry::METAS{Foo} (strong)
        #                                   $attr->{meta}       (WEAK)
    }
    is $destroyed, 0,
        'A: meta survives while %METAS holds it (weak attr back-ref)';
    ok defined Registry::get('Foo'),
        'A: %METAS{Foo} still defined';
    ok defined $attr->meta,
        'A: $attr->meta still defined (weak ref points at live obj)';
    is $attr->meta->{id}, 1, 'A: weak ref returns valid Probe';

    %Registry::METAS = ();
    is $destroyed, 1, 'A: destroyed after %METAS cleared';
    ok !defined $attr->meta, 'A: weak ref now undef';
}

# ---- Pattern B: many such metaclasses (Class::MOP bootstrap shape) ------
{
    %Registry::METAS = ();
    $destroyed = 0;

    my @attrs;
    for my $i (1 .. 20) {
        my $meta = Probe->new;
        Registry::store("Pkg$i", $meta);
        push @attrs, Attr->new($meta);
    }

    # All my-vars exited. %METAS is the only strong holder for each.
    is $destroyed, 0, 'B: 20 metaclasses survive bootstrap pattern';
    my $alive = scalar grep { defined Registry::get("Pkg$_") } 1 .. 20;
    is $alive, 20, 'B: all 20 still in %METAS';
    my $attr_alive = scalar grep { defined $_->meta } @attrs;
    is $attr_alive, 20, 'B: all 20 weak attr back-refs still resolve';

    %Registry::METAS = ();
    is $destroyed, 20, 'B: all 20 destroyed when %METAS cleared';
}

# ---- Pattern C: plus a DESTROY method (Class::MOP::Class shape) ---------
# Reproduces the EXACT failure shape: a class that has DESTROY, weak
# attr back-ref, package-global hash store. This is what Class::MOP does.
our $C_destroyed = 0;
package MetaClass;
sub new { bless { name => $_[1] }, $_[0] }
sub DESTROY { $main::C_destroyed++ }

package main;
{
    %Registry::METAS = ();
    $C_destroyed = 0;

    my $attr;
    {
        my $meta = MetaClass->new('TestPkg');
        Registry::store('TestPkg', $meta);
        $attr = Attr->new($meta);
    }
    is $C_destroyed, 0,
        'C: MetaClass-with-DESTROY survives via %METAS strong hold';
    ok defined $attr->meta,
        'C: weak attr back-ref resolves through %METAS';
    is $attr->meta->{name}, 'TestPkg', 'C: weak ref returns valid metaclass';

    %Registry::METAS = ();
    is $C_destroyed, 1, 'C: DESTROY fires once when %METAS cleared';
}

done_testing;
