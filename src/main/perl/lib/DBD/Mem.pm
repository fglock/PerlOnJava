package DBD::Mem;
use strict;
use warnings;

our $VERSION = '0.001';

# DBD::Mem compatibility shim for PerlOnJava
# Maps dbi:Mem: to SQLite in-memory via jdbc:sqlite::memory:
# Perl 5's DBD::Mem is a pure-Perl in-memory table engine.
# We emulate it using SQLite's in-memory mode which provides
# equivalent SQL functionality.

sub _dsn_to_jdbc {
    my ($class, $dsn_rest) = @_;
    return "jdbc:sqlite::memory:";
}

1;

__END__

=head1 NAME

DBD::Mem - PerlOnJava in-memory database driver via SQLite

=head1 SYNOPSIS

    use DBI;
    my $dbh = DBI->connect("dbi:Mem:", "", "");
    my $dbh = DBI->connect("dbi:Mem(RaiseError=1):", "", "");

=head1 DESCRIPTION

This is a PerlOnJava compatibility shim that maps C<dbi:Mem:> connections
to SQLite in-memory databases (C<jdbc:sqlite::memory:>).

In Perl 5, C<DBD::Mem> is a pure-Perl in-memory table engine built on
C<SQL::Statement>. PerlOnJava emulates this using SQLite's in-memory mode,
which provides equivalent SQL functionality.

=cut
