# D-W6.6 — Class::MOP add_attribute loop drift reproducer.
#
# Recreates the exact shape of the Class::MOP bootstrap that fails
# during `use Class::MOP` with the gate disabled. The bootstrap calls
# `Class::MOP::Mixin::HasMethods->meta->add_attribute(...)` repeatedly
# for each MOP attribute. Each call:
#
#   1. _attach_attribute — sets `weaken($attr->{associated_class} = $self)`.
#   2. Stores attribute in `$self->{_attribute_map}{...}` (strong).
#   3. _post_add_attribute — `try { install_accessors } catch { ... }`.
#
# Both Perl 5 and PerlOnJava handle this correctly. The bare-Perl
# shape passes. The actual failure during real Class::MOP load must
# involve something more specific (multi-level @ISA, role
# composition, or a specific code path inside install_accessors).
use strict;
use warnings;
use Test::More;
use Try::Tiny;

package Meta;
use Try::Tiny;
use Scalar::Util qw(weaken);

sub new { bless { name => $_[1], _attrs => {} }, $_[0] }
sub name { $_[0]->{name} }
sub _attach_attribute {
    my ($self, $attr) = @_;
    $attr->{associated_class} = $self;
    weaken($attr->{associated_class});
}
sub _post_add_attribute {
    my ($self, $attr) = @_;
    try {
        local $SIG{__DIE__};
        $attr->install_accessors;
    } catch {
        $self->remove_attribute($attr->{name});
    };
}
sub add_attribute {
    my ($self, $attribute) = @_;
    $self->_attach_attribute($attribute);
    $self->{_attrs}{$attribute->{name}} = $attribute;
    $self->_post_add_attribute($attribute);
}
sub remove_attribute {
    my ($self, $name) = @_;
    my $attr = delete $self->{_attrs}{$name} or return;
    $attr->remove_accessors;
}

package Attr;
sub new { bless { %{$_[1]} }, $_[0] }
sub install_accessors {
    my $self = shift;
    my $reader = $self->{reader} or return;
    if (ref $reader eq 'HASH') {
        my $cls = $self->{associated_class};
        return if defined $cls;
        die "install: associated_class UNDEF for $self->{name}!";
    }
}
sub remove_accessors {
    my $self = shift;
    return if defined $self->{associated_class};
    die "*** remove: UNDEF associated_class for $self->{name}";
}

package main;
our %METAS;

my $cv = sub { 'reader' };
my $meta = Meta->new('TestPkg');
$METAS{TestPkg} = $meta;

my $err = 0;
for my $n (qw(_method_map method_metaclass wrapped_method_metaclass
              attribute_metaclass attribute_map list_methods
              foo bar baz quux corge)) {
    eval {
        $meta->add_attribute(Attr->new({
            name => $n,
            reader => { "${n}_reader" => $cv },
        }));
    };
    if ($@) { $err++; diag("attr $n: $@") }
}
is $err, 0, '11 add_attribute iterations all succeed';
is scalar(keys %{$meta->{_attrs}}), 11, '11 attributes registered';
done_testing;
