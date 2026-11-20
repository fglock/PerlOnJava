package Set::Scalar;

use strict;
# local $^W = 1;

use vars qw($VERSION @ISA);

$VERSION = '1.29';
@ISA = qw(Set::Scalar::Real Set::Scalar::Null Set::Scalar::Base);

use Set::Scalar::Base qw(_make_elements is_equal as_string_callback);
use Set::Scalar::Real;
use Set::Scalar::Null;
use Set::Scalar::Universe;

sub ELEMENT_SEPARATOR { " "    }
sub SET_FORMAT        { "(%s)" }

sub _insert_hook {
    my $self     = shift;

    if (@_) {
	my $elements = shift;

	$self->universe->_extend( $elements );

	$self->_insert_elements( $elements );
    }
}

sub _new_hook {
    my $self     = shift;
    my $elements = shift;

    $self->{ universe } = Set::Scalar::Universe->universe;

    $self->_insert( { _make_elements( @$elements ) } );
}

=pod

=head1 NAME

Set::Scalar - basic set operations

=head1 SYNOPSIS

    use Set::Scalar;
    $s = Set::Scalar->new;
    $s->insert('a', 'b');
    $s->delete('b');
    $t = Set::Scalar->new('x', 'y', $z);

=head1 DESCRIPTION

=head2 Creating

    $s = Set::Scalar->new;
    $s = Set::Scalar->new(@members);

    $t = $s->clone;
    $t = $s->copy;         # Clone of clone.
    $t = $s->empty_clone;  # Like clone() but with no members.

=head2 Modifying

    $s->insert(@members);
    $s->delete(@members);
    $s->invert(@members);  # Insert if hasn't, delete if has.

    $s->clear;  # Removes all the elements.

Note that clear() only releases the memory used by the set to
be reused by Perl; it will not reduce the overall memory use.

=head2 Displaying

    print $s, "\n";

The display format of a set is the members of the set separated by
spaces and enclosed in parentheses (), for example:

   my $s = Set::Scalar->new();
   $s->insert("a".."e");
   print $s, "\n";

will output

   a b c d e

You can even display recursive sets.

See L</"Customising Display"> for customising the set display.

=head2 Querying

Assuming a set C<$s>:

    @members  = $s->members;
    @elements = $s->elements;  # Alias for members.

    @$s  # Overloaded alias for members.

    $size = $s->size;  # The number of members.

    $s->has($m)        # Return true if has that member.
    $s->contains($m)   # Alias for has().

    if ($s->has($member)) { ... }

    $s->member($m)     # Returns the member if has that member.
    $s->element($m)    # Alias for member.

    $s->is_null        # Returns true if the set is empty.
    $s->is_empty       # Alias for is_null.

    $s->is_universal   # Returns true if the set is universal.

    $s->null           # The null set.
    $s->empty          # Alias for null.
    $s->universe       # The universe of the set.

=head2 Deriving

    $u = $s->union($t);
    $i = $s->intersection($t);
    $d = $s->difference($t);
    $e = $s->symmetric_difference($t);
    $v = $s->unique($t);
    $c = $s->complement;

These methods have operator overloads:

    $u = $s + $t;  # union
    $i = $s * $t;  # intersection
    $d = $s - $t;  # difference
    $e = $s % $t;  # symmetric_difference
    $v = $s / $t;  # unique
    $c = -$s;      # complement

Both the C<symmetric_difference> and C<unique> are symmetric on all
their arguments.  For two sets they are identical but for more than
two sets beware: C<symmetric_difference> returns true for elements
that are in an odd number (1, 3, 5, ...) of sets, C<unique> returns
true for elements that are in one set.

Some examples of the various set differences below
(the _ is just used to align the elements):

    set or difference                   value

    $a                                  (a b c d e _ _ _ _)
    $b                                  (_ _ c d e f g _ _)
    $c                                  (_ _ _ _ e f g h i)

    $a->difference($b)                  (a b _ _ _ _ _ _ _)
    $a->symmetric_difference($b)        (a b _ _ _ f g _ _)
    $a->unique($b)                      (a b _ _ _ f g _ _)

    $b->difference($a)                  (_ _ _ _ _ f g _ _)
    $b->symmetric_difference($a)        (a b _ _ _ f g _ _)
    $b->unique($a)                      (a b _ _ _ f g _ _)

    $a->difference($b, $c)              (a b _ _ _ _ _ _ _)
    $a->symmetric_difference($b, $c)    (a b _ _ e _ _ h i)
    $a->unique($b, $c)                  (a b _ _ _ _ _ h i)

=head2 Comparing

    $eq = $s->is_equal($t);
    $dj = $s->is_disjoint($t);
    $pi = $s->is_properly_intersecting($t);
    $ps = $s->is_proper_subset($t);
    $pS = $s->is_proper_superset($t);
    $is = $s->is_subset($t);
    $iS = $s->is_superset($t);

    $cmp = $s->compare($t);

The C<compare> method returns a string from the following list:
"equal", "disjoint", "proper subset", "proper superset", "proper
intersect", and in future (once I get around implementing it),
"disjoint universes".

These methods have operator overloads:

    $eq = $s == $t;  # is_equal
    $dj = $s != $t;  # is_disjoint
    # No operator overload for is_properly_intersecting.
    $ps = $s < $t;   # is_proper_subset
    $pS = $s > $t;   # is_proper_superset
    $is = $s <= $t;  # is_subset
    $iS = $s >= $t;  # is_superset

    $cmp = $s <=> $t;

=head2 Boolean contexts

In Boolean contexts such as

    if ($set) { ... }
    while ($set1 && $set2) { ... }

the size of the C<$set> is tested, so empty sets test as false,
and non-empty sets as true.

=head2 Iterating

    while (defined(my $e = $s->each)) { ... }

This is more memory-friendly than

    for my $e ($s->elements) { ... }

which would first construct the full list of elements and then
walk through it: the C<$s-E<gt>each> handles one element at a time.

Analogously to using normal C<each(%hash)> in scalar context,
using C<$s-E<gt>each> has the following caveats:

=over 4

=item *

The elements are returned in (apparently) random order.
So don't expect any particular order.

=item *

When no more elements remain C<undef> is returned.  Since you may one
day have elements named C<0> don't test just like this

    while (my $e = $s->each) { ... }           # WRONG!

but instead like this

    while (defined(my $e = $s->each)) { ... }  # Right.

(An C<undef> as a set element doesn't really work, you get C<"">.)

=item *

There is one iterator per one set which is shared by many
element-accessing interfaces-- using the following will reset the
iterator: C<elements()>, C<insert()>, C<members()>, C<size()>,
C<unique()>.  C<insert()> causes the iterator of the set being
inserted (not the set being the target of insertion) becoming reset.
C<unique()> causes the iterators of all the participant sets becoming
reset.  B<The iterator getting reset most probably causes an endless
loop.> So avoid doing that.

For C<delete()> the story is a little bit more complex: it depends
on what element you are deleting and on the version of Perl.  On modern
Perls you can safely delete the element you just deleted.  But deleting
random elements can affect the iterator, so beware.

=item *

Modifying the set during the iteration may cause elements to be missed
or duplicated, or in the worst case, an endless loop; so don't do
that, either.

=back

=head2 Cartesian Product and Power Set

=over 4

=item *

Cartesian product is a product of two or more sets.  For two sets, it
is the set consisting of B<ordered pairs> of members from each set.
For example for the sets

  (a b)
  (c d e)

The Cartesian product of the above is the set

  ([a, c] [a, d] [a, e] [b, c] [b, d] [b, e])

The [,] notation is for the ordered pairs, which sets are not.
This means two things: firstly, that [e, b] is B<not> in the above
Cartesian product, and secondly, [b, b] is a possibility:

  (a b)
  (b c e)

  ([a, b] [a, c] [a, e] [b, b] [b, c] [b, d])

For example:

  my $a = Set::Scalar->new(1..2);
  my $b = Set::Scalar->new(3..5);
  my $c = $a->cartesian_product($b);  # As an object method.
  my $d = Set::Scalar->cartesian_product($a, $b);  # As a class method.

The $c and $d will be of the same class as $a.  The members of $c and
$c in the above will be anonymous arrays (array references), not sets,
since sets wouldn't be able to represent the ordering or that a member
can be present more than once.  Also note that since the members of
the input sets are unordered, the ordered pairs themselves are
unlikely to be in any particular order.

If you don't want to construct the Cartesian product set, you can
construct an iterator and call it while it returns more members:

   my $iter = Set::Scalar->cartesian_product_iterator($a, $b, $c);
   while (my @m = $iter->()) {
     process(@m);
   }

=item *

Power set is the set of all the subsets of a set.  If the set has N
members, its power set has 2**N members.  For example for the set

    (a b c)

size 3, its power set is

    (() (a) (b) (c) (a b) (a c) (b c) (a b c))

size 8.  Note that since the elements of the power set are sets, they
are unordered, and therefore (b c) is equal to (c b).  For example:

    my $a = Set::Scalar->new(1..3);
    my $b = $a->power_set;               # As an object method.
    my $c = Set::Scalar->power_set($a);  # As a class method.

Even the empty set has a power set, of size one.

If you don't want to construct the power set, you can construct an
iterator and call it until it returns no more members:

   my $iter = Set::Scalar->power_set_iterator($a);
   my @m;
   do {
     @m = $iter->();
     process(@m);
   } while (@m);

=back

=head2 Customising Display

If you want to customise the display routine you will have to
modify the C<as_string> callback.  You can modify it either
for all sets by using C<as_string_callback()> as a class method:

    my $class_callback = sub { ... };

    Set::Scalar->as_string_callback($class_callback);

or for specific sets by using C<as_string_callback()> as an object
method:

    my $callback = sub  { ... };

    $s1->as_string_callback($callback);
    $s2->as_string_callback($callback);

The anonymous subroutine gets as its first (and only) argument the
set to display as a string.  For example to display the set C<$s>
as C<a-b-c-d-e> instead of C<(a b c d e)>

    $s->as_string_callback(sub{join("-",sort $_[0]->elements)});

If called without an argument, the current callback is returned.

If called as a class method with undef as the only argument, the
original callback (the one returning C<(a b c d e)>) for all the sets
is restored, or if called for a single set the callback is removed
(and the callback for all the sets will be used).

=head1 CAVEATS

The first priority of Set::Scalar is to be a convenient interface to sets.
While not designed to be slow or big, neither has it been designed to
be fast or compact.

Using references (or objects) as set members has not been extensively
tested.  The desired semantics are not always clear: what should
happen when the elements behind the references change? Especially
unclear is what should happen when the objects start having their
own stringification overloads.

=head1 SEE ALSO

Set::Bag for bags (multisets, counted sets), and Bit::Vector for fast
set operations (you have to take care of the element name to bit
number and back mappings yourself), or Set::Infinite for sets of
intervals, and many more.  CPAN is your friend.

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>
David Oswald <davido@cpan.org> is the current maintainer.
The GitHub repo is at L<https://github.com/daoswald/Set-Scalar>

=head1 COPYRIGHT AND LICENSE

Copyright 2001,2002,2003,2004,2005,2007,2009,2013 by Jarkko Hietaniemi

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut

1;
