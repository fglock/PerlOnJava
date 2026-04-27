package Class::MOP::Attribute;

# PerlOnJava skeleton stub for Class::MOP::Attribute.
#
# Just enough surface that `require Class::MOP::Attribute` succeeds and
# `Class::MOP::Attribute->new(name => ...)` returns an object with a
# `name` accessor. No real attribute installation happens here — the
# Moose-as-Moo shim installs accessors via Moo's `has`.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my ($class, @args) = @_;
    my %opts;
    if (@args == 1 && ref $args[0] eq 'HASH') {
        %opts = %{ $args[0] };
    }
    elsif (@args >= 2 && @args % 2 == 1) {
        my $name = shift @args;
        %opts = @args;
        $opts{name} //= $name;
    }
    else {
        %opts = @args;
    }
    return bless { %opts }, $class;
}

sub name              { $_[0]->{name} }
sub init_arg          { exists $_[0]->{init_arg} ? $_[0]->{init_arg} : $_[0]->{name} }
sub default           { $_[0]->{default} }
sub has_default       { exists $_[0]->{default} }
sub builder           { $_[0]->{builder} }
sub has_builder       { exists $_[0]->{builder} }
sub is_required       { $_[0]->{required} ? 1 : 0 }
sub is_lazy           { $_[0]->{lazy}     ? 1 : 0 }
sub reader            { $_[0]->{reader}   // $_[0]->{name} }
sub writer            { $_[0]->{writer} }
sub accessor          { $_[0]->{accessor} }
sub predicate         { $_[0]->{predicate} }
sub clearer           { $_[0]->{clearer} }
sub has_predicate     { exists $_[0]->{predicate} }
sub has_clearer       { exists $_[0]->{clearer} }
sub has_reader        { exists $_[0]->{reader}   || exists $_[0]->{name} }
sub has_writer        { exists $_[0]->{writer} }
sub has_accessor      { exists $_[0]->{accessor} }
sub type_constraint   { $_[0]->{isa} }

1;

__END__

=head1 NAME

Class::MOP::Attribute - PerlOnJava skeleton stub.

=cut
