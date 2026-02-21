package DBI;
use strict;
use warnings;
use XSLoader;

XSLoader::load( 'DBI' );

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/DBI.java

# Example:
#
# java -cp "h2-2.2.224.jar:target/perlonjava-3.0.0.jar" org.perlonjava.app.cli.Main dbi.pl
#
# # Connect to H2 database
# my $dbh = DBI->connect(
#     "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",  # In-memory H2 database
#     "sa",                 # Default H2 username
#     "",                   # Empty password
#     { RaiseError => 1 }
# );

# Cache variables for prepare_cached and connect_cached
our %CACHED_STATEMENTS;
our $MAX_CACHED_STATEMENTS = 100;
our %CACHED_CONNECTIONS;
our $MAX_CACHED_CONNECTIONS = 10;

sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}

sub finish {
    my ($sth) = @_;
    $sth->{Active} = 0;
}

sub clone {
    my ($dbh) = @_;
    my %new_dbh = %{$dbh};  # Shallow copy
    return bless \%new_dbh, ref($dbh);
}

sub err {
    my ($handle) = @_;
    return $handle->{err};
}

sub errstr {
    my ($handle) = @_;
    return $handle->{errstr} || '';
}

sub state {
    my ($handle) = @_;
    my $state = $handle->{state};
    # Return empty string for success code 00000
    return ($state && $state eq '00000') ? '' : ($state || 'S1000');
}

sub selectrow_arrayref {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    return $sth->fetchrow_arrayref();
}

sub selectrow_hashref {
    my ($dbh, $statement, $attr, @params) = @_;
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
    return undef unless $sth->{Database}{Active};

    my @rows;
    my $row_count = 0;

    # Handle different slice types
    if (!defined $slice) {
        # Default behavior - fetch all columns as array refs
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            push @rows, $row; # Use the row directly to avoid unnecessary copying
            $row_count++;
        }
    }
    elsif (ref($slice) eq 'ARRAY') {
        # Array slice - select specific columns by index
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            if (@$slice) {
                push @rows, [ map {$row->[$_]} @$slice ];
            }
            else {
                push @rows, $row; # Use the row directly
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
                my %new_row = map {$_ => $row->{$_}}
                    grep {exists $slice->{$_}}
                        keys %$row;
                push @rows, \%new_row;
            }
            else {
                push @rows, $row; # Use the row directly
            }
            $row_count++;
        }
    }
    elsif (ref($slice) eq 'REF' && ref($slice) eq 'HASH') {
        # Column index to name mapping
        while (!defined($max_rows) || $row_count < $max_rows) {
            my $row = $sth->fetchrow_arrayref();
            last unless $row;
            my %new_row;
            while (my ($idx, $key) = each %{$slice}) {
                $new_row{$key} = $row->[$idx];
            }
            push @rows, \%new_row;
            $row_count++;
        }
    }

    return \@rows;
}

sub fetchall_hashref {
    my ($sth, $key_field) = @_;

    # Return undef if statement handle is inactive
    return undef unless $sth->{Database}{Active};

    my %results;

    # Convert key_field to array ref if it's not already
    my @key_fields = ref($key_field) eq 'ARRAY' ? @$key_field : ($key_field);

    # Get column names/info
    my $hash_key_name = $sth->{FetchHashKeyName} || 'NAME';
    my $fields = $sth->{$hash_key_name};
    my %field_index;
    for my $i (0 .. $#{$fields}) {
        $field_index{$fields->[$i]} = $i + 1; # 1-based indexing
    }

    # Verify key fields exist
    for my $key (@key_fields) {
        unless (exists $field_index{$key} || ($key =~ /^\d+$/ && $key <= @$fields)) {
            return undef; # Invalid key field
        }
    }

    # Fetch all rows
    while (my $row = $sth->fetchrow_hashref()) {
        my $href = \%results;

        # Navigate through all but the last key
        for my $i (0 .. $#key_fields - 1) {
            my $key = $key_fields[$i];
            my $key_value;

            # Handle numeric column reference
            if ($key =~ /^\d+$/) {
                $key_value = $row->{$fields->[$key - 1]};
            }
            else {
                $key_value = $row->{$key};
            }

            $href->{$key_value} ||= {};
            $href = $href->{$key_value};
        }

        # Handle the last key
        my $final_key = $key_fields[-1];
        my $final_value;

        # Handle numeric column reference
        if ($final_key =~ /^\d+$/) {
            $final_value = $row->{$fields->[$final_key - 1]};
        }
        else {
            $final_value = $row->{$final_key};
        }

        $href->{$final_value} = $row; # Use the row directly
    }

    return \%results;
}

sub selectall_arrayref {
    my ($dbh, $statement, $attr, @bind_values) = @_;

    # Handle statement handle or SQL string
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;

    $sth->execute(@bind_values) or return undef;

    # Extract MaxRows and Slice/Columns attributes
    my $max_rows = $attr->{MaxRows};
    my $slice = $attr->{Slice};

    # Handle Columns attribute (convert 1-based indices to 0-based)
    if (!defined $slice && defined $attr->{Columns}) {
        if (ref $attr->{Columns} eq 'ARRAY') {
            $slice = [ map {$_ - 1} @{$attr->{Columns}} ];
        }
        else {
            $slice = $attr->{Columns};
        }
    }

    # Fetch all rows using the specified parameters
    my $rows = $sth->fetchall_arrayref($slice, $max_rows);

    # Call finish() if MaxRows was specified
    $sth->finish if defined $max_rows;

    return $rows;
}

sub selectall_hashref {
    my ($dbh, $statement, $key_field, $attr, @bind_values) = @_;

    # Handle statement handle or SQL string
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;

    # Execute with bind values if provided
    $sth->execute(@bind_values) or return undef;

    # Reuse fetchall_hashref to do the heavy lifting
    return $sth->fetchall_hashref($key_field);
}

sub bind_columns {
    my ($sth, @refs) = @_;
    return 1 unless @refs;

    # Clear existing bound columns
    $sth->{bound_columns} = {};

    # Bind each column reference
    for (my $i = 0; $i < @refs; $i++) {
        $sth->bind_col($i + 1, $refs[$i]) or return undef;
    }
    return 1;
}

sub trace {
    my ($dbh, $level, $output) = @_;
    $level ||= 0;

    $dbh->{TraceLevel} = $level;
    $dbh->{TraceOutput} = $output if defined $output;

    return $level;
}

sub trace_msg {
    my ($dbh, $msg, $level) = @_;
    $level ||= 0;

    my $current_level = $dbh->{TraceLevel} || 0;
    if ($level <= $current_level) {
        if ($dbh->{TraceOutput}) {
            # TODO: Write to custom output
            print STDERR $msg;
        } else {
            print STDERR $msg;
        }
    }
    return 1;
}

sub prepare_cached {
    my ($dbh, $sql, $attr, $if_active) = @_;

    my $cache_key = $dbh->{Name} . ':' . $sql;

    if (exists $CACHED_STATEMENTS{$cache_key}) {
        my $sth = $CACHED_STATEMENTS{$cache_key};
        if ($sth->{Database}{Active}) {
            return $sth;
        }
    }

    my $sth = $dbh->prepare($sql, $attr) or return undef;

    # Implement simple LRU by removing oldest if cache is full
    if (keys %CACHED_STATEMENTS >= $MAX_CACHED_STATEMENTS) {
        my @keys = keys %CACHED_STATEMENTS;
        delete $CACHED_STATEMENTS{$keys[0]};
    }

    $CACHED_STATEMENTS{$cache_key} = $sth;
    return $sth;
}

sub connect_cached {
    my ($class, $dsn, $user, $pass, $attr) = @_;

    my $cache_key = "$dsn:$user";

    if (exists $CACHED_CONNECTIONS{$cache_key}) {
        my $dbh = $CACHED_CONNECTIONS{$cache_key};
        if ($dbh->{Active} && $dbh->ping) {
            return $dbh;
        }
    }

    my $dbh = $class->connect($dsn, $user, $pass, $attr) or return undef;

    # Implement simple LRU
    if (keys %CACHED_CONNECTIONS >= $MAX_CACHED_CONNECTIONS) {
        my @keys = keys %CACHED_CONNECTIONS;
        delete $CACHED_CONNECTIONS{$keys[0]};
    }

    $CACHED_CONNECTIONS{$cache_key} = $dbh;
    return $dbh;
}

1;

__END__

Author and Copyright messages from the original DBI.pm:

=head1 AUTHORS

DBI by Tim Bunce, L<http://www.tim.bunce.name>

This pod text by Tim Bunce, J. Douglas Dunlop, Jonathan Leffler and others.
Perl by Larry Wall and the C<perl5-porters>.

=head1 COPYRIGHT

The DBI module is Copyright (c) 1994-2012 Tim Bunce. Ireland.
All rights reserved.

You may distribute under the terms of either the GNU General Public
License or the Artistic License, as specified in the Perl 5.10.0 README file.
