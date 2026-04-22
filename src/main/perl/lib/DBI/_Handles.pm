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
use Carp ();
use Scalar::Util ();

our %installed_drh;    # driver_name => $drh (outer)

# ---- handle factories -----------------------------------------------
#
# Real DBI handles are "two-headed":
#   - an "inner" handle: the actual storage, blessed into the driver's
#     implementor class (e.g. DBD::NullP::db).
#   - an "outer" handle: a blessed reference to an anonymous hash,
#     tied (at the hash level) to a small DBI::_::Tie class. The outer
#     is what gets returned to user code.
#
# The outer is blessed into DBI::dr / DBI::db / DBI::st so
# `ref($dbh) eq 'DBI::db'` and `isa('DBI::db')` hold — matching what
# the DBI tests and DBIx::Class expect.
#
# Hash access on the outer (`$dbh->{Active}`) is intercepted by the
# tie class, which forwards FETCH / STORE to methods on the inner.
# The inner's @ISA reaches into DBD::_::common's FETCH / STORE, which
# can compute derived keys (NAME_lc, NAME_uc, NAME_hash, …) on the
# fly — matching real DBI's tied-hash behaviour.
#
# Method dispatch on the outer (`$dbh->prepare(...)`) falls through
# DBI::db's own methods first; if not found, DBI::db's AUTOLOAD looks
# up the method on the inner's class and invokes it with the inner
# as invocant. That way driver-specific methods (prepare, execute,
# f_versions, dbm_versions, …) all work transparently.
#
# Backward link: every inner has a weak reference to its outer in
# $inner->{_outer}, so helpers like `_new_dbh` (which take inner as
# $drh) can still populate new handles' `Driver` attribute with the
# user-visible outer.

sub _new_drh {
    my ($class, $initial_attr, $imp_data) = @_;
    my $inner = {
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
    $inner->{_private_data} = $imp_data if defined $imp_data;
    bless $inner, $class;

    my %outer_storage;
    my $outer = bless \%outer_storage, 'DBI::dr';
    tie %$outer, 'DBI::_::Tie', $inner;
    $inner->{_outer} = $outer;

    return wantarray ? ($outer, $inner) : $outer;
}

sub _new_dbh {
    my ($drh, $attr, $imp_data) = @_;
    # $drh may be the inner (if called from a driver's connect(),
    # routed via AUTOLOAD with inner as invocant) or the outer (if
    # called directly by user code). Normalise to inner.
    my $drh_inner = _inner_of($drh);
    my $drh_outer = $drh_inner->{_outer} || $drh;

    my $imp_class = $drh_inner->{ImplementorClass}
        or Carp::croak("DBI _new_dbh: $drh has no ImplementorClass");
    (my $db_class = $imp_class) =~ s/::dr$/::db/;

    my $inner = {
        Err       => \my $h_err,
        Errstr    => \(my $h_errstr = ''),
        State     => \my $h_state,
        TraceLevel => 0,
        %{ $attr || {} },
        ImplementorClass => $db_class,
        Driver    => $drh_outer,
        Kids      => 0,
        ActiveKids => 0,
        Active    => 0,
        Statement => '',
    };
    # If the caller passed a string Profile spec (e.g. "2/DBI::ProfileDumper/File:x"),
    # upgrade it to an object now so `$dbh->{Profile}->flush_to_disk` etc. work.
    if (defined $inner->{Profile} && !ref $inner->{Profile}) {
        $inner->{Profile} = DBD::_::common::_parse_profile_spec($inner->{Profile});
    }
    $inner->{_private_data} = $imp_data if defined $imp_data;
    bless $inner, $db_class;

    my %outer_storage;
    my $outer = bless \%outer_storage, 'DBI::db';
    tie %$outer, 'DBI::_::Tie', $inner;
    $inner->{_outer} = $outer;

    $drh_inner->{Kids}++;
    # Track child handles on the parent for visit_child_handles.
    # Weak refs so children are garbage-collected normally (but see
    # note below: weak refs in combination with tied outer handles
    # don't currently survive across scope boundaries on PerlOnJava;
    # for now we keep strong refs and let `grep { defined }` in tests
    # be a no-op. Real DBI cleans stale entries in its XS destroy path.)
    push @{ $drh_inner->{ChildHandles} ||= [] }, $outer;
    # Scalar::Util::weaken($drh_inner->{ChildHandles}[-1]);

    return wantarray ? ($outer, $inner) : $outer;
}

sub _new_sth {
    my ($dbh, $attr, $imp_data) = @_;
    my $dbh_inner = _inner_of($dbh);
    my $dbh_outer = $dbh_inner->{_outer} || $dbh;

    my $imp_class = $dbh_inner->{ImplementorClass}
        or Carp::croak("DBI _new_sth: $dbh has no ImplementorClass");
    (my $st_class = $imp_class) =~ s/::db$/::st/;

    my $inner = {
        Err       => \my $h_err,
        Errstr    => \(my $h_errstr = ''),
        State     => \my $h_state,
        TraceLevel => 0,
        NUM_OF_FIELDS => 0,
        NUM_OF_PARAMS => 0,
        %{ $attr || {} },
        ImplementorClass => $st_class,
        Database  => $dbh_outer,
        Active    => 0,
    };
    # Inherit Profile from the parent dbh if not explicitly set.
    $inner->{Profile} = $dbh_inner->{Profile}
        if !exists $inner->{Profile} && defined $dbh_inner->{Profile};
    $inner->{_private_data} = $imp_data if defined $imp_data;
    bless $inner, $st_class;

    my %outer_storage;
    my $outer = bless \%outer_storage, 'DBI::st';
    tie %$outer, 'DBI::_::Tie', $inner;
    $inner->{_outer} = $outer;

    $dbh_inner->{Kids}++;
    push @{ $dbh_inner->{ChildHandles} ||= [] }, $outer;
    # Scalar::Util::weaken($dbh_inner->{ChildHandles}[-1]);  # see _new_dbh

    return wantarray ? ($outer, $inner) : $outer;
}

# Given either an outer (tied) handle or an inner (blessed driver
# hashref), return the inner.
sub _inner_of {
    my $h = shift;
    return $h unless ref $h;
    my $tied = tied %$h;
    if (ref($tied) eq 'DBI::_::Tie') {
        return $$tied;
    }
    return $h;
}

# Given either inner or outer, return the user-facing outer. Falls back
# to the input if no outer exists (e.g. handles constructed by older
# code paths).
sub _outer_of {
    my $h = shift;
    return $h unless ref $h;
    my $tied = tied %$h;
    return $h if ref($tied) eq 'DBI::_::Tie';   # already the outer
    return $h->{_outer} || $h;                  # inner -> outer back-ref
}

# ---- DBI::_::Tie -----------------------------------------------------
#
# Minimal tie class: stores a reference to the inner handle, forwards
# hash access to FETCH / STORE methods on the inner's class.

{
    package DBI::_::Tie;
    sub TIEHASH { my ($class, $inner) = @_; bless \$inner, $class; }
    sub FETCH   { ${$_[0]}->FETCH($_[1]); }
    sub STORE   { ${$_[0]}->STORE($_[1], $_[2]); }
    sub DELETE  { delete ${${$_[0]}}{$_[1]}; }
    sub EXISTS  { exists ${${$_[0]}}{$_[1]}; }
    sub FIRSTKEY {
        my $h = ${$_[0]};
        my $a = keys %$h;    # reset iterator
        each %$h;
    }
    sub NEXTKEY { each %{${$_[0]}}; }
    sub CLEAR   { %{${$_[0]}} = (); }
    sub SCALAR  { scalar %{${$_[0]}}; }
}

# ---- outer-handle classes -------------------------------------------
#
# DBI::dr / DBI::db / DBI::st: the classes outer handles are blessed
# into. Methods are dispatched via AUTOLOAD to the inner handle's
# class, so driver-specific methods (prepare, execute, f_versions, ...)
# work transparently.

{
    # Shared base that implements the outer-side dispatch.
    package DBI::_::OuterHandle;
    our @ISA = ();

    # Ordered list of packages to try when dispatching a method on an
    # outer handle. Tied (pure-Perl DBD) handles hit the inner's class
    # first; untied handles (JDBC path) fall straight through to the
    # common base, with the DBI package checked for Java-registered
    # methods like prepare / execute / fetchrow_*.
    sub _dispatch_packages {
        my ($self) = @_;
        my $ref = ref $self;
        my ($suffix) = $ref =~ /^DBI::(dr|db|st)$/;
        $suffix ||= '';
        my $inner = DBI::_inner_of($self);
        my $inner_class = (ref($inner) && $inner != $self) ? ref($inner) : undef;
        my @packages;
        push @packages, $inner_class if defined $inner_class;
        push @packages, 'DBI' if !defined $inner_class;  # JDBC fallback
        push @packages, "DBD::_::$suffix" if $suffix;
        return @packages;
    }

    sub _dispatch_target {
        my ($self) = @_;
        my $inner = DBI::_inner_of($self);
        return $inner if ref($inner) && $inner != $self;
        return $self;
    }

    our $AUTOLOAD;
    sub AUTOLOAD {
        my $method = $AUTOLOAD;
        $method =~ s/.*:://;
        return if $method eq 'DESTROY';
        my $self = shift;
        Carp::croak("Can't call method \"$method\" on undefined handle")
            unless defined $self && ref $self;
        my @packages = _dispatch_packages($self);
        my $target   = _dispatch_target($self);
        for my $class (@packages) {
            if (my $code = $class->can($method)) {
                return $code->($target, @_);
            }
        }
        my $ref = ref $self;
        Carp::croak(
            "Can't locate DBI object method \"$method\" via package \"$ref\"");
    }

    sub can {
        my ($self, $method) = @_;
        return unless defined $self;
        my $pkg = ref($self) || $self;
        my $direct = UNIVERSAL::can($pkg, $method);
        return $direct if $direct;
        return unless ref $self;
        for my $class (_dispatch_packages($self)) {
            if (my $code = $class->can($method)) {
                return $code;
            }
        }
        return;
    }

    sub isa {
        my ($self, $class) = @_;
        my $pkg = ref($self) || $self;
        return 1 if UNIVERSAL::isa($pkg, $class);
        return 0 unless ref $self;
        for my $c (_dispatch_packages($self)) {
            return 1 if $c->isa($class);
        }
        return 0;
    }

    sub DESTROY { }
}

# All three outer-handle classes are plain DBI::_::OuterHandle subclasses.
# (They do NOT inherit from DBI: DBI has `connect` etc. registered as class
# methods, and we don't want `$drh->connect` to recurse back into DBI::connect.
# Java-registered methods like prepare / execute are reachable through the
# AUTOLOAD fallback chain in _dispatch_packages.)
{ package DBI::dr; our @ISA = ('DBI::_::OuterHandle'); }
{ package DBI::db; our @ISA = ('DBI::_::OuterHandle'); }
{ package DBI::st; our @ISA = ('DBI::_::OuterHandle'); }

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

# DBI->visit_handles(\&code [, \%info]) — walk all child handles of
# installed drivers, calling $code->($handle, $info) on each.
sub visit_handles {
    my ($class, $code, $info) = @_;
    $info = {} unless defined $info;
    for my $name (keys %installed_drh) {
        my $drh = $installed_drh{$name} or next;
        my $ci = $code->($drh, $info) or next;
        $drh->visit_child_handles($code, $ci);
    }
    return $info;
}

sub data_sources {
    my ($class, $driver, $attr) = @_;
    if (!ref($class)) {
        # allow `DBI->data_sources("dbi:DRIVER:", $attr)` form
        if (defined $driver && $driver =~ /^dbi:([^:]+):?/i) {
            $driver = $1;
        }
    }
    my $drh = ref($class) ? $class : $class->install_driver($driver);
    return $drh->data_sources($attr);
}

# DBI->internal returns the internal DBD::Switch pseudo-driver handle,
# used by the DBI self-tests to exercise DBI::dr-level attributes. We
# fake it as a simple DBD::Switch::dr handle that inherits from
# DBD::_::dr (and therefore isa('DBI::dr')).
our $_internal_drh;
sub internal {
    return $_internal_drh if $_internal_drh;
    {
        package DBD::Switch::dr;
        our @ISA = ('DBD::_::dr');
        sub DESTROY { }
    }
    $_internal_drh = bless {
        Name => 'Switch',
        Version => $DBI::VERSION,
        ImplementorClass => 'DBD::Switch::dr',
        Kids => 0,
        ActiveKids => 0,
    }, 'DBD::Switch::dr';
    return $_internal_drh;
}

# DBI->driver_prefix / dbixs_revision stubs. Real DBI uses these
# for the method-installation registry; we don't need the machinery,
# we just need the calls to succeed.
sub driver_prefix {
    my ($class, $driver) = @_;
    # Accept either 'DBM' or 'DBD::DBM'.
    $driver =~ s/^DBD:://;
    my %map = (
        DBM => 'dbm_', ExampleP => 'examplep_', File => 'f_',
        Mem => 'mem_', NullP => 'nullp_', Proxy => 'proxy_',
        Sponge => 'sponge_', SQLite => 'sqlite_', Gofer => 'go_',
    );
    return $map{$driver};
}

sub dbixs_revision { return 0 }

# DBI->parse_dsn(dsn): parse a DBI DSN into
# (scheme, driver, attr_string, attr_hash, dsn_rest).
sub parse_dsn {
    my ($class, $dsn) = @_;
    return unless defined $dsn;
    return unless $dsn =~ /^(dbi):([^:;(]+)(?:\(([^)]*)\))?(?:[:;](.*))?$/si;
    my ($scheme, $driver, $attr_str, $rest) = ($1, $2, $3, $4);
    my %attr;
    if (defined $attr_str && length $attr_str) {
        for my $pair (split /,/, $attr_str) {
            $pair =~ s/^\s+//; $pair =~ s/\s+$//;
            my ($k, $v) = split /\s*=\s*/, $pair, 2;
            $attr{$k} = $v if defined $k;
        }
    }
    return ($scheme, $driver, $attr_str, \%attr, $rest);
}

# DBI::_concat_hash_sorted(hashref, kv_sep, pair_sep, neat, sort_type).
# Serialize a hash deterministically. Used by prepare_cached cache keys
# and a handful of tests.
sub _concat_hash_sorted {
    my ($hash, $kv_sep, $pair_sep, $neat, $sort_type) = @_;
    return '' unless ref($hash) eq 'HASH';
    $kv_sep   = '=' unless defined $kv_sep;
    $pair_sep = ',' unless defined $pair_sep;
    my @parts;
    for my $k (sort keys %$hash) {
        my $v = $hash->{$k};
        if ($neat) {
            $v = DBI::neat($v);
        } else {
            $v = defined $v ? "'$v'" : 'undef';
        }
        push @parts, "'$k'${kv_sep}${v}";
    }
    return join $pair_sep, @parts;
}

# DBI::dbi_profile stubs. Real DBI implements a per-handle profiler
# (see DBI/Profile.pm). We accept the call so profile tests don't blow
# up; the real DBI::Profile module, when loaded, handles things itself.
sub dbi_profile { return; }

sub dbi_profile_merge_nodes {
    my ($dest, @sources) = @_;
    return 0 unless ref($dest) eq 'ARRAY';
    my $total = 0;
    for my $src (@sources) {
        next unless ref($src) eq 'ARRAY' && @$src >= 2;
        $dest->[0] = ($dest->[0] || 0) + ($src->[0] || 0);
        $dest->[1] = ($dest->[1] || 0) + ($src->[1] || 0);
        $total += ($src->[0] || 0);
    }
    return $total;
}

sub dbi_profile_merge { goto &dbi_profile_merge_nodes }

# DBI::dbi_time — real DBI returns Time::HiRes::time() here; we
# delegate to time() for simplicity. (Already defined in DBI/_Utils.pm
# — this copy would 'redefined' warn — so we omit it here.)

# DBI::hash(string[, type=0]): 31-bit multiplicative hash used by
# various DBI tests and some XS-based drivers. Ported from
# DBI::PurePerl.
sub hash {
    my ($key, $type) = @_;
    $type ||= 0;
    if ($type == 0) {
        my $hash = 0;
        for my $char (unpack("c*", $key)) {
            $hash = $hash * 33 + $char;
        }
        $hash &= 0x7FFFFFFF;
        $hash |= 0x40000000;
        return -$hash;
    }
    Carp::croak("DBI::hash type $type not supported in PerlOnJava");
}

# DBI->_install_method is used by drivers to register new methods
# on handle classes. Real DBI builds dispatch tables; our simplified
# version just installs the method directly so `$h->$method` works.
sub _install_method {
    my ($class, $full_name, $attr, $sub) = @_;
    # $full_name is like "DBI::db::sqlite_foo"
    no strict 'refs';
    if (ref $sub eq 'CODE') {
        *{$full_name} = $sub;
    }
    return 1;
}

# DBI->trace / DBI->trace_msg are already defined as instance
# methods by DBI.pm (on dbh/sth handles). Tests that call them as
# class methods (DBI->trace(1)) are uncommon and the existing
# impls accept that shape; don't redefine here.

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
# (wired by setup_driver above). The `DBI::dr` / `DBI::db` / `DBI::st`
# outer-handle classes are set up earlier in this file (they inherit
# from DBI::_::OuterHandle and dispatch to the inner via AUTOLOAD).

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
        # Drivers may STORE magic sentinel values on AutoCommit
        # (-900 / -901) to signal that they've handled the attribute
        # themselves. Translate them back to 0 / 1 for user code.
        if ($key eq 'AutoCommit' && defined $v && !ref $v) {
            return 0 if $v eq '-900';
            return 1 if $v eq '-901';
        }
        return $v;
    }

    sub STORE {
        my ($h, $key, $val) = @_;
        if ($key eq 'Profile' && defined $val && !ref $val) {
            # Real DBI parses "LEVEL/CLASS/ARGS" and creates a
            # DBI::Profile(Dumper) object. Minimal port: try to
            # require the requested class, call ->new, fall back to
            # DBI::Profile.
            $val = _parse_profile_spec($val);
        }
        if ($key =~ /^(?:Err|Errstr|State)$/ && ref($h->{$key}) eq 'SCALAR') {
            ${ $h->{$key} } = $val;
        } else {
            $h->{$key} = $val;
        }
        return 1;
    }

    # Very small subset of real DBI's Profile spec parser. Accepts
    # "LEVEL[/CLASS[/ARGS]]" where ARGS is "Key1:val1:Key2:val2...".
    sub _parse_profile_spec {
        my ($spec) = @_;
        return $spec unless defined $spec;
        my ($flags, $rest);
        if ($spec =~ m{^(\d+)(?:/(.*))?$}) {
            ($flags, $rest) = ($1, $2);
        } else {
            ($flags, $rest) = (0, $spec);
        }
        my ($class, @arg_parts) = split m{/}, ($rest // ''), 2;
        $class ||= 'DBI::Profile';
        my $args_str = $arg_parts[0];
        my %args;
        if (defined $args_str && length $args_str) {
            my @pairs = split /:/, $args_str;
            while (@pairs) {
                my $k = shift @pairs;
                my $v = shift @pairs;
                $args{$k} = $v if defined $k;
            }
        }
        my $ok = eval "require $class; 1";
        return $spec unless $ok;
        my $profile = eval {
            $class->new(Path => ['!Statement'], %args);
        };
        return $profile || $spec;
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

    # set_err(err, errstr [, state, method, rv]) — standard DBI error
    # setter. Tries to match real DBI's semantics, which treat the
    # three kinds of err values distinctly:
    #
    #   err truthy       — real error. HandleError is always fired;
    #                      if not suppressed, RaiseError dies and
    #                      PrintError warns.
    #   err 0 / "0"      — warning. HandleError fires only if
    #                      RaiseWarn or PrintWarn is set; if fired
    #                      and RaiseWarn, we die; if PrintWarn, we
    #                      warn. No HandleError/die/warn when no
    #                      *Warn flag is set.
    #   err ""           — info. Just stored; no alerts, no handler.
    #   err undef        — clear Err/Errstr/State; no alerts.
    #
    # The test suite probes each of these combinations, see
    # t/17handle_error.t.
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

        # Clearing case: set_err(undef, undef) — no further work.
        return $rv if !defined $err;

        # Classify the severity. Real DBI prioritises err > "0" > ""
        # by length.
        my $is_error   = $err ? 1 : 0;
        my $is_warning = !$is_error && defined $err && length($err) > 0;
        my $is_info    = !$is_error && !$is_warning;
        return $rv if $is_info;

        # Build a real-DBI-style formatted message ("impl_class method
        # failed|warning: errstr") — the test regex keys off this.
        my $impl_class = ref($h) || 'DBI';
        my $meth_name  = defined $method ? $method : 'set_err';
        my $kind       = $is_error ? 'failed' : 'warning';
        my $formatted  = "${impl_class} ${meth_name} ${kind}: "
                       . (defined $errstr ? $errstr : '');

        # Decide whether HandleError should fire.
        #   - Real errors always fire it.
        #   - Warnings only fire it when RaiseWarn or PrintWarn is set.
        my $may_handle = $is_error
                       || ($is_warning && ($h->{RaiseWarn} || $h->{PrintWarn}));

        my $suppressed = 0;
        if ($may_handle && ref($h->{HandleError}) eq 'CODE') {
            local $@;
            my $ret = eval { $h->{HandleError}->($formatted, $h, $rv) };
            die $@ if $@;
            $suppressed = 1 if $ret;
        }

        unless ($suppressed) {
            if ($is_error) {
                die  "$formatted\n" if $h->{RaiseError};
                warn "$formatted\n" if $h->{PrintError};
            } elsif ($is_warning) {
                die  "$formatted\n" if $h->{RaiseWarn};
                warn "$formatted\n" if $h->{PrintWarn};
            }
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
        if ($level >= $min_level) {
            my $fh = DBI::_trace_fh();
            print $fh $msg;
        }
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

    sub dbixs_revision { return 0 }

    sub debug {
        my ($h, $level) = @_;
        my $old = ref($h) ? ($h->{TraceLevel} || 0) : ($DBI::dbi_debug || 0);
        $h->trace($level) if defined $level;
        return $old;
    }

    # FETCH_many: fetch multiple attributes in one call, used by
    # DBI profile code and DBIx::Class.
    sub FETCH_many {
        my $h = shift;
        return map { scalar $h->FETCH($_) } @_;
    }

    # can() override so installed methods on the implementor class
    # are findable. Handles inherit through @ISA already; this stub
    # mostly exists for symmetry with real DBI.
    sub install_method {
        my ($class, $method, $attr) = @_;
        Carp::croak("Class '$class' must begin with DBD:: and end with ::db or ::st")
            unless $class =~ /^DBD::(\w+)::(dr|db|st)$/;
        # No-op: drivers define methods directly on their ::<suffix>
        # packages and MRO picks them up.
        return 1;
    }

    sub dump_handle {
        my ($h, $msg, $level) = @_;
        $msg = '' unless defined $msg;
        my $class = ref($h) || $h;
        my $fh = DBI::_trace_fh();
        print $fh "$msg $class=HASH\n";
        if (ref $h) {
            for my $k (sort keys %$h) {
                my $v = $h->{$k};
                next if ref $v;
                print $fh "  $k = ", (defined $v ? $v : 'undef'), "\n";
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
    # Intentionally does not inherit from DBI::dr: DBI::dr is the
    # OUTER-handle class with an AUTOLOAD that forwards to the inner.
    # If the inner's ISA reached DBI::dr, AUTOLOAD would loop.
    our @ISA = ('DBD::_::common');
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
    our @ISA = ('DBD::_::common');
    use strict;

    sub ping { return 0 }    # DBDs should override
    sub data_sources {
        my ($dbh, $attr) = @_;
        my $drh = $dbh->{Driver} or return ();
        return $drh->data_sources($attr);
    }

    sub do {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        my $rows = $sth->rows;
        return ($rows == 0) ? "0E0" : $rows;
    }

    sub prepare_cached {
        my ($dbh, $statement, $attr, $if_active) = @_;
        $if_active ||= 0;
        my $cache = $dbh->{CachedKids} ||= {};
        my $key = join "\001", $statement,
            (defined $attr && ref($attr) eq 'HASH')
                ? map { defined $_ ? $_ : '' } %$attr
                : '';
        my $sth = $cache->{$key};
        if ($sth && $sth->FETCH('Active')) {
            if ($if_active == 0) {
                Carp::carp("prepare_cached($statement) statement handle $sth still Active");
            } elsif ($if_active == 1) {
                $sth->finish;
            } elsif ($if_active == 2) {
                # fall through, reuse
            } elsif ($if_active == 3) {
                delete $cache->{$key};
                $sth = $dbh->prepare($statement, $attr);
                $cache->{$key} = $sth;
            }
        } elsif (!$sth) {
            $sth = $dbh->prepare($statement, $attr) or return undef;
            $cache->{$key} = $sth;
        }
        return $sth;
    }

    sub selectrow_array {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return;
        $sth->execute(@bind) or return;
        my $row = $sth->fetchrow_arrayref;
        $sth->finish;
        return $row ? (wantarray ? @$row : $row->[0]) : ();
    }

    sub selectrow_arrayref {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        my $row = $sth->fetchrow_arrayref;
        $sth->finish;
        return $row ? [@$row] : undef;
    }

    sub selectall_arrayref {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        my @rows;
        while (my $row = $sth->fetchrow_arrayref) {
            push @rows, [@$row];
        }
        return \@rows;
    }

    sub selectcol_arrayref {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        my @col;
        while (my $row = $sth->fetchrow_arrayref) {
            push @col, $row->[0];
        }
        return \@col;
    }

    sub selectrow_hashref {
        my ($dbh, $statement, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        my $row = $sth->fetchrow_hashref;
        $sth->finish;
        return $row;
    }

    sub selectall_hashref {
        my ($dbh, $statement, $key_field, $attr, @bind) = @_;
        my $sth = (ref $statement) ? $statement : $dbh->prepare($statement, $attr) or return undef;
        $sth->execute(@bind) or return undef;
        return $sth->fetchall_hashref($key_field);
    }

    sub disconnect {
        my $dbh = shift;
        $dbh->STORE(Active => 0);
        return 1;
    }
    sub commit {
        my $dbh = shift;
        if ($dbh->{BegunWork}) {
            $dbh->STORE(AutoCommit => 1);
            $dbh->{BegunWork} = 0;
        }
        return 1;
    }
    sub rollback {
        my $dbh = shift;
        if ($dbh->{BegunWork}) {
            $dbh->STORE(AutoCommit => 1);
            $dbh->{BegunWork} = 0;
        }
        return 1;
    }

    sub begin_work {
        my $dbh = shift;
        if (!$dbh->FETCH('AutoCommit')) {
            Carp::carp("Already in a transaction");
            return 0;
        }
        $dbh->STORE(AutoCommit => 0);
        $dbh->{BegunWork} = 1;
        return 1;
    }

    sub clone {
        my ($dbh, $attr) = @_;
        my $drh = $dbh->{Driver} or return;
        my $new = $drh->connect(
            $dbh->{Name} // '',
            $dbh->{Username} // '',
            '',
            $attr || {},
        );
        return $new;
    }
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
    sub type_info     { return () }
    sub get_info      { return undef }
    sub last_insert_id { return undef }
    sub take_imp_data { return undef }
}

{
    package DBD::_::st;
    our @ISA = ('DBD::_::common');
    use strict;

    sub rows      { return -1 }
    sub finish {
        my $sth = shift;
        $sth->STORE(Active => 0);
        return 1;
    }

    # Computed NAME_lc / NAME_uc / NAME_hash / NAME_lc_hash /
    # NAME_uc_hash attributes derived from NAME.
    sub FETCH {
        my ($sth, $key) = @_;
        return undef unless ref $sth;
        if ($key eq 'NAME_lc') {
            return undef unless $sth->{NAME};
            return [ map { lc } @{ $sth->{NAME} } ];
        }
        if ($key eq 'NAME_uc') {
            return undef unless $sth->{NAME};
            return [ map { uc } @{ $sth->{NAME} } ];
        }
        if ($key eq 'NAME_hash') {
            return undef unless $sth->{NAME};
            my %h; @h{ @{ $sth->{NAME} } } = (0 .. $#{ $sth->{NAME} });
            return \%h;
        }
        if ($key eq 'NAME_lc_hash') {
            return undef unless $sth->{NAME};
            my %h; @h{ map { lc } @{ $sth->{NAME} } } = (0 .. $#{ $sth->{NAME} });
            return \%h;
        }
        if ($key eq 'NAME_uc_hash') {
            return undef unless $sth->{NAME};
            my %h; @h{ map { uc } @{ $sth->{NAME} } } = (0 .. $#{ $sth->{NAME} });
            return \%h;
        }
        return $sth->SUPER::FETCH($key);   # DBD::_::common::FETCH
    }

    sub bind_col      { return 1 }
    sub bind_columns  { return 1 }
    sub bind_param    { return 1 }
    sub bind_param_array  { return 1 }
    sub execute_array { return 0 }

    sub fetchall_arrayref {
        my ($sth, $slice, $maxrows) = @_;
        my @rows;
        my $count = 0;
        if (!defined $slice || (ref $slice eq 'ARRAY' && !@$slice)) {
            # plain: each row as arrayref
            while (my $row = $sth->fetchrow_arrayref) {
                push @rows, [@$row];
                last if defined $maxrows && ++$count >= $maxrows;
            }
        } elsif (ref $slice eq 'ARRAY') {
            while (my $row = $sth->fetchrow_arrayref) {
                push @rows, [ @{$row}[ @$slice ] ];
                last if defined $maxrows && ++$count >= $maxrows;
            }
        } elsif (ref $slice eq 'HASH') {
            my $names = $sth->{ $sth->{FetchHashKeyName} || 'NAME' };
            my @keys = keys %$slice;
            @keys = @$names if !@keys && $names;
            while (my $row = $sth->fetchrow_arrayref) {
                my %h;
                for my $i (0 .. $#$names) {
                    $h{ $names->[$i] } = $row->[$i];
                }
                push @rows, \%h;
                last if defined $maxrows && ++$count >= $maxrows;
            }
        }
        return \@rows;
    }

    sub fetchall_hashref {
        my ($sth, $key_field) = @_;
        my %result;
        my $names = $sth->{ $sth->{FetchHashKeyName} || 'NAME' };
        return {} unless $names;
        # map field name -> column index
        my %idx;
        for my $i (0 .. $#$names) { $idx{ $names->[$i] } = $i; }
        my @key_fields = ref($key_field) eq 'ARRAY' ? @$key_field : ($key_field);
        while (my $row = $sth->fetchrow_arrayref) {
            my %h;
            for my $i (0 .. $#$names) { $h{ $names->[$i] } = $row->[$i]; }
            my $target = \%result;
            for my $i (0 .. $#key_fields - 1) {
                my $k = $h{ $key_fields[$i] };
                $target = $target->{$k} ||= {};
            }
            $target->{ $h{ $key_fields[-1] } } = \%h;
        }
        return \%result;
    }

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

    # `fetch` is the canonical method real DBI documents for pulling
    # a row from a statement handle; many drivers alias it to
    # fetchrow_arrayref. Provide a default delegate so outer
    # `$sth->fetch` works even when the driver didn't install one.
    sub fetch {
        my $sth = shift;
        my $code = ref($sth)->can('fetchrow_arrayref')
            or return;
        return $code->($sth);
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

    # _get_fbav: returns the pre-allocated row buffer for bind_col-style
    # fetch. Used by DBD::Sponge and a few others. We simply allocate a
    # fresh array of the expected width.
    sub _get_fbav {
        my ($sth) = @_;
        my $num = $sth->FETCH('NUM_OF_FIELDS') || 0;
        return [ (undef) x $num ];
    }
}

1;
