package DBD::SQLite;
use strict;
use warnings;

our $VERSION = '1.74';

# Translate Perl DBI DSN to JDBC URL for SQLite
# Handles:
#   dbi:SQLite:dbname=:memory:       -> jdbc:sqlite::memory:
#   dbi:SQLite::memory:              -> jdbc:sqlite::memory:
#   dbi:SQLite:dbname=/path/to/db    -> jdbc:sqlite:/path/to/db
#   dbi:SQLite:/path/to/db           -> jdbc:sqlite:/path/to/db
#   dbi:SQLite:dbname=file.db        -> jdbc:sqlite:file.db
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

=cut
