# Internal helper module for DBI. Provides the driver-architecture
# pieces that pure-Perl DBDs (DBD::NullP, DBD::ExampleP, DBD::Sponge,
# DBD::File, DBD::Mem, DBD::DBM, DBD::Proxy, ...) expect to see:
#
#   * DBI->install_driver / installed_drivers / setup_driver
#   * DBI::_new_drh, DBI::_new_dbh, DBI::_new_sth  (handle factories)
#   * DBD::_::common / DBD::_::dr / DBD::_::db / DBD::_::st base
#     classes with FETCH / STORE / set_err / err / errstr / state /
#     trace / trace_msg / func / DESTROY / finish / default connect.
#
# Lives in its own file so PerlOnJava compiles it to a separate JVM
# class (see note in DBI.pm).
#
# NOTE: this is a *minimal* reimplementation aimed at making the
# bundled DBI test suite load and exercise pure-Perl drivers. It is
# intentionally simpler than real DBI.pm. Notable differences:
#
#   - Handles are plain blessed hashrefs, not tied hashes. `FETCH`
#     / `STORE` / `can` / `isa` all work, and DBD drivers that use
#     `$h->STORE(key => val)` / `$h->{key}` interchangeably work,
#     but `each %$h` and tie-aware introspection do not.
#   - `_new_drh` / `_new_dbh` / `_new_sth` return the same object
#     for the outer and inner handle. Real DBI distinguishes them
#     via a tie; we don't.
#   - Trace flag parsing is a stub (enough to satisfy tests that
#     probe it, not a full implementation).

package DBI;

use strict;
use warnings;

our %installed_drh;    # driver_name => $drh

# ---- handle factories -----------------------------------------------

sub _new_drh {
    # called by DBD::<name>::driver() with the fully-qualified ::dr
    # package name as $class, plus initial attrs and private data.
    my ($class, $initial_attr, $imp_data) = @_;
    my $drh = {
        # defaults real DBI copies down to children
        State       => \my $h_state,
        Err         => \my $h_err,
        Errstr      => \(my $h_errstr = ''),
        TraceLevel  => 0,
        FetchHashKeyName => 'NAME',
        %{ $initial_attr || {} },
        ImplementorClass => $class,
        Kids        => 0,
        ActiveKids  => 0,
        Active      => 1,
    };
    $drh->{_private_data} = $imp_data if defined $imp_data;
    bless $drh, $class;
    return wantarray ? ($drh, $drh) : $drh;
}

sub _new_dbh {
    my ($drh, $attr, $imp_data) = @_;
    my $imp_class = $drh->{ImplementorClass}
        or Carp::croak("DBI _new_dbh: $drh has no ImplementorClass");
    # driver::dr -> driver::db
    (my $db_class = $imp_class) =~ s/::dr$/::db/;
    my $dbh = {
        Err       => \my $h_err,
        Errstr    => \(my $h_errstr = ''),
        State     => \my $h_state,
        TraceLevel => 0,
        %{ $attr || {} },
        ImplementorClass => $db_class,
        Driver    => $drh,
        Kids      => 0,
        ActiveKids => 0,
        Active    => 0,   # driver's connect() is expected to set Active=1
        Statement => '',
    };
    $dbh->{_private_data} = $imp_data if defined $imp_data;
    bless $dbh, $db_class;
    $drh->{Kids}++;
    return wantarray ? ($dbh, $dbh) : $dbh;
}

sub _new_sth {
    my ($dbh, $attr, $imp_data) = @_;
    my $imp_class = $dbh->{ImplementorClass}
        or Carp::croak("DBI _new_sth: $dbh has no ImplementorClass");
    (my $st_class = $imp_class) =~ s/::db$/::st/;
    my $sth = {
        Err       => \my $h_err,
        Errstr    => \(my $h_errstr = ''),
        State     => \my $h_state,
        TraceLevel => 0,
        NUM_OF_FIELDS => 0,
        NUM_OF_PARAMS => 0,
        %{ $attr || {} },
        ImplementorClass => $st_class,
        Database  => $dbh,
        Active    => 0,
    };
    $sth->{_private_data} = $imp_data if defined $imp_data;
    bless $sth, $st_class;
    $dbh->{Kids}++;
    return wantarray ? ($sth, $sth) : $sth;
}

# ---- driver installation --------------------------------------------

sub install_driver {
    my ($class, $driver, $attr) = @_;
    Carp::croak("usage: $class->install_driver(\$driver [, \\%attr])")
        unless defined $driver && length $driver;
    return $installed_drh{$driver} if $installed_drh{$driver};

    my $dbd_class = "DBD::$driver";
    my $ok = eval "require $dbd_class; 1";
    unless ($ok) {
        my $err = $@ || 'unknown error';
        Carp::croak("install_driver($driver) failed: $err");
    }

    # wire up @ISA for DBD::$driver::{dr,db,st} so SUPER:: works
    $class->setup_driver($dbd_class);

    my $drh = $dbd_class->driver($attr || {});
    Carp::croak("$dbd_class->driver() did not return a driver handle")
        unless ref $drh;
    $installed_drh{$driver} = $drh;
    return $drh;
}

sub setup_driver {
    my ($class, $driver_class) = @_;
    no strict 'refs';
    for my $suffix (qw(dr db st)) {
        my $h_class = "${driver_class}::${suffix}";
        my $base    = "DBD::_::${suffix}";
        push @{"${h_class}::ISA"}, $base
            unless UNIVERSAL::isa($h_class, $base);
    }
}

sub installed_drivers { %installed_drh }

sub data_sources {
    my ($class, $driver, $attr) = @_;
    my $drh = ref($class) ? $class : $class->install_driver($driver);
    return $drh->data_sources($attr);
}

sub available_drivers {
    my ($class, $quiet) = @_;
    # Best-effort: scan @INC for DBD::* modules. Tests usually only
    # care that this returns a list, not an exact one.
    my %seen;
    for my $dir (@INC) {
        next unless ref($dir) eq '' && -d "$dir/DBD";
        if (opendir my $dh, "$dir/DBD") {
            while (my $e = readdir $dh) {
                next unless $e =~ /^(\w+)\.pm$/;
                $seen{$1} ||= 1;
            }
            closedir $dh;
        }
    }
    return sort keys %seen;
}

# ---- base classes ----------------------------------------------------
#
# Real DBI exposes these as `DBD::_::common` + DBD::_::{dr,db,st},
# where each DBD::<name>::<suffix> inherits from DBD::_::<suffix>
# (wired by setup_driver above). Real DBI additionally makes handles
# pass `isa('DBI::dr')` / `isa('DBI::db')` / `isa('DBI::st')` —
# DBIx::Class and the DBI self-tests rely on this. We achieve that
# by having DBD::_::<suffix> inherit from DBI::<suffix>.

{
    package DBI::dr; our @ISA = ();
    package DBI::db; our @ISA = ();
    package DBI::st; our @ISA = ();
}

sub _get_imp_data {
    my $h = shift;
    return ref($h) ? $h->{_private_data} : undef;
}

{
    package DBD::_::common;
    our @ISA = ();
    use strict;

    sub FETCH {
        my ($h, $key) = @_;
        return undef unless ref $h;
        my $v = $h->{$key};
        # Err / Errstr / State are stored as scalarref holders so they
        # can be shared with child handles. Dereference on FETCH.
        return $$v if ref($v) eq 'SCALAR' && $key =~ /^(?:Err|Errstr|State)$/;
        return $v;
    }

    sub STORE {
        my ($h, $key, $val) = @_;
        if ($key =~ /^(?:Err|Errstr|State)$/ && ref($h->{$key}) eq 'SCALAR') {
            ${ $h->{$key} } = $val;
        } else {
            $h->{$key} = $val;
        }
        return 1;
    }

    sub EXISTS   { defined($_[0]->FETCH($_[1])) }
    sub FIRSTKEY { }
    sub NEXTKEY  { }
    sub CLEAR    { Carp::carp "Can't CLEAR $_[0] (DBI)" }

    sub err {
        my $h = shift;
        my $v = $h->{Err};
        return ref($v) eq 'SCALAR' ? $$v : $v;
    }
    sub errstr {
        my $h = shift;
        my $v = $h->{Errstr};
        return ref($v) eq 'SCALAR' ? $$v : $v;
    }
    sub state {
        my $h = shift;
        my $v = $h->{State};
        my $s = ref($v) eq 'SCALAR' ? $$v : $v;
        return defined $s ? $s : '';
    }

    sub set_err {
        my ($h, $err, $errstr, $state, $method, $rv) = @_;
        $errstr = $err unless defined $errstr;
        $h->STORE(Err    => $err);
        $h->STORE(Errstr => $errstr);
        $h->STORE(State  => $state) if defined $state;
        # also update $DBI::err / $DBI::errstr / $DBI::state
        $DBI::err    = $err;
        $DBI::errstr = $errstr;
        $DBI::state  = defined $state ? $state : '';
        if ($h->{PrintError}) {
            warn "DBI: $errstr\n";
        }
        if ($h->{RaiseError}) {
            die "$errstr\n";
        }
        return $rv;   # usually undef
    }

    sub trace {
        my ($h, $level, $file) = @_;
        my $old = ref($h) ? ($h->{TraceLevel} || 0) : 0;
        if (defined $level) {
            if (ref $h) {
                $h->{TraceLevel} = $level;
            } else {
                $DBI::dbi_debug = $level;
            }
        }
        return $old;
    }

    sub trace_msg {
        my ($h, $msg, $min_level) = @_;
        $min_level ||= 1;
        my $level = ref($h) ? ($h->{TraceLevel} || 0) : ($DBI::dbi_debug || 0);
        print STDERR $msg if $level >= $min_level;
        return 1;
    }

    sub parse_trace_flag {
        my ($h, $name) = @_;
        return 0x00000100 if $name eq 'SQL';
        return 0x00000200 if $name eq 'CON';
        return 0x00000400 if $name eq 'ENC';
        return 0x00000800 if $name eq 'DBD';
        return 0x00001000 if $name eq 'TXN';
        return;
    }

    sub parse_trace_flags {
        my ($h, $spec) = @_;
        my ($level, $flags) = (0, 0);
        for my $word (split /\s*[|&,]\s*/, $spec // '') {
            if ($word =~ /^\d+$/ && $word >= 0 && $word <= 0xF) {
                $level = $word;
            } elsif ($word eq 'ALL') {
                $flags = 0x7FFFFFFF;
                last;
            } elsif (my $flag = $h->parse_trace_flag($word)) {
                $flags |= $flag;
            }
        }
        return $flags | $level;
    }

    sub func {
        my ($h, @args) = @_;
        my $method = pop @args;
        my $target = ref($h) ? $h : $h;
        my $impl   = ref($h) ? $h->{ImplementorClass} : undef;
        if ($impl && (my $sub = $impl->can($method))) {
            return $sub->($h, @args);
        }
        Carp::croak("Can't locate DBI object method \"$method\"");
    }

    sub private_attribute_info { undef }

    sub dump_handle {
        my ($h, $msg, $level) = @_;
        $msg = '' unless defined $msg;
        my $class = ref($h) || $h;
        print STDERR "$msg $class=HASH\n";
        if (ref $h) {
            for my $k (sort keys %$h) {
                my $v = $h->{$k};
                next if ref $v;
                print STDERR "  $k = ", (defined $v ? $v : 'undef'), "\n";
            }
        }
        return 1;
    }

    sub swap_inner_handle { return 1 }
    sub visit_child_handles {
        my ($h, $code, $info) = @_;
        $info = {} unless defined $info;
        for my $ch (@{ $h->{ChildHandles} || [] }) {
            next unless $ch;
            my $child_info = $code->($ch, $info) or next;
            $ch->visit_child_handles($code, $child_info);
        }
        return $info;
    }

    sub DESTROY {
        my $h = shift;
        # decrement parent's Kids on destruction.
        if (ref $h eq 'HASH' || ref $h) {
            my $parent = $h->{Database} || $h->{Driver};
            if ($parent && ref $parent && exists $parent->{Kids}) {
                $parent->{Kids}-- if $parent->{Kids} > 0;
            }
        }
    }
}

{
    package DBD::_::dr;
    our @ISA = ('DBI::dr', 'DBD::_::common');
    use strict;

    sub default_user {
        my ($drh, $user, $pass) = @_;
        $user = $ENV{DBI_USER} unless defined $user;
        $pass = $ENV{DBI_PASS} unless defined $pass;
        return ($user, $pass);
    }

    sub connect {
        # default connect: create a db handle. DBDs typically override.
        my ($drh, $dsn, $user, $auth, $attr) = @_;
        my $dbh = DBI::_new_dbh($drh, { Name => $dsn });
        return $dbh;
    }

    sub connect_cached {
        my ($drh, $dsn, $user, $auth, $attr) = @_;
        my $cache = $drh->{CachedKids} ||= {};
        my $key = join "!\001",
            defined $dsn  ? $dsn  : '',
            defined $user ? $user : '',
            defined $auth ? $auth : '';
        my $dbh = $cache->{$key};
        if ($dbh && $dbh->FETCH('Active')) {
            return $dbh;
        }
        $dbh = $drh->connect($dsn, $user, $auth, $attr);
        $cache->{$key} = $dbh;
        return $dbh;
    }

    sub data_sources { return () }
    sub disconnect_all { return; }
}

{
    package DBD::_::db;
    our @ISA = ('DBI::db', 'DBD::_::common');
    use strict;

    sub ping { return 0 }    # DBDs should override
    sub data_sources {
        my ($dbh, $attr) = @_;
        my $drh = $dbh->{Driver} or return ();
        return $drh->data_sources($attr);
    }
    sub disconnect {
        my $dbh = shift;
        $dbh->STORE(Active => 0);
        return 1;
    }
    sub commit   { return 1 }
    sub rollback { return 1 }
    sub quote {
        my ($dbh, $str, $type) = @_;
        return 'NULL' unless defined $str;
        $str =~ s/'/''/g;
        return "'$str'";
    }
    sub quote_identifier {
        my ($dbh, @ids) = @_;
        my $q = '"';
        return join('.', map { defined $_ ? qq{$q$_$q} : '' } @ids);
    }
    sub table_info    { return undef }
    sub column_info   { return undef }
    sub primary_key_info { return undef }
    sub foreign_key_info { return undef }
    sub type_info_all { return [] }
    sub get_info      { return undef }
    sub last_insert_id { return undef }
    sub take_imp_data { return undef }
}

{
    package DBD::_::st;
    our @ISA = ('DBI::st', 'DBD::_::common');
    use strict;

    sub rows      { return -1 }
    sub finish {
        my $sth = shift;
        $sth->STORE(Active => 0);
        return 1;
    }
    sub bind_col      { return 1 }
    sub bind_columns  { return 1 }
    sub bind_param    { return 1 }
    sub bind_param_array  { return 1 }
    sub execute_array { return 0 }
    sub fetchrow_array {
        my $sth = shift;
        my $ref = $sth->fetchrow_arrayref;
        return $ref ? @$ref : ();
    }
    sub fetchrow_hashref {
        my ($sth, $name_attr) = @_;
        my $row = $sth->fetchrow_arrayref or return undef;
        my $names = $sth->{ $name_attr || $sth->{FetchHashKeyName} || 'NAME' };
        my %h;
        @h{ @$names } = @$row;
        return \%h;
    }

    # Helper used by pure-Perl DBDs (see DBD::NullP::st::fetchrow_arrayref).
    # Real DBI binds fetched column values into the variables that were
    # passed to bind_col / bind_columns. Our simplified impl just returns
    # the array reference unchanged.
    sub _set_fbav {
        my ($sth, $data) = @_;
        if (my $bound = $sth->{_bound_cols}) {
            for my $i (0 .. $#$bound) {
                ${ $bound->[$i] } = $data->[$i] if ref $bound->[$i];
            }
        }
        return $data;
    }
}

1;
