package DBI;
use strict;

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/Dbi.java

# Example:
#
# java -cp "h2-2.2.224.jar:target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main dbi.pl
#
# # Connect to H2 database
# my $dbh = DBI->connect(
#     "dbi:org.h2.Driver:mem:testdb;DB_CLOSE_DELAY=-1",  # In-memory H2 database
#     "sa",                 # Default H2 username
#     "",                   # Empty password
#     { RaiseError => 1 }
# );

# package DBI::db;
# Class for $dbh

sub do {
    my($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}

sub finish {
    # placeholder
}

sub selectrow_arrayref {
    my($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    return $sth->fetchrow_arrayref();
}

sub selectrow_hashref {
    my($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    return $sth->fetchrow_hashref();
}

sub selectrow_array {
    my $arr = selectrow_arrayref(@_);
    return $arr ? @$arr : ();
}

sub fetchrow_array {
    my $arr = fetchrow_arrayref(@_);
    return $arr ? @$arr : ();
}

sub fetch {
    return fetchrow_arrayref(@_);
}

sub fetchall_arrayref {
    my ($sth, $slice, $max_rows) = @_;

    # Return undef if statement handle is inactive
    return undef unless $sth->{dbh}{Active};

    my @rows;
    my $row_count = 0;

    # Handle different slice types
    if (!defined $slice) {
        # Default behavior - fetch all columns as array refs
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            push @rows, [@$row]; # Create a copy of the row
            $row_count++;
        }
    }
    elsif (ref($slice) eq 'ARRAY') {
        # Array slice - select specific columns by index
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            if (@$slice) {
                push @rows, [map { $row->[$_] } @$slice];
            } else {
                push @rows, [@$row]; # Empty slice means all columns
            }
            $row_count++;
        }
    }
    elsif (ref($slice) eq 'HASH') {
        # Hash slice - fetch as hash refs with selected columns
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_hashref();
            last unless $row;
            if (%$slice) {
                # Select only requested columns
                my %new_row = map { $_ => $row->{$_} }
                    grep { exists $slice->{$_} }
                        keys %$row;
                push @rows, \%new_row;
            } else {
                push @rows, {%$row}; # Empty hash means all columns
            }
            $row_count++;
        }
    }
    elsif (ref($slice) eq 'REF' && ref($$slice) eq 'HASH') {
        # Column index to name mapping
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            my %new_row;
            while (my ($idx, $key) = each %{$$slice}) {
                $new_row{$key} = $row->[$idx];
            }
            push @rows, \%new_row;
            $row_count++;
        }
    }

    return \@rows;
}

1;

__END__

=head1 AUTHORS

DBI by Tim Bunce, L<http://www.tim.bunce.name>

This pod text by Tim Bunce, J. Douglas Dunlop, Jonathan Leffler and others.
Perl by Larry Wall and the C<perl5-porters>.

=head1 COPYRIGHT

The DBI module is Copyright (c) 1994-2012 Tim Bunce. Ireland.
All rights reserved.

You may distribute under the terms of either the GNU General Public
License or the Artistic License, as specified in the Perl 5.10.0 README file.