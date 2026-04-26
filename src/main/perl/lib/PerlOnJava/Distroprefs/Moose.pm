package PerlOnJava::Distroprefs::Moose;

# Helpers invoked from the bundled CPAN distropref for the Moose CPAN
# distribution (see src/main/perl/lib/CPAN/Config.pm). The distropref's
# pl: phase calls bootstrap_pl_phase() to:
#
#   1. Make sure Moo is installed (it is the runtime dependency of the
#      PerlOnJava Moose-as-Moo shim at src/main/perl/lib/Moose.pm).
#      If Moo is missing, recursively invoke jcpan via $ENV{JCPAN_BIN}.
#   2. Create a stub Makefile so CPAN.pm's "no Makefile created"
#      fallback path doesn't try to regenerate Makefile.PL.
#
# This module exists so the distropref's commandline can be a single
# cross-platform Perl invocation:
#
#   jperl -MPerlOnJava::Distroprefs::Moose \
#         -e 'PerlOnJava::Distroprefs::Moose::bootstrap_pl_phase()'
#
# instead of POSIX-shell-only constructs (||, ;, $VAR, touch, /dev/null)
# that don't work in Windows cmd.exe.

use strict;
use warnings;

our $VERSION = '0.01';

sub bootstrap_pl_phase {
    _ensure_moo();
    _touch_makefile();
    return 0;
}

sub _ensure_moo {
    return if eval { require Moo; 1 };

    my $jcpan = $ENV{JCPAN_BIN}
        or die "PerlOnJava::Distroprefs::Moose: JCPAN_BIN not set; "
             . "cannot install Moo. Run `jcpan Moo` manually.\n";

    print "PerlOnJava: Moose shim requires Moo; installing via $jcpan...\n";
    my $rc = system $jcpan, 'Moo';
    if ($rc != 0) {
        die "PerlOnJava::Distroprefs::Moose: '$jcpan Moo' failed "
          . "(exit $rc). Install Moo manually before running "
          . "`jcpan -t Moose`.\n";
    }

    # Verify it now loads. CPAN may report "OK" while still leaving the
    # module unimportable — fail loudly here so we don't pretend the
    # bootstrap succeeded.
    delete $INC{'Moo.pm'};
    eval { require Moo; 1 }
        or die "PerlOnJava::Distroprefs::Moose: Moo still not "
             . "loadable after install: $@\n";
}

sub _touch_makefile {
    open my $fh, '>>', 'Makefile' or die "Cannot create Makefile: $!\n";
    close $fh;
}

# A no-op equivalent of POSIX `true` / Windows `cmd /c exit 0`. Used by
# the make/install phases of the Moose distropref so they're a portable
# `jperl -MPerlOnJava::Distroprefs::Moose -e
# 'PerlOnJava::Distroprefs::Moose::noop()'`.
sub noop { 0 }

1;

__END__

=head1 NAME

PerlOnJava::Distroprefs::Moose - cross-platform helpers for the bundled
Moose distropref

=head1 SEE ALSO

L<dev/modules/moose_support.md>, L<CPAN/Config.pm>.

=cut
