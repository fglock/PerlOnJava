package Symbol;

# This is a wrapper for the PerlOnJava Symbol XS module.
# It provides qualify_to_ref and gensym functions.
# Minimal implementation to avoid dependencies on Exporter.

our $VERSION = '1.09';

# Custom import to avoid depending on Exporter
sub import {
    my $class = shift;
    my $caller = caller;
    
    # Default: export nothing. Only export what's explicitly requested.
    for my $sym (@_) {
        if ($sym eq 'gensym') {
            no strict 'refs';
            *{"${caller}::gensym"} = \&gensym;
        } elsif ($sym eq 'qualify_to_ref') {
            no strict 'refs';
            *{"${caller}::qualify_to_ref"} = \&qualify_to_ref;
        } elsif ($sym eq 'ungensym') {
            no strict 'refs';
            *{"${caller}::ungensym"} = \&ungensym;
        } elsif ($sym eq 'delete_package') {
            no strict 'refs';
            *{"${caller}::delete_package"} = \&delete_package;
        } elsif ($sym eq 'geniosym') {
            no strict 'refs';
            *{"${caller}::geniosym"} = \&geniosym;
        }
    }
}

# Load the XS module to get gensym and qualify_to_ref
require XSLoader;
XSLoader::load('Symbol', $VERSION);

# ungensym is a no-op (for backwards compatibility)
sub ungensym ($) {}

# delete_package - not implemented, provide stub
sub delete_package ($) {
    my $pkg = shift;
    # Not implemented - would need to delete all symbols in the package
    warn "Symbol::delete_package is not fully implemented";
}

# geniosym - generate anonymous IO handle
sub geniosym () {
    my $sym = gensym();
    # Already returns a glob ref, which works for IO
    return $sym;
}

1;
