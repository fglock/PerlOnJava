package Moose::Meta::Role;

# PerlOnJava skeleton stub for Moose::Meta::Role.
#
# Like _FakeMeta but for roles. Just a "remember what we saw" registry.

use strict;
use warnings;

our $VERSION = '2.4000';

require Class::MOP::Class;
our @ISA = ('Class::MOP::Class');

my %ROLE_CACHE;

sub initialize {
    my ($class, $name, %opts) = @_;
    return $ROLE_CACHE{$name} ||= bless {
        name        => $name,
        attributes  => {},
        attr_order  => [],
        roles       => [],
        %opts,
    }, $class;
}

sub create {
    my ($class, $name, %opts) = @_;
    return $class->initialize($name, %opts);
}

{
    my $next = 0;
    sub _next_anon_id { ++$next }
}

sub create_anon_role {
    my ($class, %opts) = @_;
    my $name = "Moose::Meta::Role::__ANON__::SERIAL::" . _next_anon_id();
    return $class->create($name, %opts);
}

sub name              { $_[0]->{name} }
sub is_anon_role      { $_[0]->{name} =~ /__ANON__/ ? 1 : 0 }
sub get_required_method_list { @{ $_[0]->{required_methods} || [] } }
sub get_method_list   { () }
sub get_attribute_list { @{ $_[0]->{attr_order} || [] } }
sub get_attribute     { $_[0]->{attributes}{$_[1]} }
sub has_attribute     { exists $_[0]->{attributes}{$_[1]} ? 1 : 0 }
sub add_attribute {
    my ($self, @args) = @_;
    require Class::MOP::Attribute;
    my $attr = (@args == 1 && ref $args[0]) ? $args[0] : Class::MOP::Attribute->new(@args);
    my $name = $attr->name;
    return unless defined $name;
    push @{ $self->{attr_order} }, $name unless exists $self->{attributes}{$name};
    $self->{attributes}{$name} = $attr;
    return $attr;
}
sub get_method_modifier_list { () }
sub apply              { $_[0] }
sub combine            { $_[0] }

1;
__END__
=head1 NAME
Moose::Meta::Role - PerlOnJava skeleton stub.
=cut
