package DBI;
use strict;
use warnings;
use Scalar::Util ();
use XSLoader;

our $VERSION = '1.643';

XSLoader::load( 'DBI' );

# DBI::db and DBI::st inherit from DBI so method dispatch works
# when handles are blessed into subclass packages
@DBI::db::ISA = ('DBI');
@DBI::st::ISA = ('DBI');

# Wrap Java DBI methods with HandleError support and DBI attribute tracking.
# In real DBI, HandleError is called from C before RaiseError/die.
# Since our Java methods just die with RaiseError, we wrap them in Perl
# to intercept the die and call HandleError from Perl context (where
# caller() works correctly for DBIC's __find_caller).
{
    my $orig_prepare = \&DBI::prepare;
    my $orig_execute = \&DBI::execute;
    my $orig_finish  = \&DBI::finish;
    my $orig_disconnect = \&DBI::disconnect;

    no warnings 'redefine';

    *DBI::prepare = sub {
        my $result = eval { $orig_prepare->(@_) };
        if ($@) {
            return _handle_error($_[0], $@);
        }
        if ($result) {
            my $dbh = $_[0];
            my $sql = $_[1];
            # Track statement handle count (Kids) and last statement
            $dbh->{Kids} = ($dbh->{Kids} || 0) + 1;
            $dbh->{Statement} = $sql;
            # Link sth back to parent dbh (weak ref to avoid circular reference
            # with CachedKids: $dbh → CachedKids → $sth → Database → $dbh).
            # In Perl 5's XS-based DBI, child→parent references are weak.
            $result->{Database} = $dbh;
            Scalar::Util::weaken($result->{Database});
            # RootClass support: re-bless the statement handle into the ::st
            # subclass if the parent dbh has a RootClass attribute.
            if (my $root = $dbh->{RootClass}) {
                my $st_class = "${root}::st";
                if (UNIVERSAL::isa($st_class, 'DBI::st')) {
                    bless $result, $st_class;
                }
            }
        }
        return $result;
    };

    *DBI::execute = sub {
        my $result = eval { $orig_execute->(@_) };
        if ($@) {
            # For sth errors, try HandleError on the parent dbh first, then sth
            my $sth_handle = $_[0];
            my $parent_dbh = $sth_handle->{Database};
            if ($parent_dbh && Scalar::Util::reftype($parent_dbh->{HandleError} || '') eq 'CODE') {
                return _handle_error_with_handler($parent_dbh->{HandleError}, $@);
            }
            return _handle_error($sth_handle, $@);
        }
        if ($result) {
            my $sth = $_[0];
            my $dbh = $sth->{Database};
            if ($dbh) {
                # Only mark as active for result-returning statements (SELECT etc.)
                # DDL/DML statements (CREATE, INSERT, etc.) have NUM_OF_FIELDS == 0
                if (($sth->{NUM_OF_FIELDS} || 0) > 0) {
                    if (!$sth->{Active}) {
                        $dbh->{ActiveKids} = ($dbh->{ActiveKids} || 0) + 1;
                    }
                    $sth->{Active} = 1;
                } else {
                    # DML statement: mark as inactive
                    if ($sth->{Active}) {
                        my $active = $dbh->{ActiveKids} || 0;
                        $dbh->{ActiveKids} = $active > 0 ? $active - 1 : 0;
                    }
                    $sth->{Active} = 0;
                }
            }
        }
        return $result;
    };

    *DBI::finish = sub {
        my $sth = $_[0];
        if ($sth->{Active} && $sth->{Database}) {
            my $active = $sth->{Database}{ActiveKids} || 0;
            $sth->{Database}{ActiveKids} = $active > 0 ? $active - 1 : 0;
            $sth->{Active} = 0;
        }
        return $orig_finish->(@_);
    };

    *DBI::disconnect = sub {
        my $dbh = $_[0];
        $dbh->{Active} = 0;
        return $orig_disconnect->(@_);
    };
}

# DESTROY for statement handles — calls finish() if still active.
# This matches Perl DBI behavior where sth DESTROY triggers finish().
sub DBI::st::DESTROY {
    my $sth = $_[0];
    return unless $sth && ref($sth);
    if ($sth->{Active}) {
        eval { $sth->finish() };
    }
}

# DESTROY for database handles — calls disconnect() if still active.
# This matches Perl DBI behavior where dbh DESTROY disconnects.
sub DBI::db::DESTROY {
    my $dbh = $_[0];
    return unless $dbh && ref($dbh);
    if ($dbh->{Active}) {
        eval { $dbh->disconnect() };
    }
}

sub _handle_error {
    my ($handle, $err) = @_;
    if (ref($handle) && Scalar::Util::reftype($handle->{HandleError} || '') eq 'CODE') {
        # Call HandleError — if it throws (as DBIC's does), propagate the exception
        $handle->{HandleError}->($err, $handle, undef);
        # If HandleError returns without dying, return undef (error handled)
        return undef;
    }
    die $err;
}

sub _handle_error_with_handler {
    my ($handler, $err) = @_;
    $handler->($err, undef, undef);
    return undef;
}

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/runtime/perlmodule/DBI.java

# SQL type constants (from DBI spec, java.sql.Types values)
# Used by DBIx::Class::Storage::DBI::SQLite and others
use constant {
    SQL_GUID            => -11,
    SQL_WLONGVARCHAR    => -10,
    SQL_WVARCHAR        => -9,
    SQL_WCHAR           => -8,
    SQL_BIGINT          => -5,
    SQL_BIT             => -7,
    SQL_TINYINT         => -6,
    SQL_LONGVARBINARY   => -4,
    SQL_VARBINARY       => -3,
    SQL_BINARY          => -2,
    SQL_LONGVARCHAR     => -1,
    SQL_UNKNOWN_TYPE    => 0,
    SQL_ALL_TYPES       => 0,
    SQL_CHAR            => 1,
    SQL_NUMERIC         => 2,
    SQL_DECIMAL         => 3,
    SQL_INTEGER         => 4,
    SQL_SMALLINT        => 5,
    SQL_FLOAT           => 6,
    SQL_REAL            => 7,
    SQL_DOUBLE          => 8,
    SQL_DATETIME        => 9,
    SQL_DATE            => 9,
    SQL_INTERVAL        => 10,
    SQL_TIME            => 10,
    SQL_TIMESTAMP       => 11,
    SQL_VARCHAR         => 12,
    SQL_BOOLEAN         => 16,
    SQL_UDT             => 17,
    SQL_UDT_LOCATOR     => 18,
    SQL_ROW             => 19,
    SQL_REF             => 20,
    SQL_BLOB            => 30,
    SQL_BLOB_LOCATOR    => 31,
    SQL_CLOB            => 40,
    SQL_CLOB_LOCATOR    => 41,
    SQL_ARRAY           => 50,
    SQL_MULTISET        => 55,
    SQL_TYPE_DATE       => 91,
    SQL_TYPE_TIME       => 92,
    SQL_TYPE_TIMESTAMP  => 93,
    SQL_TYPE_TIME_WITH_TIMEZONE      => 94,
    SQL_TYPE_TIMESTAMP_WITH_TIMEZONE => 95,
};

# DSN translation: convert Perl DBI DSN format to JDBC URL
# This wraps the Java-side connect() to support dbi:Driver:... format
# Handles attribute syntax: dbi:Driver(RaiseError=1):rest
{
    no warnings 'redefine';
    my $orig_connect = \&connect;
    *connect = sub {
        my ($class, $dsn, $user, $pass, $attr) = @_;

        # Fall back to DBI_DSN env var if no DSN provided
        $dsn = $ENV{DBI_DSN} if !defined $dsn || !length $dsn;

        $dsn = '' unless defined $dsn;
        $user = '' unless defined $user;
        $pass = '' unless defined $pass;
        $attr = {} unless ref $attr eq 'HASH';
        my $driver_name;
        my $dsn_rest;
        if ($dsn =~ /^dbi:(\w*)(?:\(([^)]*)\))?:(.*)$/i) {
            my ($driver, $dsn_attrs, $rest) = ($1, $2, $3);

            # Fall back to DBI_DRIVER env var if driver part is empty
            $driver = $ENV{DBI_DRIVER} if !length($driver) && $ENV{DBI_DRIVER};

            # If still no driver, die with the expected Perl DBI error message
            if (!length($driver)) {
                die "I can't work out what driver to use (no driver in DSN and DBI_DRIVER env var not set)\n";
            }

            $driver_name = $driver;
            $dsn_rest = $rest;

            # Parse DSN-embedded attributes like (RaiseError=1,PrintError=0)
            if (defined $dsn_attrs && length $dsn_attrs) {
                for my $pair (split /,/, $dsn_attrs) {
                    if ($pair =~ /^\s*(\w+)\s*=\s*(.*?)\s*$/) {
                        $attr->{$1} = $2 unless exists $attr->{$1};
                    }
                }
            }

            my $dbd_class = "DBD::$driver";
            eval "require $dbd_class";
            if ($dbd_class->can('_dsn_to_jdbc')) {
                $dsn = $dbd_class->_dsn_to_jdbc($rest);
            }
        }
        my $dbh = $orig_connect->($class, $dsn, $user, $pass, $attr);
        if ($dbh && $driver_name) {
            # Set Driver attribute so DBIx::Class can detect the driver
            # (e.g. $dbh->{Driver}{Name} returns "SQLite")
            $dbh->{Driver} = bless { Name => $driver_name }, 'DBI::dr';
            # Initialize DBI handle tracking attributes
            $dbh->{Kids} = 0;
            $dbh->{ActiveKids} = 0;
            $dbh->{Statement} = '';
            # Set Name to DSN rest (after driver:), not the JDBC URL
            $dbh->{Name} = $dsn_rest if defined $dsn_rest;
        }
        # RootClass support: re-bless the database handle into the subclass
        # specified by the RootClass attribute. This is used by CDBI compat
        # (via Ima::DBI) which sets RootClass => 'DBIx::ContextualFetch'.
        # The RootClass module provides ::db and ::st subclasses that add
        # methods like select_row, select_hash, etc. to statement handles.
        # Without this, handles are always DBI::db/DBI::st and those methods
        # are unavailable, breaking t/cdbi/ tests with:
        #   "Can't locate object method select_row via package DBI::st"
        if ($dbh && $attr->{RootClass}) {
            my $root = $attr->{RootClass};
            eval "require $root" unless $root->isa('DBI');
            my $db_class = "${root}::db";
            if ($db_class->isa('DBI::db') || eval { require $root; $db_class->isa('DBI::db') }) {
                bless $dbh, $db_class;
            }
            $dbh->{RootClass} = $root;
        }
        return $dbh;
    };
}

# Example:
#
# java -cp "h2-2.2.224.jar:target/perlonjava-5.42.0.jar" org.perlonjava.app.cli.Main dbi.pl
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

# FETCH/STORE methods for tied-hash compatibility
# In real Perl DBI, handles are tied hashes. DBIx::Class calls
# $dbh->FETCH('Active') explicitly, so we need method wrappers.
sub FETCH {
    my ($self, $key) = @_;
    return $self->{$key};
}

sub STORE {
    my ($self, $key, $value) = @_;
    $self->{$key} = $value;
}

sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    $sth->finish();  # Close JDBC statement to release locks
    ($rows == 0) ? "0E0" : $rows;
}

sub finish {
    my ($sth) = @_;
    $sth->{Active} = 0;
}

# Batch execution: calls $fetch_tuple->() repeatedly to get parameter arrays,
# executes the prepared statement for each, and tracks results in $tuple_status.
sub execute_for_fetch {
    my ($sth, $fetch_tuple_sub, $tuple_status) = @_;
    # start with empty status array
    if ($tuple_status) {
        @$tuple_status = ();
    } else {
        $tuple_status = [];
    }

    my $rc_total = 0;
    my $err_count;
    while ( my $tuple = &$fetch_tuple_sub() ) {
        my $rc = eval { $sth->execute(@$tuple) };
        if ($rc) {
            push @$tuple_status, $rc;
            $rc_total = ($rc >= 0 && $rc_total >= 0) ? $rc_total + $rc : -1;
        }
        else {
            $err_count++;
            push @$tuple_status, [ $sth->err, $sth->errstr || $@, $sth->state ];
        }
    }
    my $tuples = @$tuple_status;
    if ($err_count) {
        my $err_msg = "executing $tuples generated $err_count errors";
        die $err_msg if $sth->{Database}{RaiseError};
        warn $err_msg if $sth->{Database}{PrintError};
        return undef;
    }
    $tuples ||= "0E0";
    return $tuples unless wantarray;
    return ($tuples, $rc_total);
}

sub bind_param {
    my ($sth, $param_num, $value, $attr) = @_;
    # Store bind parameter for later use
    $sth->{_bind_params} ||= {};
    $sth->{_bind_params}{$param_num} = $value;
    return 1;
}

sub clone {
    my ($dbh) = @_;
    my %new_dbh = %{$dbh};  # Shallow copy
    return bless \%new_dbh, ref($dbh);
}

sub quote {
    my ($dbh, $str, $data_type) = @_;
    return "NULL" unless defined $str;
    # For numeric SQL data types, return the value unquoted
    if (defined $data_type) {
        if ($data_type == SQL_INTEGER  || $data_type == SQL_SMALLINT ||
            $data_type == SQL_DECIMAL  || $data_type == SQL_NUMERIC  ||
            $data_type == SQL_FLOAT    || $data_type == SQL_REAL     ||
            $data_type == SQL_DOUBLE   || $data_type == SQL_BIGINT   ||
            $data_type == SQL_TINYINT  || $data_type == SQL_BIT      ||
            $data_type == SQL_BOOLEAN) {
            return $str;
        }
    }
    # Default: escape single quotes and wrap in single quotes
    $str =~ s/'/''/g;
    return "'$str'";
}

sub quote_identifier {
    my ($dbh, @id) = @_;
    # Simple implementation: quote with double quotes, escaping embedded double quotes
    my $quote_char = '"';
    my @quoted;
    for my $part (@id) {
        next unless defined $part;
        $part =~ s/"/""/g;
        push @quoted, qq{$quote_char${part}$quote_char};
    }
    return join('.', @quoted);
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

sub selectcol_arrayref {
    my ($dbh, $statement, $attr, @bind_values) = @_;
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;
    $sth->execute(@bind_values) or return undef;
    my @col;
    my $columns = $attr && ref($attr) eq 'HASH' && $attr->{Columns}
        ? $attr->{Columns} : [1];
    if (@$columns == 1) {
        my $idx = $columns->[0] - 1;
        while (my $row = $sth->fetchrow_arrayref()) {
            push @col, $row->[$idx];
        }
    } else {
        while (my $row = $sth->fetchrow_arrayref()) {
            push @col, map { $row->[$_ - 1] } @$columns;
        }
    }
    return \@col;
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

    # Use a per-dbh cache (like real DBI's CachedKids) to avoid cross-connection
    # cache hits when multiple connections share the same Name (e.g., :memory:)
    $dbh->{CachedKids} ||= {};
    my $cache = $dbh->{CachedKids};

    if (exists $cache->{$sql}) {
        my $sth = $cache->{$sql};
        if ($sth->{Database}{Active}) {
            # Handle if_active parameter:
            # 1 = warn and finish, 2 = finish silently, 3 = return new sth
            if ($sth->{Active}) {
                if ($if_active && $if_active == 3) {
                    # Return a fresh sth instead of the active cached one
                    my $new_sth = _prepare_as_cached($dbh, $sql, $attr);
                    return undef unless $new_sth;
                    $cache->{$sql} = $new_sth;
                    return $new_sth;
                }
                # Auto-finish the stale active sth before reuse.
                # In Perl 5 DBI, cursor DESTROY calls finish() deterministically.
                # PerlOnJava's GC timing means DESTROY may not have fired yet.
                eval { $sth->finish() };
            }
            return $sth;
        }
    }

    my $sth = _prepare_as_cached($dbh, $sql, $attr);
    return undef unless $sth;
    $cache->{$sql} = $sth;
    return $sth;
}

# Call prepare() but rewrite error messages to say prepare_cached.
# This matches real DBI behavior where prepare_cached is the reported method.
sub _prepare_as_cached {
    my ($dbh, $sql, $attr) = @_;
    my $sth = eval { $dbh->prepare($sql, $attr) };
    if ($@) {
        my $err = "$@";
        $err =~ s/\bDBI prepare failed\b/DBI prepare_cached failed/g;
        die $err;
    }
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
