package DBI::DBD;

# Minimal DBI::DBD shim for PerlOnJava.
#
# Upstream DBI::DBD is the build-time helper used by CPAN DBD::* drivers
# from their Makefile.PL: it wires Driver.xst / Driver_xst.h into the
# generated Makefile so the C glue compiles against the installed DBI.
#
# In PerlOnJava there is no XS compilation — DBD drivers we support
# (DBD::JDBC, DBD::SQLite, …) ship as pure-Perl + Java shims bundled in
# the JAR. Their Makefile.PL files still `use DBI::DBD;` and call
# `main::dbd_postamble(@_)` from `MY::postamble`, expecting the helper
# functions below to exist. We provide no-op equivalents so configure
# can succeed without an XS toolchain or a copy of Driver.xst.

use strict;
use warnings;

our $VERSION = '12.015129'; # mirror upstream version style; value not load-bearing

require Exporter;
our @ISA       = qw(Exporter);
our @EXPORT    = qw(
    dbd_dbi_dir
    dbd_dbi_arch_dir
    dbd_edit_mm_attribs
    dbd_postamble
);

sub dbd_dbi_dir {
    my $dbidir = $INC{'DBI.pm'} or return '.';
    $dbidir =~ s{/DBI\.pm$}{};
    return $dbidir;
}

sub dbd_dbi_arch_dir {
    # No real arch dir under PerlOnJava; return a placeholder so any
    # generated Makefile fragments that reference it stay syntactically
    # valid even though the corresponding rules will never run.
    return '$(INST_ARCHAUTODIR)';
}

# dbd_edit_mm_attribs is normally used to filter Makefile.PL test files
# and tweak attributes. Pass through unchanged.
sub dbd_edit_mm_attribs {
    my ($mm_attr, $dbd_attr) = @_;
    return $mm_attr;
}

# dbd_postamble: upstream emits Makefile rules that turn Driver.xst into
# $(BASEEXT).xsi and link the XS object. PerlOnJava has no XS step, so
# return an empty postamble.
sub dbd_postamble {
    return "\n# DBI::DBD postamble suppressed under PerlOnJava (no XS build)\n";
}

package DBDI; # reserved on PAUSE by upstream DBI::DBD

1;

__END__

=head1 NAME

DBI::DBD - PerlOnJava shim for the DBI driver build helper

=head1 DESCRIPTION

Stub replacement for L<DBI::DBD> that provides no-op versions of
C<dbd_postamble>, C<dbd_edit_mm_attribs>, C<dbd_dbi_dir> and
C<dbd_dbi_arch_dir> so that CPAN DBD::* C<Makefile.PL> files load
successfully under C<jperl> / C<jcpan>. Actual driver code for
supported DBDs is bundled inside the PerlOnJava JAR.

=cut
