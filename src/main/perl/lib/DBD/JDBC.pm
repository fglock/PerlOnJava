package DBD::JDBC;

# PerlOnJava-specific base driver for JDBC-backed DBI drivers.
#
# This is not the CPAN DBD::JDBC (which uses a separate Java proxy
# process). It's an in-JVM base that provides the driver / dbh / sth
# handle architecture expected by upstream DBI (DBI 1.647 + DBI::PurePerl)
# on top of the JDBC connect / prepare / execute / fetch methods
# registered in Java under DBD::JDBC::{dr,db,st}.
#
# Per-flavour drivers (DBD::SQLite, DBD::Mem, …) inherit from this and
# provide a `_dsn_to_jdbc` class method that maps a Perl DBI DSN to a
# JDBC URL.

use strict;
use warnings;

our $VERSION = '0.01';

our $drh = undef;

sub driver {
    my ($class, $attr) = @_;
    return $drh if $drh;

    ($drh) = DBI::_new_drh("${class}::dr", {
        Name        => ($class =~ /^DBD::(\w+)/)[0] || 'JDBC',
        Version     => $VERSION,
        Attribution => "$class via JDBC (PerlOnJava)",
    });
    return $drh;
}

sub CLONE { undef $drh; }

# ---------------------------------------------------------------------
package DBD::JDBC::dr;
our @ISA = ('DBD::_::dr');
use strict;

# Upstream's install_driver calls $class->driver; that returns our drh.
# connect($drh, $dsn_rest, $user, $pass, $attr) builds the dbh.
#
# For our hybrid model, we DELEGATE to the Java-registered
# DBD::JDBC::dr::connect below (installed at startup from DBI.java).
# The per-flavour driver's `_dsn_to_jdbc` converts the Perl-DBI DSN
# suffix to a JDBC URL before the call reaches the Java entry point.
#
# The Java connect returns a *flat* hashref blessed into DBD::JDBC::db
# (not an upstream tied outer / inner pair). For method dispatch,
# upstream DBI's AUTOLOAD looks up $h->{ImplementorClass}::method; since
# our dbh is blessed directly into DBD::JDBC::db (or a subclass), Perl's
# normal method-resolution finds the Java-registered methods.

# data_sources can be overridden by subclasses.

# ---------------------------------------------------------------------
package DBD::JDBC::db;
our @ISA = ('DBD::_::db');
use strict;

# `do` is inherited from DBD::_::db (via DBI.pm), which calls prepare +
# execute + (optionally) rows — that all routes back into our
# Java-registered methods on this class.

# ---------------------------------------------------------------------
package DBD::JDBC::st;
our @ISA = ('DBD::_::st');
use strict;

# Alias fetch/fetchrow to our Java-registered fetchrow_arrayref so
# Perl's MRO stops on DBD::JDBC::st first and doesn't fall through to
# DBD::_::st's defaults (which call each other recursively assuming
# the driver has implemented at least one).
*fetch    = \&fetchrow_arrayref;
*fetchrow = \&fetchrow_arrayref;

1;

__END__

=head1 NAME

DBD::JDBC - PerlOnJava base driver for JDBC-backed DBDs

=head1 DESCRIPTION

This module is installed by PerlOnJava and is not a standalone CPAN
module. It exists to bridge between upstream DBI's driver-architecture
expectations and PerlOnJava's in-JVM JDBC backend.

Per-flavour drivers (L<DBD::SQLite>, L<DBD::Mem>, etc.) inherit from
this driver and only need to provide a C<_dsn_to_jdbc> class method.

=cut
