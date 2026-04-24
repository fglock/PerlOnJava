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

# Upstream DBI.pm's default do() is:
#   my $sth = $dbh->prepare(...); $sth->execute(...); my $rows = $sth->rows;
# which leaves $sth's PreparedStatement alive until scope exit DESTROY
# fires. Under PerlOnJava's JVM refcount model the sth often stays
# reachable via $dbh->{sth} or via cached references, so DESTROY is
# delayed — SQLite-JDBC then keeps a shared table lock that blocks
# subsequent DROP / ALTER / schema change on the same connection.
# DBIC t/storage/on_connect_do.t#8 hits this precisely (on_disconnect_do
# runs a SELECT via do() then a DROP TABLE on the same dbh).
#
# Override do() in DBD::JDBC::db to call $sth->finish before return, so
# the underlying java.sql.PreparedStatement is closed deterministically.
#
# NOTE: `do` is a Perl keyword (do FILE / do BLOCK). PerlOnJava's runtime
# currently doesn't expose user `sub do` via ordinary method dispatch
# (defined(&DBD::JDBC::db::do) returns NO even with \&... working), so
# we install via glob aliasing to an explicitly-named helper.
sub _do_impl {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or do { $sth->finish; return undef };
    my $rows = $sth->rows;
    $sth->finish;
    return ($rows == 0) ? "0E0" : $rows;
}
*DBD::JDBC::db::do = \&_do_impl;

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

# Scope-exit DESTROY closes the underlying JDBC PreparedStatement /
# ResultSet. Without this, a `$dbh->do('SELECT ...')` leaves its sth
# stmt open once the local $sth goes out of scope (upstream DBI.pm's
# do() relies on scope-exit to tear down), and SQLite-JDBC keeps a
# shared table lock that blocks subsequent DROP TABLE / schema changes
# on the same connection. DBIC t/storage/on_connect_do.t#8 exercises
# exactly that via on_disconnect_do -> DROP TABLE.
sub DESTROY {
    my $sth = shift;
    # finish() is a no-op if already closed (checks stmt.isClosed()).
    # Wrapped in eval because $sth may be partially constructed if an
    # earlier prepare step died.
    eval { $sth->finish if $sth->can('finish') };
}

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
