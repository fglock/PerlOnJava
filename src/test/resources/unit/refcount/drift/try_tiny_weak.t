# D-W6.6 — Try::Tiny + weak back-ref drift reproducer.
#
# The actual failure during `use Class::MOP` (with the gate disabled)
# happens at Class/MOP/Class.pm:897 inside `_post_add_attribute`:
#
#   sub _post_add_attribute {
#       my ($self, $attribute) = @_;
#       $self->invalidate_meta_instances;
#       try {
#           local $SIG{__DIE__};
#           $attribute->install_accessors;
#       } catch {
#           $self->remove_attribute($attribute->name);
#           die $_;
#       };
#   }
#
# In the catch path, `$attribute->associated_class()` (a WEAK ref to
# `$self`) reads as undef — even though `$self` is alive on the stack.
#
# Hypothesis: Try::Tiny's `try { ... } catch { ... }` builds a closure
# that captures `$self` and `$attr`. The captures drop refCount
# transiently. The captured weak-ref-target's refCount hits 0 and
# `clearWeakRefsTo` fires, wiping the back-ref before the catch
# reads it.
use strict;
use warnings;
use Test::More;
use Try::Tiny;
use Scalar::Util qw(weaken);

# Bare-Perl recreation of Class::MOP's _post_add_attribute pattern.

package Meta;
sub new {
    my ($class, $name) = @_;
    bless { name => $name }, $class;
}
sub name { $_[0]->{name} }
sub attach_attr {
    my ($self, $attr) = @_;
    $attr->{associated_class} = $self;
    Scalar::Util::weaken($attr->{associated_class});
}
sub remove_attr {
    my ($self, $attr) = @_;
    my $cls = $attr->associated_class;
    die "associated_class is undef!" unless defined $cls;
    return $cls->name;
}

package Attr;
sub new { bless { name => $_[1] }, $_[0] }
sub name { $_[0]->{name} }
sub associated_class { $_[0]->{associated_class} }
sub install_accessors_with_die {
    my $self = shift;
    die "install_accessors deliberately dying\n";
}

package main;

# ---- Pattern A: bare Try::Tiny + weak back-ref ---------------------------
{
    my $meta = Meta->new('FooA');
    my $attr = Attr->new('attr1');
    $meta->attach_attr($attr);

    my $caught_class;
    try {
        local $SIG{__DIE__};
        $attr->install_accessors_with_die;
    } catch {
        # In Class::MOP this is `$self->remove_attribute($attr->name)`
        # which dispatches into _remove_accessor that calls
        # `$attr->associated_class()`. The weak ref must still resolve.
        $caught_class = $meta->remove_attr($attr);
    };

    is $caught_class, 'FooA',
        'A: weak ref to $meta survived Try::Tiny try/catch';
}

# ---- Pattern B: $self captured by try-block, no outer my-var -------------
# Mimics _post_add_attribute where $self is a parameter, not a global.
sub do_post_add_attribute {
    my ($self, $attr) = @_;
    my $caught_class;
    try {
        local $SIG{__DIE__};
        $attr->install_accessors_with_die;
    } catch {
        $caught_class = $self->remove_attr($attr);
    };
    return $caught_class;
}

{
    my $meta = Meta->new('FooB');
    my $attr = Attr->new('attr1');
    $meta->attach_attr($attr);
    my $r = do_post_add_attribute($meta, $attr);
    is $r, 'FooB',
        'B: weak ref survives Try::Tiny inside a sub';
}

# ---- Pattern C: 20-iteration loop ----------------------------------------
# Mimics Class::MOP bootstrap building many attributes.
{
    my @failures;
    for my $i (1 .. 20) {
        my $meta = Meta->new("PkgC$i");
        my $attr = Attr->new("attr$i");
        $meta->attach_attr($attr);
        my $r = do_post_add_attribute($meta, $attr);
        push @failures, $i unless defined $r && $r eq "PkgC$i";
    }
    is scalar(@failures), 0,
        'C: 20 iterations of try/catch + weak ref all succeed';
}

# ---- Pattern D: meta in a global hash --------------------------------------
# Mimics Class::MOP's %METAS pattern.
{
    package Reg;
    our %METAS;
    package main;
    %Reg::METAS = ();
    my @failures;
    for my $i (1 .. 5) {
        my $meta = Meta->new("PkgD$i");
        $Reg::METAS{"PkgD$i"} = $meta;
        my $attr = Attr->new("attr$i");
        $meta->attach_attr($attr);
        my $r = do_post_add_attribute($meta, $attr);
        push @failures, $i unless defined $r && $r eq "PkgD$i";
    }
    is scalar(@failures), 0,
        'D: 5 metaclasses in %METAS + try/catch + weak ref all succeed';
}

done_testing;
