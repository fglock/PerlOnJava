package DynaLoader;

#
# DynaLoader.pm - PerlOnJava stub for dynamic loading
#
# This stub provides a minimal DynaLoader module for PerlOnJava.
# The Java DynaLoader class registers bootstrap() and boot_DynaLoader()
# at startup. This Perl file exists so that CPAN dependency checks can
# find DynaLoader.pm on disk and extract its $VERSION.
#
# In standard Perl, DynaLoader loads shared C libraries (.so/.dll).
# In PerlOnJava, "XS" modules are implemented in Java and loaded via
# XSLoader, so DynaLoader's bootstrap() is not needed for normal use.
#

our $VERSION = '1.54';

# Only define bootstrap if not already registered by Java
BEGIN {
    unless (defined &bootstrap) {
        *bootstrap = sub {
            my ($module) = @_;
            $module = caller() unless defined $module;
            # Delegate to XSLoader::load for its multi-stage fallback
            require XSLoader;
            return XSLoader::load($module);
        };
    }

    unless (defined &boot_DynaLoader) {
        *boot_DynaLoader = sub { return };
    }

    # Stubs for CPAN Makefile.PL files that probe for native C libraries
    # (e.g. Geo::IP's Makefile.PL calls DynaLoader::dl_findfile('GeoIP')).
    # PerlOnJava has no shared-library support, so these return a "not found"
    # result, letting modules fall through to their pure-Perl (PP) code paths.
    unless (defined &dl_findfile) {
        *dl_findfile = sub { return };
    }
    unless (defined &dl_load_file) {
        *dl_load_file = sub { return };
    }
    unless (defined &dl_find_symbol) {
        *dl_find_symbol = sub { return };
    }
    unless (defined &dl_find_symbol_anywhere) {
        *dl_find_symbol_anywhere = sub { return };
    }
    unless (defined &dl_install_xsub) {
        *dl_install_xsub = sub { return };
    }
    unless (defined &dl_error) {
        *dl_error = sub { return "DynaLoader is not supported in PerlOnJava" };
    }
    unless (defined &dl_undef_symbols) {
        *dl_undef_symbols = sub { return () };
    }
    our @dl_library_path = ();
    our @dl_resolve_using = ();
    our @dl_require_symbols = ();
    our @dl_librefs = ();
    our @dl_modules = ();
    our @dl_shared_objects = ();
}

1;

__END__

=head1 NAME

DynaLoader - PerlOnJava stub for dynamic loading

=head1 SYNOPSIS

    package YourPackage;
    require DynaLoader;
    our @ISA = qw(DynaLoader);
    YourPackage->bootstrap($VERSION);

=head1 DESCRIPTION

This is a PerlOnJava-specific stub module. In standard Perl, DynaLoader
dynamically loads shared C/XS extensions. In PerlOnJava, "XS" modules
are implemented in Java and loaded via XSLoader, so DynaLoader is only
provided for compatibility with modules that list it as a dependency.

=head1 AUTHOR

Flavio S. Glock

=cut
