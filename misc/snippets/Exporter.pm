package Exporter;

use strict;
use warnings;
use Symbol;

sub import {
    my $package = shift;               # The package that called 'use'
    my $caller  = caller;              # The package that imported us
    my @symbols = @_;

    # Look for @EXPORT and @EXPORT_OK in the caller package
    my @export    = @{ Symbol::qualify_to_ref('EXPORT', $package)    || [] };  # Subroutines exported by default
    my @export_ok = @{ Symbol::qualify_to_ref('EXPORT_OK', $package) || [] };  # Subroutines exported on request

    # If no specific symbols were requested, default to @EXPORT
    @symbols = @export if !@symbols;

    for my $symbol (@symbols) {
        # Check if the symbol is in @EXPORT or @EXPORT_OK
        if (grep { $_ eq $symbol } (@export, @export_ok)) {
            # Check if the subroutine exists in the package
            my $symbol_ref = Symbol::qualify_to_ref($symbol, $package);
            if (defined &$symbol_ref) {
                # Export the subroutine to the caller package using Symbol::geniosym
                my $caller_ref = Symbol::qualify_to_ref($symbol, $caller);
                *$caller_ref = *$symbol_ref;
            } else {
                warn "Subroutine $symbol not found in package $package";
            }
        } else {
            warn "Subroutine $symbol not allowed for export in package $package";
        }
    }
}

1;  # End of the module

__END__

