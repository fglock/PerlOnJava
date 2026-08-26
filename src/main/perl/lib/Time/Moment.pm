package Time::Moment;

use strict;
use warnings;
use Carp qw[];

our $VERSION = '0.46';

# The value and calendar operations are implemented by the bundled Java XS
# provider.  Keep this wrapper deliberately close to upstream so callers see
# the usual Time::Moment package and version.
use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

use overload
    '""'   => 'to_string',
    '<=>' => 'compare',
    fallback => 1;

sub STORABLE_freeze {
    my ($self, $cloning) = @_;
    return if $cloning;
    return pack 'nnNNN', 0x544D, $self->offset, $self->utc_rd_values;
}

sub STORABLE_thaw {
    my ($self, $cloning, $packed) = @_;
    return if $cloning;
    my $restored = _thaw_moment(ref($self), $packed);
    # The upstream XS object is a scalar reference.  The Java provider uses a
    # blessed hash, so retain upstream's in-place replacement semantics by
    # copying the restored hash payload into Storable's placeholder.
    %$self = %$restored;
}

# PerlOnJava's Storable reader deliberately supports STORABLE_attach: it is
# the representation-neutral hook for replacing a placeholder.  Prefer it
# for Java-backed (blessed-hash) moments; retain STORABLE_thaw above for
# compatibility with Storable implementations that use that older hook.
sub STORABLE_attach {
    my ($class, $cloning, $packed) = @_;
    return if $cloning;
    return _thaw_moment($class, $packed);
}

sub _thaw_moment {
    my ($class, $packed) = @_;
    (length($packed) == 16 && vec($packed, 0, 16) == 0x544D)
        or die 'Cannot deserialize corrupted data';
    my ($offset, $rdn, $sod, $nos) = unpack 'xxnNNN', $packed;
    $offset = ($offset & 0x7FFF) - 0x8000 if $offset & 0x8000;
    my $seconds = ($rdn - 719163) * 86400 + $sod;
    return $class->from_epoch($seconds, $nos)
                 ->with_offset_same_instant($offset);
}

sub TO_JSON { $_[0]->to_string }
sub FREEZE  { $_[0]->to_string }
sub THAW    { $_[0]->from_string($_[2]) }

*with_offset = \&with_offset_same_instant;

sub utc_year { $_[0]->with_offset_same_instant(0)->year }

sub with {
    my ($self, $adjuster) = @_;
    ref($adjuster) eq 'CODE'
        or Carp::croak("Parameter: 'adjuster' is not a CODE reference");
    my $result = $adjuster->($self);
    eval { $result->isa('Time::Moment') }
        or Carp::croak("Expected an instance of Time::Moment from adjuster");
    return $result;
}

# Keep object coercion in Perl, as upstream does: this lets ecosystem objects
# opt in with __as_Time_Moment while reusing the Java-backed constructors.
sub from_object {
    my ($class, $object) = @_;
    my $type = ref($object) || $object || 'unknown';

    if (eval { $object->can('__as_Time_Moment') }) {
        $object = $object->__as_Time_Moment;
    }

    if (eval { $object->can('time_zone') }
            && eval { $object->time_zone->is_floating }) {
        Carp::croak("Cannot coerce object of type $type with 'floating' time zone");
    }

    unless (eval { $object->can('epoch') }) {
        Carp::croak("Cannot coerce object of type $type");
    }

    my $nanosecond = eval { $object->can('nanosecond') }
        ? $object->nanosecond : 0;
    my $offset = 0;
    if (eval { $object->can('tzoffset') }) {
        $offset = $object->tzoffset / 60;       # Time::Piece: seconds
    }
    elsif (eval { $object->can('offset') }) {
        $offset = $object->offset / 60;         # DateTime: seconds
    }

    return $class->from_epoch($object->epoch, nanosecond => $nanosecond)
                 ->with_offset_same_instant($offset);
}

1;

__END__

=head1 NAME

Time::Moment - immutable date/time values with a fixed UTC offset

=head1 COPYRIGHT

Compatible with Time-Moment 0.46 by Christian Hansen.  The PerlOnJava
provider is backed by C<java.time>.

=cut
