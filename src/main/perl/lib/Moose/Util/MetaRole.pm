package Moose::Util::MetaRole;

# PerlOnJava skeleton stub for Moose::Util::MetaRole.
#
# Upstream this is what MooseX::* extensions use to install custom
# metaclass roles into a class's meta-object protocol. Under the
# Moose-as-Moo shim there is no metaclass to extend, so apply_metaroles
# and friends are no-ops that succeed quietly. This is enough to keep
# extensions from blowing up at compile/use time; behaviour-affecting
# metaroles (e.g. MooseX::StrictConstructor) are not honoured.

use strict;
use warnings;

our $VERSION = '2.4000';

use Exporter 'import';
our @EXPORT_OK = qw(
    apply_metaroles
    apply_base_class_roles
);

sub apply_metaroles {
    my %args = @_;
    my $for = $args{for} or return;
    return $for;
}

sub apply_base_class_roles {
    my %args = @_;
    my $for = $args{for} or return;
    return $for;
}

1;
__END__
=head1 NAME
Moose::Util::MetaRole - PerlOnJava skeleton stub.
=cut
