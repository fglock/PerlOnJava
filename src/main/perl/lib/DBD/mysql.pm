package DBD::mysql;

use strict;
use warnings;

our $VERSION = '5.013';

# PerlOnJava's MySQL driver is the ordinary DBI/JDBC implementation with a
# DBD::mysql-compatible DSN and handle namespace.  The MySQL JDBC driver is
# supplied separately through Configure.pl or CLASSPATH; this shim deliberately
# does not add a driver dependency to the runtime.
use DBI ();
use DBD::JDBC ();
our @ISA = ('DBD::JDBC');

{
    package DBD::mysql::dr;
    our @ISA = ('DBD::JDBC::dr');

    sub connect {
        my ($drh, $dsn_rest, $user, $pass, $attr) = @_;
        my $jdbc_url = DBD::mysql->_dsn_to_jdbc($dsn_rest);
        my $dbh = DBD::JDBC::dr::connect($drh, $jdbc_url, $user, $pass, $attr);
        if ($dbh) {
            $dbh = bless $dbh, 'DBD::mysql::db';
            $dbh->{Driver} = $drh;
        }
        return $dbh;
    }
}

{
    package DBD::mysql::db;
    our @ISA = ('DBD::JDBC::db');
}

{
    package DBD::mysql::st;
    our @ISA = ('DBD::JDBC::st');
}

# Translate the conventional DBD::mysql DSN suffix to a MySQL JDBC URL.
# Supported forms include:
#   database=test;host=db.example;port=3307
#   test;host=db.example
#   host=db.example;database=test
# A bare suffix is treated as the database name, matching DBD::mysql.
sub _dsn_to_jdbc {
    my ($class, $dsn_rest) = @_;
    $dsn_rest = '' unless defined $dsn_rest;

    # This is useful for callers that already use a JDBC URL with the mysql
    # driver name and keeps the mapping harmless for DBI-compatible wrappers.
    return $dsn_rest if $dsn_rest =~ /^jdbc:mysql:/i;

    my ($database, $host, $port);
    my @parts = split /;/, $dsn_rest, -1;
    for my $part (@parts) {
        next unless length $part;
        if ($part =~ /^([^=]+)=(.*)$/) {
            my ($key, $value) = (lc $1, $2);
            $database = $value if $key eq 'database' || $key eq 'dbname';
            $host = $value if $key eq 'host' || $key eq 'hostname';
            $port = $value if $key eq 'port';
        } elsif (!defined $database) {
            $database = $part;
        }
    }

    $database = '' unless defined $database;
    $host = 'localhost' unless defined $host && length $host;
    $port = 3306 unless defined $port && length $port;

    my $url = "jdbc:mysql://$host:$port/$database";
    return $url;
}

1;

__END__

=head1 NAME

DBD::mysql - PerlOnJava MySQL driver via JDBC

=head1 DESCRIPTION

This is a PerlOnJava compatibility shim for C<DBD::mysql>.  It translates
the standard DBI MySQL DSN to a JDBC URL and delegates database operations to
the bundled C<DBD::JDBC> implementation.

The MySQL JDBC driver is not bundled.  Add one with C<Configure.pl> or place
its JAR on C<CLASSPATH> before running PerlOnJava.

=cut
