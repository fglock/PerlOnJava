package autobox;

use strict;
use warnings;

our $VERSION = '3.0.2';

# Native-type method calls are rewritten by the parser provider. The original
# distribution installs its equivalent compiler and scope hooks from XS.
sub import { }
sub unimport { }

sub autobox::universal::type {
    my ($value) = @_;
    return 'UNDEF' unless defined $value;

    my $reference_type = ref $value;
    if ($reference_type) {
        return $reference_type if $reference_type eq 'ARRAY'
            || $reference_type eq 'HASH'
            || $reference_type eq 'CODE';
        return $reference_type;
    }

    require B;
    my $flags = B::svref_2object(\$value)->FLAGS;
    return 'FLOAT' if $flags & (B::SVf_NOK() | B::SVp_NOK());
    return 'INTEGER' if $flags & (B::SVf_IOK() | B::SVp_IOK());
    return 'STRING';
}

1;

__END__

=head1 NAME

autobox - PerlOnJava native-type method dispatch

=head1 DESCRIPTION

PerlOnJava implements the autobox import and lexical binding behavior in its
parser and runtime. Bindings are attached to method call sites, so nested
lexical scopes do not share mutable process-wide dispatch state.

=cut
