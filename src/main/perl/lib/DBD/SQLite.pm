package DBD::SQLite;
use strict;
use warnings;

our $VERSION = '1.74';

# Inherit the driver factory + handle classes from DBD::JDBC. We only
# need to implement DSN translation. Real DBI discovers DBD::SQLite via
# install_driver, calls SQLite->driver (inherited), gets a drh, then
# calls $drh->connect which routes to DBD::JDBC::dr::connect (registered
# in Java) — that function consults SQLite->_dsn_to_jdbc to map the
# DSN suffix to a JDBC URL.
#
# This is NOT the CPAN DBD::SQLite (XS wrapper around sqlite3). It is
# a PerlOnJava shim that delegates to Xerial's sqlite-jdbc driver
# bundled with PerlOnJava.

use DBI ();
use DBD::JDBC ();
our @ISA = ('DBD::JDBC');

{
    package DBD::SQLite::dr;
    our @ISA = ('DBD::JDBC::dr');

    # Override connect to translate the DSN before the Java entrypoint
    # takes over. We call the Java-registered DBD::JDBC::dr::connect
    # with the translated URL.
    sub connect {
        my ($drh, $dbname, $user, $pass, $attr) = @_;
        my $jdbc_url = DBD::SQLite->_dsn_to_jdbc($dbname);
        return DBD::JDBC::dr::connect($drh, $jdbc_url, $user, $pass, $attr);
    }
}

{
    package DBD::SQLite::db;
    our @ISA = ('DBD::JDBC::db');
}

{
    package DBD::SQLite::st;
    our @ISA = ('DBD::JDBC::st');
}

# Translate Perl DBI DSN to JDBC URL for SQLite
# Handles:
#   dbname=:memory:       -> jdbc:sqlite::memory:
#   :memory:              -> jdbc:sqlite::memory:
#   dbname=/path/to/db    -> jdbc:sqlite:/path/to/db
#   /path/to/db           -> jdbc:sqlite:/path/to/db
#   dbname=file.db        -> jdbc:sqlite:file.db
sub _dsn_to_jdbc {
    my ($class, $dsn_rest) = @_;

    my $dbname;
    if ($dsn_rest =~ /(?:^|;)dbname=(.+?)(?:;|$)/) {
        $dbname = $1;
    } elsif ($dsn_rest =~ /(?:^|;)database=(.+?)(?:;|$)/i) {
        $dbname = $1;
    } elsif ($dsn_rest =~ /^:memory:$/) {
        $dbname = ':memory:';
    } elsif ($dsn_rest !~ /=/) {
        $dbname = $dsn_rest;
    } else {
        $dbname = ':memory:';
    }

    return "jdbc:sqlite:$dbname";
}

1;

__END__

=head1 NAME

DBD::SQLite - PerlOnJava SQLite driver via JDBC (sqlite-jdbc)

=head1 SYNOPSIS

    use DBI;
    my $dbh = DBI->connect("dbi:SQLite:dbname=:memory:", "", "");
    my $dbh = DBI->connect("dbi:SQLite::memory:", "", "");
    my $dbh = DBI->connect("dbi:SQLite:dbname=/path/to/db.sqlite", "", "");

=head1 DESCRIPTION

This is a PerlOnJava compatibility shim that translates Perl DBI DSN format
to JDBC URL format for the Xerial sqlite-jdbc driver bundled with PerlOnJava.

It inherits handle architecture from L<DBD::JDBC>, which bridges
upstream DBI's driver-architecture expectations to PerlOnJava's
Java-backed JDBC methods.

=cut
