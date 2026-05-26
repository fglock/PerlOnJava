package Class::XSAccessor::Heavy;

use 5.008;
use strict;
use warnings;
use Carp ();

our $VERSION = '1.19';
our @CARP_NOT = qw(
    Class::XSAccessor
    Class::XSAccessor::Array
);

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
            "Cannot replace existing subroutine '$bare_subname' in package '$sub_package' "
            . "with an XS implementation. If you wish to force a replacement, add the "
            . "'replace => 1' parameter to the arguments of 'use " . (caller())[0] . "'."
        );
    }
}

1;
