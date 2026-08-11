package Data::Util;

use strict;
use warnings;

our $VERSION = '0.67';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    is_scalar_ref is_array_ref is_hash_ref is_code_ref is_glob_ref is_rx is_regex_ref
    is_instance is_invocant
    is_value is_string is_number is_integer
    scalar_ref array_ref hash_ref code_ref glob_ref rx regex_ref
    instance invocant
    anon_scalar neat
    get_stash
    install_subroutine uninstall_subroutine get_code_info get_code_ref
    curry modify_subroutine subroutine_modifier
    mkopt mkopt_hash
);
our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
    check => [qw(
        is_scalar_ref is_array_ref is_hash_ref is_code_ref is_glob_ref is_rx
        is_instance is_invocant is_value is_string is_number is_integer is_regex_ref
    )],
    validate => [qw(
        scalar_ref array_ref hash_ref code_ref glob_ref rx instance invocant regex_ref
    )],
);

# XSLoader registers the Java implementations before evaluating this shim.
# Retain them while installing the distribution's pure-Perl implementation
# for the rest of Data::Util's API.
unless (defined &is_value && defined &is_string) {
    require XSLoader;
    XSLoader::load(__PACKAGE__, $VERSION);
}

my $java_is_value  = \&is_value;
my $java_is_string = \&is_string;

require 'Data/Util/PurePerl.pm';

{
    no warnings 'redefine';
    *is_value  = $java_is_value;
    *is_string = $java_is_string;
}

1;

__END__

=head1 NAME

Data::Util - PerlOnJava XS compatibility shim

=head1 DESCRIPTION

This shim combines PerlOnJava implementations of Data::Util's primitive SV
classification with the upstream distribution's pure-Perl implementation.

=head1 AUTHOR

Goro Fuji (gfx) E<lt>gfuji(at)cpan.orgE<gt>.

=head1 LICENSE AND COPYRIGHT

Copyright (c) 2008-2010, Goro Fuji. All rights reserved.

This module is free software; you can redistribute it and/or modify it under
the same terms as Perl itself.

=cut
