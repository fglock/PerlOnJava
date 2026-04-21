package # hide from PAUSE
    Class::XSAccessor::Heavy;

use 5.008;
use strict;
use warnings;
use Carp;

our $VERSION  = '1.19';
our @CARP_NOT = qw(
        Class::XSAccessor
        Class::XSAccessor::Array
);

# PerlOnJava note: unchanged from upstream — this file is pure-Perl
# already. Shipped alongside the PP Class::XSAccessor.pm so the
# require chain succeeds without needing XS.

sub check_sub_existence {
    my $subname = shift;

    my $sub_package = $subname;
    $sub_package =~ s/([^:]+)$// or die;
    my $bare_subname = $1;

    my $sym;
    {
        no strict 'refs';
        $sym = \%{"$sub_package"};
    }
    no warnings;
    local *s = $sym->{$bare_subname};
    my $coderef = *s{CODE};
    if ($coderef) {
        $sub_package =~ s/::$//;
        Carp::croak(
            "Cannot replace existing subroutine '$bare_subname' in package '"
            . $sub_package
            . "' with an XS implementation. If you wish to force a replacement, add the 'replace => 1' parameter to the arguments of 'use "
            . (caller())[0] . "'."
        );
    }
}

1;

__END__

=head1 NAME

Class::XSAccessor::Heavy - Shared helpers for Class::XSAccessor

=head1 DESCRIPTION

Internal: shared utility code used by L<Class::XSAccessor> and
L<Class::XSAccessor::Array>. Bundled with PerlOnJava so the require
chain resolves entirely with pure-Perl files.

=cut
