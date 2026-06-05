package Set::Object;

use strict;
use warnings;
use Carp ();
use Exporter ();
use Scalar::Util ();

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    ish_int is_int is_string is_double blessed reftype refaddr
    is_overloaded is_object is_key set weak_set
);
our $VERSION = '1.43';
our $cust_disp;
my %STORE;

use overload
    '""'  => \&as_string,
    '+'   => \&op_union,
    '*'   => \&op_intersection,
    '%'   => \&op_symm_diff,
    '/'   => \&op_invert,
    '-'   => \&difference,
    '=='  => \&equal,
    '!='  => \&not_equal,
    '<'   => \&proper_subset,
    '>'   => \&proper_superset,
    '<='  => \&subset,
    '>='  => \&superset,
    '@{}' => sub { [ sort { "$a" cmp "$b" } $_[0]->members ] },
    '%{}' => sub {
        my %h;
        @h{$_[0]->members} = (1) x $_[0]->size;
        \%h;
    },
    'bool' => sub { 1 },
    fallback => 1;

sub _state {
    my ($self) = @_;
    my $id = Scalar::Util::refaddr($self);
    $STORE{$id} ||= { _members => {}, _weak => 0 };
}

sub _members {
    my ($self) = @_;
    _state($self)->{_members} ||= {};
}

sub _key {
    return undef unless defined $_[0];
    return ref($_[0]) ? 'R:' . Scalar::Util::refaddr($_[0]) : 'S:' . $_[0];
}

sub _compact {
    my ($self) = @_;
    my $m = _members($self);
    for my $k (keys %$m) {
        delete $m->{$k} unless defined $m->{$k};
    }
    $self;
}

sub new {
    my $class = shift;
    $class = ref($class) || $class;
    my $token = '';
    my $self = bless \$token, $class;
    $STORE{Scalar::Util::refaddr($self)} = { _members => {}, _weak => 0 };
    $self->insert(@_);
    return $self;
}

sub DESTROY {
    delete $STORE{Scalar::Util::refaddr($_[0])};
}

sub set {
    my $class = __PACKAGE__;
    if (blessed($_[0]) && $_[0]->isa(__PACKAGE__)) {
        $class = shift->strong_pkg;
    }
    return $class->new(@_);
}

sub weak_set {
    require Set::Object::Weak;
    return Set::Object::Weak->new(@_);
}

sub insert {
    my $self = shift;
    my $m = _members($self);
    my $added = 0;
    for my $item (@_) {
        my $key = _key($item);
        next unless defined $key;
        next if exists $m->{$key} && defined $m->{$key};
        $m->{$key} = $item;
        Scalar::Util::weaken($m->{$key}) if _state($self)->{_weak} && ref($m->{$key});
        $added++;
    }
    return $added;
}

sub remove {
    my $self = shift;
    my $m = _members($self);
    my $removed = 0;
    for my $item (@_) {
        my $key = _key($item);
        next unless defined $key;
        $removed++ if exists $m->{$key} && defined $m->{$key};
        delete $m->{$key};
    }
    return $removed;
}

*delete = \&remove;

sub includes {
    my $self = shift;
    _compact($self);
    my $m = _members($self);
    for my $item (@_) {
        my $key = _key($item);
        return 0 unless defined $key && exists $m->{$key} && defined $m->{$key};
    }
    return 1;
}

*has = \&includes;
*contains = \&includes;

sub member {
    my ($self, $item) = @_;
    return $item if $self->includes($item);
    return;
}

*element = \&member;

sub members {
    my $self = shift;
    _compact($self);
    return values %{ _members($self) };
}

*elements = \&members;

sub size {
    my $self = shift;
    _compact($self);
    return scalar keys %{ _members($self) };
}

sub clear {
    %{ _members($_[0]) } = ();
    return $_[0];
}

sub is_null {
    return $_[0]->size == 0;
}

sub weak_pkg { 'Set::Object::Weak' }
sub strong_pkg { 'Set::Object' }
sub tie_hash_pkg { 'Set::Object::TieHash' }
sub tie_array_pkg { 'Set::Object::TieArray' }

sub is_weak {
    return _state($_[0])->{_weak} ? 1 : 0;
}

sub weaken {
    my $self = shift;
    my $state = _state($self);
    $state->{_weak} = 1;
    for my $k (keys %{ _members($self) }) {
        Scalar::Util::weaken($state->{_members}{$k})
            if ref($state->{_members}{$k});
    }
    bless $self, $self->weak_pkg unless $self->isa($self->weak_pkg);
    return $self;
}

sub strengthen {
    my $self = shift;
    _state($self)->{_weak} = 0;
    bless $self, $self->strong_pkg unless $self->isa($self->strong_pkg);
    return $self;
}

sub as_string {
    return $cust_disp->(@_) if $cust_disp;
    my $self = shift;
    Carp::croak("Tried to use as_string on something other than a Set::Object")
        unless UNIVERSAL::isa($self, __PACKAGE__);
    return ref($self) . '(' . join(' ', sort { "$a" cmp "$b" } $self->members) . ')';
}

sub as_string_callback {
    my ($class, $cb) = @_;
    $cust_disp = $cb;
}

sub union {
    my $self = shift;
    return $self->set(map { $_->members } grep { UNIVERSAL::isa($_, __PACKAGE__) } $self, @_);
}

sub op_union {
    my $self = shift;
    my $other = ref($_[0]) ? shift : $self->set(shift);
    Carp::croak("Tried to form union between Set::Object & `$other'")
        if ref($other) && !UNIVERSAL::isa($other, __PACKAGE__);
    return $self->union($other);
}

sub intersection {
    my $set = shift;
    my $result = $set->set($set->members);
    while (my $other = shift) {
        $other = $result->new($other) unless ref $other;
        Carp::croak("Tried to form intersection between Set::Object & " . (ref($other) || $other))
            unless UNIVERSAL::isa($other, __PACKAGE__);
        $result->remove(grep { !$other->includes($_) } $result->members);
    }
    return $result;
}

sub op_intersection {
    my ($self, $other, $reverse) = @_;
    $other = $self->set($other) unless ref $other;
    return $reverse ? intersection($other, $self) : intersection($self, $other);
}

sub difference {
    my ($self, $other, $reverse) = @_;
    $other = __PACKAGE__->new($other) unless ref $other;
    Carp::croak("Tried to find difference between Set::Object & " . (ref($other) || $other))
        unless UNIVERSAL::isa($other, __PACKAGE__);
    return $reverse
        ? $other->set(grep { !$self->includes($_) } $other->members)
        : $self->set(grep { !$other->includes($_) } $self->members);
}

sub symmetric_difference {
    my ($self, $other) = @_;
    Carp::croak("Tried to find symmetric difference between Set::Object & " . (ref($other) || $other))
        unless UNIVERSAL::isa($other, __PACKAGE__);
    return $self->difference($other)->union($other->difference($self));
}

sub op_symm_diff {
    my $self = shift;
    my $other = ref($_[0]) ? shift : __PACKAGE__->new(shift);
    return $self->symmetric_difference($other);
}

sub invert {
    my $self = shift;
    my $changed = 0;
    for my $item (@_) {
        if ($self->includes($item)) {
            $self->remove($item);
        } else {
            $self->insert($item);
        }
        $changed++;
    }
    return $changed;
}

sub op_invert {
    my $self = shift;
    my $other = ref($_[0]) ? shift : __PACKAGE__->new(shift);
    Carp::croak("Tried to form union between Set::Object & `$other'")
        if ref($other) && !UNIVERSAL::isa($other, __PACKAGE__);
    my $result = $self->set($self->members);
    $result->invert($other->members);
    return $result;
}

sub unique {
    my $self = shift;
    return $self->symmetric_difference(@_);
}

sub equal {
    my ($a, $b) = @_;
    return undef unless UNIVERSAL::isa($b, __PACKAGE__);
    return $a->size == $b->size && $a->includes($b->members);
}

sub not_equal {
    return !shift->equal(shift);
}

sub subset {
    my ($a, $b) = @_;
    Carp::croak("Tried to find subset of Set::Object & " . (ref($b) || $b))
        unless UNIVERSAL::isa($b, __PACKAGE__);
    return $b->includes($a->members);
}

sub proper_subset {
    my ($a, $b) = @_;
    Carp::croak("Tried to find proper subset of Set::Object & " . (ref($b) || $b))
        unless UNIVERSAL::isa($b, __PACKAGE__);
    return $a->size < $b->size && $a->subset($b);
}

sub superset {
    my ($a, $b) = @_;
    Carp::croak("Tried to find superset of Set::Object & " . (ref($b) || $b))
        unless UNIVERSAL::isa($b, __PACKAGE__);
    return $b->subset($a);
}

sub proper_superset {
    my ($a, $b) = @_;
    Carp::croak("Tried to find proper superset of Set::Object & " . (ref($b) || $b))
        unless UNIVERSAL::isa($b, __PACKAGE__);
    return $b->proper_subset($a);
}

sub is_disjoint {
    my ($a, $b) = @_;
    for my $item ($a->members) {
        return 0 if $b->includes($item);
    }
    return 1;
}

sub compare {
    my ($a, $b) = @_;
    return 'equal' if $a->equal($b);
    return 'proper subset' if $a->proper_subset($b);
    return 'proper superset' if $a->proper_superset($b);
    return 'disjoint' if $a->is_disjoint($b);
    return 'proper intersect';
}

sub blessed { Scalar::Util::blessed($_[0]) }
sub reftype { Scalar::Util::reftype($_[0]) }
sub refaddr { Scalar::Util::refaddr($_[0]) }
sub is_overloaded { overload::Overloaded($_[0]) ? 1 : 0 }
sub is_object { ref($_[0]) ? 1 : 0 }
sub is_int { defined($_[0]) && !ref($_[0]) && "$_[0]" =~ /\A[+-]?\d+\z/ ? 1 : 0 }
sub is_double { defined($_[0]) && !ref($_[0]) && "$_[0]" =~ /\A[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?\z/ ? 1 : 0 }
sub is_string { defined($_[0]) && !ref($_[0]) ? 1 : 0 }
sub _ish_int { is_int($_[0]) ? int($_[0]) : undef }
sub ish_int { _ish_int($_[0]) }
sub is_key { defined($_[0]) && !ref($_[0]) ? 1 : undef }

1;
