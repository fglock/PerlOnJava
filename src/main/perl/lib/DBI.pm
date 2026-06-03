package DBI;
use strict;
use warnings;
use Scalar::Util ();
use XSLoader;
use Exporter 'import';

our $VERSION = '1.643';
our $stderr = 2000000000;
our ($err, $errstr, $state);
our ($ACTIVE_FETCH_SWEEPING, $DDL_SWEEPING);
our %InstalledDrivers;
our $SqlEngineStorePatched;
our $AnyDataDestroyPatched;
our $SqlStatementTermPatched;
our %DriverPrefix = (
    AnyData   => 'ad_',
    File      => 'f_',
    JDBC      => 'jdbc_',
    SQLite    => 'sqlite_',
    SqlEngine => 'sql_',
    Sponge    => 'sponge_',
);

# References to the Java-backed DBI methods installed by XSLoader::load().
# Perl-level wrappers use these for JDBC handles, while pure-Perl DBD handles
# dispatch through their ImplementorClass packages.
our ($JDBC_CONNECT, $JDBC_PREPARE, $JDBC_EXECUTE, $JDBC_FINISH,
     $JDBC_DISCONNECT, $JDBC_PING, $JDBC_FETCHROW_ARRAYREF,
     $JDBC_FETCHROW_HASHREF);

# SQL type constants exported on demand, e.g. `use DBI qw(SQL_BLOB SQL_VARCHAR)`
# or via the :sql_types tag. Mirrors real DBI's export interface so modules
# like CGI::Session::Driver::sqlite can `use DBI qw(SQL_BLOB)` and use the
# bareword under `use strict`.
our @SQL_TYPES = qw(
    SQL_GUID SQL_WLONGVARCHAR SQL_WVARCHAR SQL_WCHAR SQL_BIGINT SQL_BIT
    SQL_TINYINT SQL_LONGVARBINARY SQL_VARBINARY SQL_BINARY SQL_LONGVARCHAR
    SQL_UNKNOWN_TYPE SQL_ALL_TYPES SQL_CHAR SQL_NUMERIC SQL_DECIMAL SQL_INTEGER
    SQL_SMALLINT SQL_FLOAT SQL_REAL SQL_DOUBLE SQL_DATETIME SQL_DATE
    SQL_INTERVAL SQL_TIME SQL_TIMESTAMP SQL_VARCHAR SQL_BOOLEAN SQL_UDT
    SQL_UDT_LOCATOR SQL_ROW SQL_REF SQL_BLOB SQL_BLOB_LOCATOR SQL_CLOB
    SQL_CLOB_LOCATOR SQL_ARRAY SQL_MULTISET SQL_TYPE_DATE SQL_TYPE_TIME
    SQL_TYPE_TIMESTAMP SQL_TYPE_TIME_WITH_TIMEZONE
    SQL_TYPE_TIMESTAMP_WITH_TIMEZONE
);
our @EXPORT_OK = (@SQL_TYPES);
our %EXPORT_TAGS = (
    sql_types => [@SQL_TYPES],
);

XSLoader::load( 'DBI' );

# DBI handle classes inherit from DBI so method dispatch works
# when handles are blessed into subclass packages
@DBI::dr::ISA = ('DBI');
@DBI::db::ISA = ('DBI');
@DBI::st::ISA = ('DBI');

# Upstream DBI exposes DBD::_::* base classes for driver shims.  PerlOnJava
# handles are plain blessed hashes, so these only need inheritance.
@DBD::_::common::ISA = ('DBI');
@DBD::_::dr::ISA = ('DBI::dr');
@DBD::_::db::ISA = ('DBI::db');
@DBD::_::st::ISA = ('DBI::st');

# Wrap Java DBI methods with HandleError support and DBI attribute tracking.
# In real DBI, HandleError is called from C before RaiseError/die.
# Since our Java methods just die with RaiseError, we wrap them in Perl
# to intercept the die and call HandleError from Perl context (where
# caller() works correctly for DBIC's __find_caller).
{
    $JDBC_CONNECT = \&DBI::connect;
    $JDBC_PREPARE = \&DBI::prepare;
    $JDBC_EXECUTE = \&DBI::execute;
    $JDBC_FINISH  = \&DBI::finish;
    $JDBC_DISCONNECT = \&DBI::disconnect;
    $JDBC_PING = \&DBI::ping;
    $JDBC_FETCHROW_ARRAYREF = \&DBI::fetchrow_arrayref;
    $JDBC_FETCHROW_HASHREF = \&DBI::fetchrow_hashref;

    no warnings 'redefine';

    *DBI::prepare = sub {
        if (!_is_jdbc_handle($_[0])) {
            return _dispatch_implementor_method($_[0], 'prepare', @_[1..$#_]);
        }
        if ($ENV{DBI_TRACE_DESTROY}) {
            my $sql_preview = substr($_[1] // '', 0, 60);
            warn "DBI::prepare on dbh=" . ($_[0]+0) . " Active=" . ($_[0]->{Active}//0) . " SQL: $sql_preview\n";
        }
        my $result = eval { $JDBC_PREPARE->(@_) };
        if ($@) {
            if ($ENV{DBI_TRACE_DESTROY}) {
                warn "DBI::prepare FAILED on dbh=" . ($_[0]+0) . ": $@\n";
            }
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
                    $result = bless $result, $st_class;
                }
            }
        }
        return $result;
    };

    *DBI::execute = sub {
        if (!_is_jdbc_handle($_[0])) {
            my $result = _dispatch_implementor_method($_[0], 'execute', @_[1..$#_]);
            _populate_statement_metadata($_[0]) if defined $result;
            return $result;
        }
        my $result = eval { $JDBC_EXECUTE->(@_) };
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
        if (!_is_jdbc_handle($_[0])) {
            return _dispatch_implementor_method($_[0], 'finish', @_[1..$#_]);
        }
        my $sth = $_[0];
        if ($sth->{Active} && $sth->{Database}) {
            my $active = $sth->{Database}{ActiveKids} || 0;
            $sth->{Database}{ActiveKids} = $active > 0 ? $active - 1 : 0;
            $sth->{Active} = 0;
        }
        return $JDBC_FINISH->(@_);
    };

    *DBI::disconnect = sub {
        if (!_is_jdbc_handle($_[0])) {
            return _dispatch_implementor_method($_[0], 'disconnect', @_[1..$#_]);
        }
        my $dbh = $_[0];
        if ($ENV{DBI_TRACE_DESTROY}) {
            my @trace;
            for my $i (0..5) {
                my @c = caller($i);
                last unless @c;
                push @trace, "$c[0]:$c[2]";
            }
            warn "DBI::disconnect on dbh=" . ($dbh+0) . " from: " . join(" <- ", @trace) . "\n";
        }
        $dbh->{Active} = 0;
        return $JDBC_DISCONNECT->(@_);
    };

    *DBI::ping = sub {
        if (!_is_jdbc_handle($_[0])) {
            my $impl = $_[0]->{ImplementorClass};
            if ($impl && (my $code = $impl->can('ping'))) {
                return $code->(@_);
            }
            return $_[0]->{Active} ? 1 : 0;
        }
        return $JDBC_PING->(@_);
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
        if ($ENV{DBI_TRACE_DESTROY}) {
            warn "DBI::db::DESTROY calling disconnect() on dbh=" . ($dbh+0) . " Active=" . ($dbh->{Active}//0) . "\n";
        }
        eval { $dbh->disconnect() };
    }
}

# Prevent Storable::dclone from sharing JDBC Connection objects.
# In Perl 5's XS-based DBI, handles are tied hashes with C-level
# connection state that Storable can't clone. In PerlOnJava, handles
# are regular blessed hashes, so without these hooks, dclone copies
# the Java Connection reference — and when the clone is destroyed,
# it closes the shared connection, breaking the original handle.
sub DBI::db::STORABLE_freeze {
    my ($self, $cloning) = @_;
    return ('disconnected_clone', );
}

sub DBI::db::STORABLE_thaw {
    my ($self, $cloning, $serialized) = @_;
    $self->{Active} = 0;
}

sub DBI::st::STORABLE_freeze {
    my ($self, $cloning) = @_;
    return ('disconnected_clone', );
}

sub DBI::st::STORABLE_thaw {
    my ($self, $cloning, $serialized) = @_;
    $self->{Active} = 0;
}

# $dbh->tables([$catalog, $schema, $table, $type])
# Returns a list of table names from table_info(). Names are quoted with
# the database identifier quote (or '"' as a safe default) to match real
# DBI behaviour — callers like CGI::Session's t/g4_sqlite.t strip quotes
# before using the names.
sub DBI::db::tables {
    my ($dbh, $catalog, $schema, $table, $type) = @_;
    $type = 'TABLE,VIEW' unless defined $type;
    my $sth = $dbh->table_info($catalog, $schema, $table, $type) or return;
    my $q = eval { $dbh->get_info(29) };  # SQL_IDENTIFIER_QUOTE_CHAR
    $q = '"' unless defined $q && length $q;
    my @names;
    while (my $row = $sth->fetchrow_arrayref) {
        my ($cat, $sch, $name) = @$row[0,1,2];
        next unless defined $name;
        my $full = '';
        $full .= "$q$cat$q." if defined $cat  && length $cat;
        $full .= "$q$sch$q." if defined $sch  && length $sch;
        $full .= "$q$name$q";
        push @names, $full;
    }
    return @names;
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

sub _quiet_gc_before_ddl {
    my ($statement) = @_;
    return unless defined $statement && !ref($statement);
    return unless $statement =~ /^\s*(?:--[^\n]*\n\s*)*(?:CREATE|DROP|ALTER|VACUUM|REINDEX)\b/i;
    return if $DDL_SWEEPING;

    local $DDL_SWEEPING = 1;
    eval {
        require Internals;
        Internals::jperl_gc_quiet();
        1;
    };
    return 1;
}

sub _append_isa {
    my ($pkg, $base) = @_;
    no strict 'refs';
    for my $existing (@{"${pkg}::ISA"}) {
        return if $existing eq $base;
    }
    push @{"${pkg}::ISA"}, $base;
}

sub setup_driver {
    my ($class, $driver_class) = @_;
    _append_isa("${driver_class}::dr", 'DBI::dr');
    _append_isa("${driver_class}::db", 'DBI::db');
    _append_isa("${driver_class}::st", 'DBI::st');
    return 1;
}

sub driver_prefix {
    my ($class, $driver_class) = @_;
    $driver_class =~ s/^DBD:://;
    $driver_class =~ s/^DBI::DBD:://;
    return $DriverPrefix{$driver_class} if exists $DriverPrefix{$driver_class};
    my $prefix = lc $driver_class;
    $prefix =~ s/\W+/_/g;
    return "${prefix}_";
}

sub install_driver {
    my ($class, $driver) = @_;
    $driver =~ s/^DBD:://;
    return $InstalledDrivers{$driver} if $InstalledDrivers{$driver};

    my $driver_class = "DBD::$driver";
    eval "require $driver_class";
    die $@ if $@;
    _patch_sqlengine_store_aliases();
    _patch_anydata_table_destroy();
    _patch_sqlstatement_term_owner();
    die "Can't locate driver method for $driver_class\n"
        unless $driver_class->can('driver');

    my $drh = $driver_class->driver;
    die "$driver_class->driver did not return a handle\n" unless $drh;
    $InstalledDrivers{$driver} = $drh;
    return $drh;
}

sub _patch_sqlengine_store_aliases {
    return if $SqlEngineStorePatched;
    no strict 'refs';
    return unless defined &{'DBI::DBD::SqlEngine::st::STORE'};
    no warnings 'redefine';
    *{'DBI::DBD::SqlEngine::st::STORE'} = sub {
        my ($sth, $attrib, $value) = @_;
        $sth->{$attrib} = $value;
        $sth->{sql_stmt} = $value if $attrib eq 'f_stmt' && !exists $sth->{sql_stmt};
        $sth->{f_stmt} = $value if $attrib eq 'sql_stmt' && !exists $sth->{f_stmt};
        $sth->{sql_params} = $value if $attrib eq 'f_params' && !exists $sth->{sql_params};
        $sth->{f_params} = $value if $attrib eq 'sql_params' && !exists $sth->{f_params};
        return 1;
    };
    $SqlEngineStorePatched = 1;
}

sub _patch_anydata_table_destroy {
    return if $AnyDataDestroyPatched;
    no strict 'refs';
    return unless defined &{'DBD::AnyData::Statement::open_table'};
    no warnings 'redefine';
    my $orig_open_table = \&{'DBD::AnyData::Statement::open_table'};
    *{'DBD::AnyData::Statement::open_table'} = sub {
        my $table = $orig_open_table->(@_);
        my $data = $_[1];
        if (ref($data) && ref($table)) {
            push @{$data->{_dbi_anydata_live_refs}}, $table;
            push @{$data->{_dbi_anydata_live_refs}}, $table->{ad}
                if ref($table->{ad});
            push @{$data->{_dbi_anydata_live_refs}}, $table->{ad}{storage}{fh}
                if ref($table->{ad}) && ref($table->{ad}{storage}{fh});
        }
        return $table;
    };
    if (defined &{'DBD::AnyData::Table::DESTROY'}) {
        *{'DBD::AnyData::Table::DESTROY'} = sub {
            return 1;
        };
    }
    $AnyDataDestroyPatched = 1;
}

sub _patch_sqlstatement_term_owner {
    return if $SqlStatementTermPatched;
    no strict 'refs';
    return unless defined &{'SQL::Statement::Term::new'};
    no warnings 'redefine';
    my $orig_new = \&{'SQL::Statement::Term::new'};
    *{'SQL::Statement::Term::new'} = sub {
        my $self = $orig_new->(@_);
        $self->{OWNER} = $_[1] if ref($self) && ref($_[1]);
        return $self;
    };
    if (defined &{'SQL::Statement::Term::DESTROY'}) {
        *{'SQL::Statement::Term::DESTROY'} = sub {
            return 1;
        };
    }
    $SqlStatementTermPatched = 1;
}

sub installed_drivers {
    return wantarray ? %InstalledDrivers : { %InstalledDrivers };
}

sub _init_handle_attrs {
    my ($h, $attrs) = @_;
    $attrs ||= {};
    %$h = (%$h, %$attrs);
    $h->{Active} = 1 unless exists $h->{Active};
    $h->{Kids} = 0 unless exists $h->{Kids};
    $h->{ActiveKids} = 0 unless exists $h->{ActiveKids};
    $h->{CachedKids} ||= {};
    $h->{AutoCommit} = 1 unless exists $h->{AutoCommit};
    $h->{PrintError} = 0 unless exists $h->{PrintError};
    $h->{RaiseError} = 0 unless exists $h->{RaiseError};
    return $h;
}

sub _new_drh {
    my ($imp_class, $attr) = @_;
    if (defined($imp_class) && $imp_class eq __PACKAGE__) {
        ($imp_class, $attr) = @_[1, 2];
    }
    $attr ||= {};
    my $drh = {};
    _init_handle_attrs($drh, $attr);
    $drh->{Type} = 'dr';
    $drh->{ImplementorClass} = $imp_class;
    $drh = bless $drh, $imp_class;
    return wantarray ? ($drh, $drh) : $drh;
}

sub _new_dbh {
    my ($drh, $attr) = @_;
    $attr ||= {};
    my $dr_class = ref($drh) || $drh->{ImplementorClass} || '';
    my $db_class = $dr_class;
    $db_class =~ s/::dr$/::db/;
    my $dbh = {};
    _init_handle_attrs($dbh, $attr);
    $dbh->{Type} = 'db';
    $dbh->{Driver} = $drh;
    $dbh->{ImplementorClass} = $db_class;
    $dbh = bless $dbh, $db_class;
    return wantarray ? ($dbh, $dbh) : $dbh;
}

sub _new_sth {
    my ($dbh, $attr) = @_;
    $attr ||= {};
    my $db_class = $dbh->{ImplementorClass} || ref($dbh) || '';
    my $st_class = $db_class;
    $st_class =~ s/::db$/::st/;

    my $sth = {};
    _init_handle_attrs($sth, $attr);
    $sth->{Type} = 'st';
    $sth->{Active} = 0;
    $sth->{Database} = $dbh;
    Scalar::Util::weaken($sth->{Database}) if ref($sth->{Database});
    $sth->{ImplementorClass} = $st_class;
    $dbh->{Kids} = ($dbh->{Kids} || 0) + 1;

    if (my $root = $dbh->{RootClass}) {
        my $root_st = "${root}::st";
        eval "require $root" unless $root->isa('DBI');
        $sth = bless $sth, $root_st;
    } else {
        $sth = bless $sth, $st_class;
    }
    return wantarray ? ($sth, $sth) : $sth;
}

sub _dispatch_implementor_method {
    my ($handle, $method, @args) = @_;
    my $impl = ref($handle) ? $handle->{ImplementorClass} : undef;
    die "No DBI implementor class for $method\n" unless $impl;
    my $code = $impl->can($method);
    die "Can't locate object method \"$method\" via package \"$impl\"\n"
        unless $code;
    return $code->($handle, @args);
}

sub _is_jdbc_handle {
    my ($handle) = @_;
    return 0 unless ref($handle);
    return 1 if exists $handle->{connection} || exists $handle->{statement};
    my $impl = $handle->{ImplementorClass} || ref($handle);
    return $impl =~ /^DBD::(?:JDBC|SQLite)::/ || ref($handle) =~ /^DBI::(?:db|st)$/;
}

sub _apply_root_class {
    my ($dbh, $attr) = @_;
    return $dbh unless $dbh && ref($dbh) && $attr && $attr->{RootClass};
    my $root = $attr->{RootClass};
    eval "require $root" unless $root->isa('DBI');
    $dbh = bless $dbh, "${root}::db";
    $dbh->{RootClass} = $root;
    return $dbh;
}

sub _jdbc_connect {
    my ($drh, $jdbc_url, $user, $pass, $attr) = @_;
    my $dbh = $JDBC_CONNECT->('DBI', $jdbc_url, $user, $pass, $attr || {});
    if ($dbh) {
        $dbh = bless $dbh, 'DBD::JDBC::db' if ref($dbh) eq 'DBI::db';
        $dbh->{Driver} = $drh if $drh;
        $dbh->{ImplementorClass} = ref($dbh);
        $dbh->{Kids} = 0 unless exists $dbh->{Kids};
        $dbh->{ActiveKids} = 0 unless exists $dbh->{ActiveKids};
        $dbh->{Statement} = '' unless exists $dbh->{Statement};
    }
    return $dbh;
}

sub _populate_statement_metadata {
    my ($sth) = @_;
    return unless ref($sth) && (($sth->{Type} || '') eq 'st');

    my $names = $sth->{NAME};
    if (!$names || ref($names) ne 'ARRAY' || !@$names) {
        my $stmt = $sth->{sql_stmt} || $sth->{f_stmt};
        $names = $stmt->{NAME} if ref($stmt) && ref($stmt->{NAME}) eq 'ARRAY' && @{$stmt->{NAME}};
        if ((!$names || ref($names) ne 'ARRAY' || !@$names) && ref($stmt) && $stmt->can('column_names')) {
            my @cols = eval { $stmt->column_names() };
            $names = \@cols if @cols;
        }
        if ((!$names || ref($names) ne 'ARRAY' || !@$names) && $sth->{ImplementorClass}) {
            if (my $fetch = $sth->{ImplementorClass}->can('FETCH')) {
                my $got = eval { $fetch->($sth, 'NAME') };
                $names = $got if ref($got) eq 'ARRAY' && @$got;
            }
        }
    }

    if ($names && ref($names) eq 'ARRAY' && @$names) {
        $sth->{NAME} = $names;
        $sth->{NAME_lc} = [ map { lc $_ } @$names ];
        $sth->{NAME_uc} = [ map { uc $_ } @$names ];
        $sth->{NUM_OF_FIELDS} = scalar @$names unless $sth->{NUM_OF_FIELDS};
    }
    return 1;
}

sub install_method {
    return 1;
}

sub _install_method {
    return 1;
}

sub func {
    my $handle = shift;
    my $method = pop;
    my $code = $handle->can($method);
    if (!$code && ref($handle) && $handle->{ImplementorClass}) {
        $code = $handle->{ImplementorClass}->can($method);
    }
    $code or return $handle->set_err($stderr, "Can't locate DBI private method $method");
    return $code->($handle, @_);
}

sub set_err {
    my ($handle, $code, $message, $sqlstate) = @_;
    $message = '' unless defined $message;
    $sqlstate = 'S1000' unless defined $sqlstate;
    if (ref($handle)) {
        $handle->{err} = $code;
        $handle->{errstr} = $message;
        $handle->{state} = $sqlstate;
    }
    $DBI::err = $code;
    $DBI::errstr = $message;
    $DBI::state = $sqlstate;
    if (ref($handle) && $handle->{RaiseError}) {
        die "$message\n";
    }
    warn "$message\n" if ref($handle) && $handle->{PrintError};
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
    *connect = sub {
        my ($class, $dsn, $user, $pass, $attr) = @_;

        # Fall back to DBI_DSN env var if no DSN provided
        $dsn = $ENV{DBI_DSN} if !defined $dsn || !length $dsn;

        $dsn = '' unless defined $dsn;
        $user = '' unless defined $user;
        $pass = '' unless defined $pass;
        $attr = {} unless ref $attr eq 'HASH';

        # DBI subclasses such as DBIx::ContextualFetch call ->connect directly.
        # Real DBI treats that as a RootClass request.
        if (defined($class) && $class ne 'DBI' && !$attr->{RootClass}) {
            $attr->{RootClass} = $class;
        }

        if ($dsn =~ /^dbi:(\w*)(?:\(([^)]*)\))?:(.*)$/i) {
            my ($driver, $dsn_attrs, $rest) = ($1, $2, $3);

            # Fall back to DBI_DRIVER env var if driver part is empty
            $driver = $ENV{DBI_DRIVER} if !length($driver) && $ENV{DBI_DRIVER};

            # If still no driver, die with the expected Perl DBI error message
            if (!length($driver)) {
                die "I can't work out what driver to use (no driver in DSN and DBI_DRIVER env var not set)\n";
            }

            # Parse DSN-embedded attributes like (RaiseError=1,PrintError=>0)
            if (defined $dsn_attrs && length $dsn_attrs) {
                for my $pair (split /,/, $dsn_attrs) {
                    if ($pair =~ /^\s*(\w+)\s*=>?\s*(.*?)\s*$/) {
                        $attr->{$1} = $2 unless exists $attr->{$1};
                    }
                }
            }

            my $drh = $class->install_driver($driver);
            my $dbh = $drh->connect($rest, $user, $pass, $attr);
            if ($dbh) {
                $dbh->{Name} = $rest if !exists $dbh->{Name};
                $dbh->{Driver} = $drh if !exists $dbh->{Driver};
                _apply_root_class($dbh, $attr);
            }
            return $dbh;
        }

        my $dbh = $JDBC_CONNECT->($class, $dsn, $user, $pass, $attr);
        _apply_root_class($dbh, $attr);
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
    if (($self->{Type} || '') eq 'st'
            && $key eq 'Active'
            && $self->{$key}
            && !$ACTIVE_FETCH_SWEEPING) {
        local $ACTIVE_FETCH_SWEEPING = 1;
        eval {
            require Internals;
            Internals::jperl_gc_quiet();
            1;
        };
    }
    return $self->{$key};
}

sub STORE {
    my ($self, $key, $value) = @_;
    $self->{$key} = $value;
    if (($self->{Type} || '') eq 'st') {
        $self->{sql_stmt} = $value if $key eq 'f_stmt' && !exists $self->{sql_stmt};
        $self->{f_stmt} = $value if $key eq 'sql_stmt' && !exists $self->{f_stmt};
        $self->{sql_params} = $value if $key eq 'f_params' && !exists $self->{sql_params};
        $self->{f_params} = $value if $key eq 'sql_params' && !exists $self->{f_params};
    }
    return 1;
}

sub do {
    my ($dbh, $statement, $attr, @params) = @_;
    _quiet_gc_before_ddl($statement) if _is_jdbc_handle($dbh);
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    $sth->finish();  # Close JDBC statement to release locks
    ($rows == 0) ? "0E0" : $rows;
}

sub finish {
    my ($sth) = @_;
    if (_is_jdbc_handle($sth) && $JDBC_FINISH) {
        return $JDBC_FINISH->(@_);
    }
    if (!$sth->{_dbi_finish_dispatching} && $sth->{ImplementorClass}) {
        local $sth->{_dbi_finish_dispatching} = 1;
        my $impl = $sth->{ImplementorClass};
        if (my $code = $impl->can('finish')) {
            return $code->(@_) unless $code == \&finish;
        }
    }
    $sth->{Active} = 0;
    return 1;
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
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;
    $sth->execute(@params) or return undef;
    my $row = $sth->fetchrow_arrayref()
        and $sth->finish;
    return $row;
}

sub selectrow_hashref {
    my ($dbh, $statement, $attr, @params) = @_;
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;
    $sth->execute(@params) or return undef;
    my $row = $sth->fetchrow_hashref()
        and $sth->finish;
    return $row;
}

sub fetchrow_arrayref {
    my ($sth, @args) = @_;
    return $JDBC_FETCHROW_ARRAYREF->($sth, @args) if _is_jdbc_handle($sth);

    my $impl = $sth->{ImplementorClass} || ref($sth);
    if (my $code = $impl->can('fetch')) {
        return $code->($sth, @args);
    }
    if (my $code = $impl->can('fetchrow_arrayref')) {
        return $code->($sth, @args);
    }
    die "Can't locate object method \"fetch\" via package \"$impl\"\n";
}

sub fetchrow_hashref {
    my ($sth, $name) = @_;
    return $JDBC_FETCHROW_HASHREF->($sth, $name) if _is_jdbc_handle($sth);

    my $row = $sth->fetchrow_arrayref;
    return undef unless $row;
    my $fields = $sth->{$name || $sth->{FetchHashKeyName} || 'NAME'} || $sth->{NAME};
    my %hash;
    for my $i (0 .. $#$row) {
        my $key = $fields && defined $fields->[$i] ? $fields->[$i] : $i + 1;
        $hash{$key} = $row->[$i];
    }
    return \%hash;
}

sub _set_fbav {
    my ($sth, $row) = @_;
    $sth->{_fbav} = $row;
    if (my $bound = $sth->{bound_columns}) {
        for my $idx (keys %$bound) {
            my $ref = $bound->{$idx} or next;
            $$ref = $row->[$idx - 1] if ref($ref) eq 'SCALAR';
        }
    }
    return $row;
}

sub bind_col {
    my ($sth, $col, $ref) = @_;
    return undef unless $col && ref($ref) eq 'SCALAR';
    $sth->{bound_columns} ||= {};
    $sth->{bound_columns}{$col} = $ref;
    return 1;
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

    # Match Perl DBI: gate on the sth's Active flag (pending/current result set),
    # not Database->{Active} (dbh-level connection flag). JDBC-backed handles keep
    # Database->{Active} accurate but clear sth->{Active} after DML executes; using
    # Database here makes fetchall_arrayref spuriously return [] after SELECT execute().
    return undef unless $sth->{Active};

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

    return undef unless $sth->{Active};

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

{
    no warnings 'redefine';
    my $fetchrow_arrayref = sub {
        my ($sth, @args) = @_;
        return $JDBC_FETCHROW_ARRAYREF->($sth, @args) if _is_jdbc_handle($sth);

        my $impl = $sth->{ImplementorClass} || ref($sth);
        if (my $code = $impl->can('fetch')) {
            return $code->($sth, @args);
        }
        if (my $code = $impl->can('fetchrow_arrayref')) {
            return $code->($sth, @args);
        }
        die "Can't locate object method \"fetch\" via package \"$impl\"\n";
    };

    my $fetchrow_hashref = sub {
        my ($sth, $name) = @_;
        return $JDBC_FETCHROW_HASHREF->($sth, $name) if _is_jdbc_handle($sth);

        my $row = $sth->fetchrow_arrayref;
        return undef unless $row;
        my $fields = $sth->{$name || $sth->{FetchHashKeyName} || 'NAME'} || $sth->{NAME};
        my %hash;
        for my $i (0 .. $#$row) {
            my $key = $fields && defined $fields->[$i] ? $fields->[$i] : $i + 1;
            $hash{$key} = $row->[$i];
        }
        return \%hash;
    };

    my $fetchrow_array = sub {
        my $arr = fetchrow_arrayref(@_);
        return $arr ? @$arr : ();
    };

    my $fetch = sub {
        return fetchrow_arrayref(@_);
    };

    *DBI::fetchrow_arrayref = $fetchrow_arrayref;
    *DBI::fetchrow_hashref  = $fetchrow_hashref;
    *DBI::fetchrow_array    = $fetchrow_array;
    *DBI::fetch             = $fetch;
    *DBI::st::fetchrow_arrayref = \&DBI::fetchrow_arrayref;
    *DBI::st::fetchrow_array    = \&DBI::fetchrow_array;
    *DBI::st::fetchrow_hashref  = \&DBI::fetchrow_hashref;
    *DBI::st::fetch             = \&DBI::fetch;
    *DBI::st::fetchall_arrayref = \&DBI::fetchall_arrayref;
    *DBI::st::fetchall_hashref  = \&DBI::fetchall_hashref;
    *DBI::st::bind_col          = \&DBI::bind_col;
    *DBI::st::bind_columns      = \&DBI::bind_columns;
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
