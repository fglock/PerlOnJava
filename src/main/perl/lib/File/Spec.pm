package File::Spec;

#
# Original File::Spec module maintained by the Perl 5 Porters.
# Copyright (c) 2004-2013 by the Perl 5 Porters. All rights reserved.
#
# The vast majority of the code was written by
# Kenneth Albanowski <kjahds@kjahds.com>,
# Andy Dougherty <doughera@lafayette.edu>,
# Andreas Koenig <A.Koenig@franz.ww.TU-Berlin.DE>,
# Tim Bunce <Tim.Bunce@ig.co.uk>.
#
# This program is free software; you can redistribute it and/or modify
# it under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/FileSpec.java
#

use warnings;
use strict;

our $VERSION = '3.95';  # Match perl5 PathTools version

# Load File::Spec::Unix so that code calling File::Spec::Unix->method()
# directly (e.g., Module::Build) can find the methods.
# The Java backend (FileSpec.java) provides the primary implementation,
# but File::Spec::Unix must also be loaded for compatibility.
require File::Spec::Unix;

# Set @ISA to the platform-specific File::Spec subclass, matching the
# behaviour of the upstream PathTools File::Spec.pm.  Some CPAN modules
# (Directory::Scratch, Path::Class helpers, etc.) inspect
# C<$File::Spec::ISA[0]> to discover the current platform; without this
# they get C<Exporter> and break.
my %_module_for = (
    MacOS   => 'Mac',
    MSWin32 => 'Win32',
    os2     => 'OS2',
    VMS     => 'VMS',
    epoc    => 'Epoc',
    NetWare => 'Win32',
    symbian => 'Win32',
    dos     => 'OS2',
    cygwin  => 'Cygwin',
    amigaos => 'AmigaOS',
);

my $_module = $_module_for{$^O} || 'Unix';
require "File/Spec/$_module.pm";
our @ISA = ("File::Spec::$_module");

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/FileSpec.java

1;

__END__

=head1 NAME

File::Spec - portably perform operations on file names

=head1 DESCRIPTION

This is the PerlOnJava implementation of File::Spec. The actual implementation
is in the Java backend.

=head1 AUTHOR

Maintained by perl5-porters <perl5-porters@perl.org>.

The vast majority of the code was written by
Kenneth Albanowski C<< <kjahds@kjahds.com> >>,
Andy Dougherty C<< <doughera@lafayette.edu> >>,
Andreas Koenig C<< <A.Koenig@franz.ww.TU-Berlin.DE> >>,
Tim Bunce C<< <Tim.Bunce@ig.co.uk> >>.

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT

Copyright (c) 2004-2013 by the Perl 5 Porters. All rights reserved.

This program is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut

