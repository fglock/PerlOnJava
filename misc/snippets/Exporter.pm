package Exporter;

use strict;
use warnings;
use Symbol;

sub import {
    my $package = shift;
    my $caller  = caller;
    my @symbols = @_;

    print "caller $caller\n";

    # Get the EXPORT and EXPORT_OK arrays
    my $export_ref    = Symbol::qualify_to_ref('EXPORT', $caller);
    my $export_ok_ref = Symbol::qualify_to_ref('EXPORT_OK', $caller);

    my @export    = $export_ref    ? @{$export_ref}    : ();
    my @export_ok = $export_ok_ref ? @{$export_ok_ref} : ();

    @symbols = @export if !@symbols;

    for my $symbol (@symbols) {
        if (grep { $_ eq $symbol } (@export, @export_ok)) {
            my $symbol_ref = Symbol::qualify_to_ref($symbol, $package);
            if ($symbol_ref && $symbol_ref->type eq 'CODE') {
                my $caller_ref = Symbol::qualify_to_ref($symbol, $caller);
                Symbol::setGlobalCodeRef($caller . '::' . $symbol, $symbol_ref);
            } else {
                warn "Subroutine $symbol not found in package $package";
            }
        } else {
            warn "Subroutine $symbol not allowed for export in package $package";
        }
    }
}

1;

