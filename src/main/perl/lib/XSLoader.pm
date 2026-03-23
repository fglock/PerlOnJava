package XSLoader;

#
# XSLoader.pm - PerlOnJava stub for dynamically loading XS modules
#
# This stub handles XS module loading in PerlOnJava. The Java XSLoader
# class registers its methods at startup. This Perl file is a fallback
# that gets loaded if %INC is cleared (e.g., by Perl test files).
#
# It does NOT override the Java-registered XSLoader::load function
# if it already exists.
#
# Author: Flavio S. Glock
#

our $VERSION = "0.32";

# Only define our load() if it's not already defined by Java
BEGIN {
    unless (defined &load) {
        *load = sub {
            my ($module, $version) = @_;
            $module = caller() unless defined $module;
            
            # Check if the module has a bootstrap function (like standard XSLoader)
            my $boots = "${module}::bootstrap";
            if (defined &{$boots}) {
                goto &{$boots};
            }
            
            # For Java-backed modules, the methods are already registered.
            # For pure-Perl modules, nothing needs to be done.
            # Either way, just return success.
            return 1;
        };
    }
    
    # Alias for compatibility
    *bootstrap_inherit = \&load unless defined &bootstrap_inherit;
}

1;

__END__

=head1 NAME

XSLoader - PerlOnJava stub for dynamically loading XS modules

=head1 SYNOPSIS

    package YourPackage;
    require XSLoader;
    XSLoader::load('YourPackage', $VERSION);

=head1 DESCRIPTION

This is a PerlOnJava-specific stub module. In standard Perl, XSLoader
dynamically loads C/XS extensions. In PerlOnJava, "XS" modules are
implemented in Java and are pre-registered at startup, so this module
just checks for a bootstrap function and otherwise returns success.

=head1 AUTHOR

Flavio S. Glock

=cut
