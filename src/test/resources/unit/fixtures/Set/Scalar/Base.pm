package Set::Scalar::Base;

use strict;
# local $^W = 1;

require Exporter;

use vars qw($VERSION @ISA @EXPORT_OK);

$VERSION = '1.29';
@ISA = qw(Exporter);

BEGIN {
    eval 'require Scalar::Util';
    unless ($@) {
	import Scalar::Util qw(blessed refaddr);
    } else {
	# Use the pure Perl emulations (directly snagged from Scalar::Util).
	eval 'sub UNIVERSAL::a_sub_not_likely_to_be_here { ref($_[0]) }';
	*blessed = sub ($) {
	    local($@, $SIG{__DIE__}, $SIG{__WARN__});
	    length(ref($_[0]))
		? eval { $_[0]->a_sub_not_likely_to_be_here }
	    : undef
	};
        *refaddr = sub ($) {
	    my $pkg = ref($_[0]) or return undef;
	    if (blessed($_[0])) {
		bless $_[0], 'Scalar::Util::Fake';
	    }
	    else {
		$pkg = undef;
	    }
	    "$_[0]" =~ /0x(\w+)/;
	    my $i = do { local $^W; hex $1 };
	    bless $_[0], $pkg if defined $pkg;
	    $i;
	};
    }
}

@EXPORT_OK = qw(_make_elements
		as_string
		as_string_callback
		_compare is_equal
		_binary_underload
		_unary_underload
		_strval);

use overload
    '+'		=> \&_union_overload,
    '*'		=> \&_intersection_overload,
    '-'		=> \&_difference_overload,
    'neg'	=> \&_complement_overload,
    '%'		=> \&_symmetric_difference_overload,
    '/'		=> \&_unique_overload,
    'eq'	=> \&is_equal,
    '=='	=> \&is_equal,
    '!='	=> \&is_disjoint,
    '<=>'	=> \&compare,
    '<'		=> \&is_proper_subset,
    '>'		=> \&is_proper_superset,
    '<='	=> \&is_subset,
    '>='	=> \&is_superset,
    'bool'	=> \&size,
    '@{}'	=> sub { [ $_[0]->members ] },
    '='         => sub { $_[0]->clone($_[1]) },
    'cmp'       => sub { "$_[0]" cmp "$_[1]" };

use constant OVERLOAD_BINARY_2ND_ARG  => 1;
use constant OVERLOAD_BINARY_REVERSED => 2;

sub _binary_underload { # Handle overloaded binary operators.
    my (@args) = @{ $_[0] };

    if (@args == 3) {
	$args[1] = (ref $args[0])->new( $args[1] ) unless ref $args[1];
	@args[0, 1] = @args[1, 0] if $args[OVERLOAD_BINARY_REVERSED];
	pop @args;
    }

    return @args;
}

sub _unary_underload { # Handle overloaded unary operators.
    if (@{ $_[0] } == 3) {
	pop @{ $_[0] };
	pop @{ $_[0] };
    }
}

sub _new_hook {
    # Just an empty stub.
}

sub new {
    my $class = shift;

    my $self = { };

    bless $self, ref $class || $class;

    $self->_new_hook( \@_ );

    return $self;
}

sub _strval {
    my $class = ref $_[0];
    return $_[0] unless $class;
    sprintf "%s(%s)", $class, refaddr $_[0];
}

sub _make_elements {
    return map { (defined $_ ? _strval($_) : "") => $_ } @_;
}

sub _invalidate_cached {
    my $self = shift;

    delete @{ $self }{ "as_string" };
}

sub _insert_hook {
    # Just an empty stub.
}

sub _insert {
    my $self     = shift;
    my $elements = shift;

    $self->_insert_hook( $elements );
}

sub _insert_elements {
    my $self     = shift;
    my $elements = shift;

    @{ $self->{'elements'} }{ keys %$elements } = values %$elements;

    $self->_invalidate_cached;
}

sub universe {
    my $self = shift;

    return $self->{'universe'};
}

sub size {
    my $self = shift;

    return scalar keys %{ $self->{'elements'} };
}

sub elements {
    my $self = shift;

    return @_ ?
	@{ $self->{'elements'} }{ map { _strval($_) } @_ } :
	values %{ $self->{'elements'} };
}

*members = \&elements;

sub element {
    my $self = shift;

    $self->elements( shift );
}

*member   = \&element;

sub has {
    my $self = shift;

    my @has = map { exists $self->{'elements'}->{ $_ } } @_;

    return wantarray ? @has : @_ > 1 ? grep { $_ } @has : $has[0];
}

*contains = \&has;

sub each {
    my $self = shift;

    my ($k, $e) = each %{ $self->{'elements'} };

    return $e;
}

sub _empty_clone {
    my $self     = shift;
    my $original = shift;

    $self->{'universe'} = $original->{'universe'};
    $self->{'null'    } = $original->{'null'    };
}

sub _clone {
    my $self     = shift;
    my $original = shift;

    $self->_empty_clone($original);

    $self->_insert( $original->{'elements'} );
}

sub clone {
    my $self  = shift;
    my $clone = (ref $self)->new;

    $clone->_clone( $self );

    return $clone;
}

*copy = \&clone;

sub empty_clone {
    my $self  = shift;
    my $clone = (ref $self)->new;

    $clone->_empty_clone( $self );

    return $clone;
}

sub clear {
    my $self = shift;

    undef %{ $self };
    undef @{ $self }{ "as_string" };
}

sub _union ($$) {
    my ($this, $that) = @_;

    my $this_universe = $this->universe;

    return (undef,          1, undef)
	unless $this_universe == $that->universe;

    return ($this->clone,   0, ref $this)
	if $that->is_null;

    return ($that->clone,   0, ref $that)
	if $this->is_null;

    return ($this, 1, ref $this)
	if $this->is_universal;

    return ($that, 1, ref $that)
	if $that->is_universal;

    my $union = $this->clone;

    $union->insert( $that->elements );

    return ($union, $union->is_universal, ref $this);
}

sub _union_overload {
    my ($this, $that) = _binary_underload( \@_ );

    my ($union, $is_universal, $class) = $this->_union( $that );

    return $union;
}

sub union {
    my $self = shift;

    my $union = $self->clone;

    my $is_universal;
    my $class;

    foreach my $next ( @_ ) {
	unless ($next->is_null) {
	    ($union, $is_universal, $class) = $union->_union( $next );

	    last if $is_universal;
	}
    }

    $union = $self
	if $is_universal && $union->size == $self->size;

    return $union;
}

sub _intersection ($$) {
    my $this = shift;
    my $that = shift;

    return (undef,        1)
	unless $this->universe == $that->universe;

    return ($this->null,  1)
	if $this->is_null || $that->is_null;

    return ($this->clone, 0)
	if $that->is_universal;

    return ($that->clone, 0)
	if $this->is_universal;

    my $intersection = $this->clone;

    my %intersection = _make_elements $intersection->elements;

    delete @intersection{ keys %{{ _make_elements $that->elements }} };

    $intersection->delete( values %intersection );

    return ($intersection, $intersection->is_null);
}

sub _intersection_overload {
    my ($this, $that) = _binary_underload( \@_ );

    my ($intersection) = $this->_intersection( $that );

    return $intersection;
}

sub intersection {
    my $self = shift;

    my $intersection = $self->clone;

    my $is_null;

    foreach my $next ( @_ ) {
	unless ($next->is_universal) {
	    ($intersection, $is_null) =	$intersection->_intersection( $next );

	    last if $is_null;
	}
    }

    $intersection = $self
	if $is_null && $intersection->size == $self->size;

    return $intersection;
}

sub _difference ($$) {
    my $this = shift;
    my $that = shift;

    return undef        unless $this->universe == $that->universe;

    return $this->null  if $this->is_null || $that->is_universal;
    return $this->clone if $that->is_null;

    my $difference = $this->clone;

    my %that = _make_elements $that->elements;

    $difference->delete( values %that );

    return $difference;
}

sub _difference_overload {
    my ($this, $that) = _binary_underload( \@_ );

    return $this->_difference( $that );
}

sub difference {
    my $this = shift;

    return $this->null if $this->is_null;

    return $this->clone unless @_;

    my $that = shift;

    $that = $that->union( @_ );

    return undef unless defined $that;

    return $this->null if $that->is_universal;

    my $difference = $this->_difference( $that );

    $difference = $this
	if $difference->size == $this->size;

    return $difference;
}

sub _symmetric_difference ($$) {
    my $this = shift;
    my $that = shift;

    return (undef, 1) unless $this->universe == $that->universe;

    return $that->clone      if $this->is_null;
    return $this->clone      if $that->is_null;

    return $that->complement if $this->is_universal;
    return $this->complement if $that->is_universal;

    my $symmetric_difference = $this->clone;

    $symmetric_difference->invert( $that->elements );

    return $symmetric_difference;
}

sub _symmetric_difference_overload {
    my ($this, $that ) = _binary_underload( \@_ );

    return $this->_symmetric_difference( $that );
}

sub symmetric_difference {
    my $this = shift;

    my $symmetric_difference = $this->clone;

    foreach my $next ( @_ ) {
	$symmetric_difference->invert( $next->elements );
    }

    return $symmetric_difference;
}

*symmdiff = \&symmetric_difference;

sub _complement {
    my $self       = shift;
    my $complement = (ref $self)->new( $self->universe->elements );

    $complement->delete( $self->elements );

    return $complement;
}

sub _complement_overload {
    _unary_underload( \@_ );

    my $self = shift;

    return $self->_complement;
}

sub complement {
    my $self = shift;

    return $self->_complement;
}

sub _unique {
    my $universe = $_[0]->universe;
    my %frequency;

    for my $set ( @_ ) {
	if ($set->universe == $universe) {
	    foreach my $element ( keys %{ $set->{'elements'} } ) {
		$frequency{ $element }++;
	    }
	} else {
	    return (ref $_[0])->new();
	}
    }

    return (ref $_[0])->new(grep { $frequency{ $_ } == 1 } keys %frequency);
}

sub _unique_overload {
    my ($this, $that) = _binary_underload( \@_ );

    return $this->_unique( $that );
}

sub unique {
    my $this = shift;

    return $this->_unique( @_ );
}

sub _make_cartesian_product_iterator {
    my @iter;
    my @value;
    for my $set (@_) {
	return unless $set->isa('Set::Scalar');
	my @member = $set->members;
	my %member;
	@member{@member} = @member;
	push @iter, \%member;
	push @value, scalar CORE::each(%{ $iter[-1] });
    }
    return sub {
	return unless @iter;
	my @now = @value;
	my $ix;
	for ($ix = $#iter; $ix >= 0; $ix--) {
	    my $next = CORE::each(%{ $iter[$ix] });
	    if (defined $next) {
		$value[$ix] = $next;
		last;
	    } else {
		keys %{ $iter[$ix] };  # Reset the iterator.
		$value[$ix] = CORE::each(%{ $iter[$ix] });
	    }
	}
	if ($ix < 0) {
	    @iter = ();  # All done.
	}
	return @now;
    };
}

sub cartesian_product_iterator {
    shift unless ref $_[0];
    return &_make_cartesian_product_iterator;
}

sub cartesian_product {
    my $iterator = &cartesian_product_iterator;
    return unless defined $iterator;
    my $product = $_[0]->empty_clone;
    while (my @member = $iterator->()) {
	$product->insert(\@member);
    }
    return $product;
}

sub _make_power_set_iterator {
    return unless $_[0]->isa('Set::Scalar');
    my @member = $_[0]->members;
    my @iter   = (0) x @member;
    return sub {
	return unless @iter;
	my $ix;
	for ($ix = 0; $ix < @iter; $ix++) {
	    if ($iter[$ix]++ == 0) {
		last;
	    } else {
		$iter[$ix] = 0;
	    }
	}
	if ($ix == @iter) {
	    @iter = ();  # All done.
	}
	return map { $member[$_] } grep { $iter[$_] } 0..$#iter;
    };
}

sub power_set_iterator {
    shift unless ref $_[0];
    return &_make_power_set_iterator;
}

sub power_set {
    my $iterator = &power_set_iterator;
    return unless defined $iterator;
    my $power = $_[0]->empty_clone;
    my @member;
    do {
	@member = $iterator->();
	$power->insert($_[0]->empty_clone->insert(@member));
    } while (@member);
    return $power;
}

sub is_universal {
    my $self = shift;

    return $self->size == $self->universe->size;
}

sub is_null {
    my $self = shift;

    return $self->size == 0;
}

*is_empty = \&is_null;

sub null {
    my $self = shift;

    return $self->universe->null;
}

*empty = \&null;

sub _compare {
    my $a = shift;
    my $b = shift;

    return "$a" eq "$b" ? 'equal' : 'different';
}

sub compare {
    my $a = shift;
    my $b = shift;

    return _compare("$a", "$b")
	unless ref $a && $a->isa(__PACKAGE__) &&
	       ref $b && $b->isa(__PACKAGE__);

    return 'disjoint universes' unless $a->universe == $b->universe;

    my $c = $a->intersection($b);

    my $na = $a->size;
    my $nb = $b->size;
    my $nc = $c->size;

    return 'proper superset' if $na && $nb == 0;
    return 'proper subset'   if $na == 0 && $nb;
    return 'disjoint'        if $na && $nb && $nc == 0;
    return 'equal'           if $na == $nc && $nb == $nc;
    return 'proper superset' if $nb == $nc;
    return 'proper subset'   if $na == $nc;
    return 'proper intersect';
}

sub is_disjoint {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'disjoint' ||
           $a->compare($b) eq 'disjoint universes';
}

sub is_equal {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'equal';
}

sub is_proper_subset {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'proper subset';
}

sub is_proper_superset {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'proper superset';
}

sub is_properly_intersecting {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'proper intersect';
}

sub is_subset {
    my $a = shift;
    my $b = shift;

    my $c = $a->compare($b);

    return $c eq 'equal' || $c eq 'proper subset';
}

sub is_superset {
    my $a = shift;
    my $b = shift;

    my $c = $a->compare($b);

    return $c eq 'equal' || $c eq 'proper superset';
}

sub cmp {
    return "$_[0]" cmp "$_[1]";
}

sub have_same_universe {
    my $self     = shift;
    my $universe = $self->universe;

    foreach my $set ( @_ ) {
	return 0 unless $set->universe == $universe;
    }

    return 1;
}

sub _elements_have_reference {
    my $self     = shift;
    my $elements = shift;

    foreach my $element (@$elements) {
	return 1 if ref $element;
    }

    return 0;
}

use constant RECURSIVE_SELF => 1;
use constant RECURSIVE_DEEP => 2;

sub _elements_as_string {
    my $self    = shift;
    my $history = shift;

    my @elements = $self->elements;
    my $self_id  = _strval($self);
    my %history;

    %history = %{ $history } if defined $history;

    my $have_reference = $self->_elements_have_reference(\@elements);

    my @simple_elements;
    my @complex_elements;
    my $recursive;

    foreach my $element (@elements) {
	my $element_id = _strval($element);

	if (exists $history{ $element_id }) {
	    if ($element_id eq $self_id) {
		$recursive = RECURSIVE_SELF;
	    } else {
		$recursive = RECURSIVE_DEEP;
	    }
	} elsif (blessed $element && $element->isa(__PACKAGE__)) {
	    local $history{ $element_id } = 1;
	    push @complex_elements, $element->as_string( \%history );
	} else {
	    push @simple_elements, $element;
	}
    }

    @elements =     sort @simple_elements;
    push @elements, sort @complex_elements;

    return (join($self->_element_separator, @elements),
	    $have_reference,
	    $recursive);
}

my $AS_STRING_CALLBACK = sub {
    my $self = shift;

    my $string = '';

    if (exists $self->{'as_string'}) {
	$string = $self->{'as_string'};
    } else {
	($string, my $have_reference, my $is_recursive) =
	    $self->_elements_as_string(@_ ? shift :
                                            { _strval($self) => 1 });

	$string .= $self->_element_separator . "..." if $is_recursive;

	$string = sprintf $self->_set_format, $string;

	$self->{'as_string'} = $string unless $have_reference;
    }

    return $string;
};

my $as_string_callback = $AS_STRING_CALLBACK;

sub as_string_callback {
    my $arg = shift;

    if (ref $arg) {
	if (@_) {
	    $arg->{'as_string_callback'} = shift;
	    delete $arg->{'as_string_callback'}
	        unless defined $arg->{'as_string_callback'};
	} else {
	    return $arg->{'as_string_callback'};
	}
    } else {
	if (@_) {
	    $as_string_callback = shift;
	    $as_string_callback = $AS_STRING_CALLBACK
	        unless defined $as_string_callback;
	} else {
	    return $as_string_callback;
	}
    }
}

sub as_string {
    my $self = shift;

    if (exists $self->{'as_string_callback'}) {
	return $self->{'as_string_callback'}->($self, @_);
    } else {
	return $as_string_callback->($self, @_);
    }
}

sub _element_separator {
    my $self = shift;

    return $self->{'display'}->{'element_separator'}
        if exists $self->{'display'}->{'element_separator'};

    my $universe = $self->universe;

    return $universe->{'display'}->{'element_separator'}
        if exists $universe->{'display'}->{'element_separator'};

    return (ref $self)->ELEMENT_SEPARATOR;
}

sub _set_format {
    my $self = shift;

    return $self->{'display'}->{'set_format'}
        if exists $self->{'display'}->{'set_format'};

    my $universe = $self->universe;

    return $universe->{'display'}->{'set_format'}
        if exists $universe->{'display'}->{'set_format'};

    return (ref $self)->SET_FORMAT;
}

=pod

=head1 NAME

Set::Scalar::Base - base class for Set::Scalar

=head1 SYNOPSIS

B<Internal use only>.

=head1 DESCRIPTION

B<This is not the module you are looking for.>
See the L<Set::Scalar>.

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>

=cut

1;
