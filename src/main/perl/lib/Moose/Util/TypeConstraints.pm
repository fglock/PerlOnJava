package Moose::Util::TypeConstraints;

# PerlOnJava Moose::Util::TypeConstraints stub.
#
# Provides the most common subroutines exported by the upstream module so
# code that imports them at compile time doesn't fail. Type registration is
# tracked in a flat hash; declared types are accepted but not deeply enforced
# (the Moose shim's `has isa => 'TypeName'` uses Moose.pm's own translator,
# which falls back to a class-name isa check for unknown names).
#
# This is enough for many CPAN modules that just do:
#
#   use Moose::Util::TypeConstraints;
#   subtype 'PositiveInt', as 'Int', where { $_ > 0 };
#   enum 'Direction', [qw(north south east west)];
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Carp ();
use Scalar::Util ();
use Exporter 'import';

our @EXPORT = qw(
    type subtype as where message optimize_as
    coerce from via
    enum union
    class_type role_type duck_type
    find_type_constraint register_type_constraint
    create_type_constraint_union
    find_or_parse_type_constraint
    list_all_type_constraints list_all_builtin_type_constraints
);
our @EXPORT_OK = @EXPORT;

# Registry of declared types. Values are hashrefs:
#   { name => $name, parent => $parent, constraint => $coderef, message => $coderef }
my %TYPES;

# Pre-populated standard type constraints (filled in below). Forward
# declared here so subs that reference it (find_type_constraint, the _Stub
# class) compile without warnings.
my %STANDARD_TYPES;

sub _store {
    my $def = shift;
    # Bless into _Stub so callers can use ->name / ->check / ->isa.
    # The _Stub class is defined further down in this file; we forward-
    # require it here just to avoid a load-order dance.
    my $obj = bless { %$def }, 'Moose::Util::TypeConstraints::_Stub';
    $TYPES{ $obj->{name} } = $obj if defined $obj->{name};
    return $obj;
}

# subtype 'Name', as 'Parent', where { ... }, message { ... };
sub subtype {
    my $name = shift;

    # Anonymous subtype: subtype as 'Parent', where { ... }
    if (ref $name eq 'HASH' || @_ == 0) {
        return { %{ $name || {} }, name => undef, anonymous => 1 };
    }

    my %opts;
    while (@_) {
        my $key = shift;
        if (ref $key eq 'HASH') {
            %opts = (%opts, %$key);
        }
        else {
            $opts{$key} = shift;
        }
    }
    return _store({ name => $name, %opts });
}

sub type {
    my $name = shift;
    my %opts = @_ == 1 && ref $_[0] eq 'HASH' ? %{ $_[0] } : @_;
    return _store({ name => $name, %opts });
}

# These are the "DSL" keywords. They return key/value pairs that subtype()
# stitches together.
sub as          { (parent     => $_[0]) }
sub where (&)   { (constraint => $_[0]) }
sub message (&) { (message    => $_[0]) }
sub optimize_as (&) { (optimized => $_[0]) }
sub from        { (coerce_from => $_[0]) }
sub via (&)     { (coerce_via  => $_[0]) }

sub coerce {
    my $name = shift;
    my %opts = @_;
    my $type = $TYPES{$name} or do {
        Carp::carp("Cannot apply coerce to unknown type '$name'");
        return;
    };
    push @{ $type->{coercions} ||= [] }, \%opts;
    return $type;
}

sub enum {
    my $name = shift;
    my $values = ref $_[0] eq 'ARRAY' ? $_[0] : [@_];
    my %ok = map { $_ => 1 } @$values;
    return _store({
        name       => $name,
        parent     => 'Str',
        constraint => sub { defined $_[0] && exists $ok{$_[0]} },
        values     => $values,
    });
}

sub union {
    my ($name, $members) = @_;
    return _store({
        name    => $name,
        parent  => 'Any',
        members => $members,
    });
}

sub class_type {
    my ($name, $opts) = @_;
    my $class = $opts && $opts->{class} ? $opts->{class} : $name;
    return _store({
        name       => $name,
        parent     => 'Object',
        class      => $class,
        constraint => sub {
            Scalar::Util::blessed($_[0]) && $_[0]->isa($class);
        },
    });
}

sub role_type {
    my ($name, $opts) = @_;
    my $role = $opts && $opts->{role} ? $opts->{role} : $name;
    return _store({
        name       => $name,
        parent     => 'Object',
        role       => $role,
        constraint => sub {
            Scalar::Util::blessed($_[0]) && $_[0]->can('does') && $_[0]->does($role);
        },
    });
}

sub duck_type {
    my $name = shift;
    my $methods = ref $_[0] eq 'ARRAY' ? $_[0] : [@_];
    return _store({
        name       => $name,
        parent     => 'Object',
        methods    => $methods,
        constraint => sub {
            my $val = $_[0];
            return 0 unless Scalar::Util::blessed($val);
            for my $m (@$methods) {
                return 0 unless $val->can($m);
            }
            1;
        },
    });
}

sub find_type_constraint           {
    my ($name) = @_;
    return undef unless defined $name;
    return $TYPES{$name} if $TYPES{$name};
    return $STANDARD_TYPES{$name};
}
sub register_type_constraint       { _store({ %{ $_[0] } }) }
sub create_type_constraint_union   { union(@_) }

# ---------------------------------------------------------------------------
# Standard-type registry. Pre-populated so that
# `find_type_constraint('Int')` etc. return a stub object instead of
# undef. Without this, Moose's own t/type_constraints/util_std_*.t test
# bails out (`BAIL_OUT("No such type ...")`) — which kills prove and
# loses every test file that follows alphabetically.
#
# The stubs are intentionally minimal: enough method surface that the
# upstream tests can call ->name / ->has_parent / ->constraint /
# ->_compile_type / ->can_be_inlined and not die. The tests will then
# fail subtests in the usual way, but won't BAIL_OUT.
# ---------------------------------------------------------------------------

{
    package Moose::Util::TypeConstraints::_Stub;
    require Moose::Meta::TypeConstraint;
    our @ISA = ('Moose::Meta::TypeConstraint');
    sub new {
        my ($class, %opts) = @_;
        $opts{constraint} ||= sub { 1 };
        return bless { %opts }, $class;
    }
    sub name              { $_[0]->{name} }
    sub parent            { $_[0]->{parent} }
    sub has_parent        { defined $_[0]->{parent} ? 1 : 0 }
    sub constraint        { $_[0]->{constraint} }
    sub message           { $_[0]->{message} }
    sub has_message       { defined $_[0]->{message} ? 1 : 0 }
    sub coercion          { undef }
    sub has_coercion      { 0 }
    sub can_be_inlined    { 0 }
    sub inline_environment { {} }
    sub _inline_check     { 'do { 1 }' }
    sub _compile_type     { $_[0]->{constraint} }
    sub _compile_subtype  { $_[0]->{constraint} }
    sub check {
        my ($self, $value) = @_;
        my $c = $self->{constraint};
        return $c ? $c->($value) : 1;
    }
    sub assert_valid {
        my ($self, $value) = @_;
        return 1 if $self->check($value);
        require Carp;
        Carp::croak("Validation failed for '" . $self->name . "'");
    }
    sub validate {
        my ($self, $value) = @_;
        return undef if $self->check($value);
        return "Validation failed for '" . ($self->name // 'Anon') . "'";
    }
    sub is_subtype_of {
        my ($self, $name) = @_;
        my $p = $self->{parent};
        while (defined $p) {
            return 1 if $p eq $name;
            my $pp = $STANDARD_TYPES{$p};
            $p = $pp ? $pp->{parent} : undef;
        }
        return 0;
    }
    sub equals {
        my ($self, $other) = @_;
        my $a = ref $self  ? $self->name  : $self;
        my $b = ref $other ? $other->name : $other;
        return defined $a && defined $b && $a eq $b;
    }
    sub is_a_type_of {
        my ($self, $name) = @_;
        return 1 if $self->equals($name);
        return $self->is_subtype_of($name);
    }
}

sub _stub_type {
    my (%opts) = @_;
    return Moose::Util::TypeConstraints::_Stub->new(%opts);
}

%STANDARD_TYPES = (
    Any        => _stub_type(name => 'Any',     constraint => sub { 1 }),
    Item       => _stub_type(name => 'Item',    parent => 'Any',  constraint => sub { 1 }),
    Defined    => _stub_type(name => 'Defined', parent => 'Item', constraint => sub { defined $_[0] }),
    Undef      => _stub_type(name => 'Undef',   parent => 'Item', constraint => sub { !defined $_[0] }),
    Bool       => _stub_type(name => 'Bool',    parent => 'Item', constraint => sub {
                      !defined $_[0] || $_[0] eq '' || $_[0] eq '0' || $_[0] eq '1' }),
    Value      => _stub_type(name => 'Value',   parent => 'Defined', constraint => sub {
                      defined $_[0] && !ref $_[0] }),
    Ref        => _stub_type(name => 'Ref',     parent => 'Defined', constraint => sub { ref $_[0] ? 1 : 0 }),
    Str        => _stub_type(name => 'Str',     parent => 'Value',   constraint => sub {
                      defined $_[0] && !ref $_[0] }),
    Num        => _stub_type(name => 'Num',     parent => 'Str', constraint => sub {
                      defined $_[0] && !ref $_[0]
                          && $_[0] =~ /\A-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?\z/ }),
    Int        => _stub_type(name => 'Int',     parent => 'Num', constraint => sub {
                      defined $_[0] && !ref $_[0] && $_[0] =~ /\A-?\d+\z/ }),
    ScalarRef  => _stub_type(name => 'ScalarRef', parent => 'Ref', constraint => sub {
                      ref $_[0] eq 'SCALAR' || ref $_[0] eq 'REF' }),
    ArrayRef   => _stub_type(name => 'ArrayRef',  parent => 'Ref', constraint => sub { ref $_[0] eq 'ARRAY' }),
    HashRef    => _stub_type(name => 'HashRef',   parent => 'Ref', constraint => sub { ref $_[0] eq 'HASH' }),
    CodeRef    => _stub_type(name => 'CodeRef',   parent => 'Ref', constraint => sub { ref $_[0] eq 'CODE' }),
    RegexpRef  => _stub_type(name => 'RegexpRef', parent => 'Ref', constraint => sub { ref $_[0] eq 'Regexp' }),
    GlobRef    => _stub_type(name => 'GlobRef',   parent => 'Ref', constraint => sub { ref $_[0] eq 'GLOB' }),
    FileHandle => _stub_type(name => 'FileHandle', parent => 'Ref', constraint => sub {
                      ref $_[0] eq 'GLOB'
                          || (Scalar::Util::blessed($_[0]) && $_[0]->isa('IO::Handle')) }),
    Object     => _stub_type(name => 'Object',    parent => 'Ref', constraint => sub {
                      Scalar::Util::blessed($_[0]) ? 1 : 0 }),
    ClassName  => _stub_type(name => 'ClassName', parent => 'Str', constraint => sub {
                      defined $_[0] && !ref $_[0] && $_[0] =~ /\A[A-Za-z_][\w:]*\z/ }),
    RoleName   => _stub_type(name => 'RoleName',  parent => 'ClassName', constraint => sub {
                      defined $_[0] && !ref $_[0] && $_[0] =~ /\A[A-Za-z_][\w:]*\z/ }),
);

sub list_all_builtin_type_constraints { keys %STANDARD_TYPES }
sub list_all_type_constraints {
    return ( keys(%STANDARD_TYPES), keys %TYPES );
}

# ---------------------------------------------------------------------------
# Find-or-parse: tries the registry first, then handles the "Foo|Bar" /
# "Maybe[Foo]" / "ArrayRef[Foo]" parameterized name-syntax. We stop short
# of a full type-name parser; for unknown shapes we just return undef.
# ---------------------------------------------------------------------------

sub find_or_parse_type_constraint {
    my ($name) = @_;
    return undef unless defined $name;
    my $t = find_type_constraint($name);
    return $t if $t;
    # Union: 'Foo | Bar'
    if ($name =~ /\|/) {
        my @members = map { find_or_parse_type_constraint($_) }
                      map { my $x = $_; $x =~ s/\A\s+|\s+\z//g; $x }
                      split /\|/, $name;
        return undef if grep { !defined } @members;
        return _stub_type(name => $name, parent => 'Any',
                          constraint => sub {
                              for my $m (@members) {
                                  return 1 if $m->check($_[0]);
                              }
                              return 0;
                          });
    }
    # Maybe[Foo]
    if ($name =~ /\AMaybe\[(.+)\]\z/) {
        my $inner = find_or_parse_type_constraint($1);
        return undef unless $inner;
        return _stub_type(name => $name, parent => 'Item',
                          constraint => sub {
                              return 1 if !defined $_[0];
                              $inner->check($_[0]);
                          });
    }
    # ArrayRef[Foo] / HashRef[Foo] — drop parameterization for now.
    if ($name =~ /\A(ArrayRef|HashRef|ScalarRef)\[/) {
        my $base = find_type_constraint($1);
        return $base;
    }
    return undef;
}

# ---------------------------------------------------------------------------
# Bulk-export every registered type-constraint as a sub in the caller's
# package. Used by Moose::Util::TypeConstraints itself in some idioms,
# and occasionally by downstream code that builds wrapper modules.
# ---------------------------------------------------------------------------

sub export_type_constraints_as_functions {
    my $caller = caller;
    no strict 'refs';
    for my $name (list_all_type_constraints()) {
        next unless defined $name;
        my $t = find_type_constraint($name);
        next unless $t;
        next if defined &{"${caller}::${name}"};
        *{"${caller}::${name}"} = sub { $t->check(@_) };
    }
    return;
}

1;

__END__

=head1 NAME

Moose::Util::TypeConstraints - PerlOnJava compatibility stub

=head1 DESCRIPTION

A best-effort stub of L<Moose::Util::TypeConstraints>. Type declarations are
accepted and remembered but not deeply enforced. Sufficient for code that
declares types at compile time without later relying on the full Moose MOP.

See C<dev/modules/moose_support.md>.

=cut
