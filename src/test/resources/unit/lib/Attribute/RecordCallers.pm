package Attribute::RecordCallers;

use strict;
use warnings;
use Attribute::Handlers;
use Carp qw(carp);

our @CARP_NOT = qw(Attribute::Handlers);
@Attribute::Handlers::CARP_NOT = qw(attributes);

sub UNIVERSAL::RecordCallers : ATTR(CODE, BEGIN) {
    my ($package, $glob) = @_;
    no strict 'refs';
    no warnings 'once';
    carp 'Ignoring RecordCallers attribute on anonymous subroutine'
        if *{$glob}{NAME} eq 'ANON';
}

1;
