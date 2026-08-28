package DBD::Pg;

use strict;
use warnings;

our $VERSION = '3.18.0';
our $drh;

# PerlOnJava compatibility shim for DBD::Pg.  The actual connection and
# statement operations are supplied by the in-JVM JDBC implementation; this
# module only adapts DBI's PostgreSQL DSN syntax to a PostgreSQL JDBC URL.
use DBI ();
use DBD::JDBC ();
our @ISA = ('DBD::JDBC');

{
    package DBD::Pg::dr;
    our @ISA = ('DBD::JDBC::dr');

    sub connect {
        my ($drh, $dsn_rest, $user, $pass, $attr) = @_;
        my $jdbc_url = DBD::Pg->_dsn_to_jdbc($dsn_rest);
        my $dbh = DBD::JDBC::dr::connect($drh, $jdbc_url, $user, $pass, $attr);
        if ($dbh) {
            $dbh = bless $dbh, 'DBD::Pg::db';
            $dbh->{Driver} = $drh;
        }
        return $dbh;
    }
}

{
    package DBD::Pg::db;
    our @ISA = ('DBD::JDBC::db');
}

{
    package DBD::Pg::st;
    our @ISA = ('DBD::JDBC::st');
}

# Translate the libpq-style key/value DSN accepted by DBD::Pg.  The JDBC
# driver uses URL query parameters for the same connection properties.
sub _dsn_to_jdbc {
    my ($class, $dsn_rest) = @_;
    $dsn_rest = '' unless defined $dsn_rest;

    # Also accept the short DBD::Pg form, e.g. "mydb".
    if ($dsn_rest !~ /=/) {
        return "jdbc:postgresql://localhost/$dsn_rest";
    }

    my (%values, @options);
    for my $part (split /;/, $dsn_rest) {
        next unless $part =~ /^\s*([^=\s]+)\s*=\s*(.*?)\s*$/s;
        my ($key, $value) = (lc($1), $2);
        if ($key eq 'dbname' || $key eq 'database') {
            $values{dbname} = $value;
        } elsif ($key eq 'host' || $key eq 'port') {
            $values{$key} = $value;
        } elsif ($key ne 'user' && $key ne 'password') {
            # JDBC accepts PostgreSQL driver properties as URL parameters.
            push @options, $key . '=' . $value;
        }
    }

    my $host = length($values{host} // '') ? $values{host} : 'localhost';
    my $port = length($values{port} // '') ? ':' . $values{port} : '';
    my $dbname = $values{dbname} // '';
    my $url = "jdbc:postgresql://$host$port/$dbname";
    $url .= '?' . join('&', @options) if @options;
    return $url;
}

1;

__END__

=head1 NAME

DBD::Pg - PerlOnJava PostgreSQL driver via JDBC

=head1 DESCRIPTION

This is a PerlOnJava compatibility shim.  It preserves the DBD::Pg DBI DSN
interface and delegates database operations to the PostgreSQL JDBC driver,
which must be supplied on the PerlOnJava classpath or selected with
C<Configure.pl>.

=cut
