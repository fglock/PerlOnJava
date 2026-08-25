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
    (length($packed) == 16 && vec($packed, 0, 16) == 0x544D)
        or die 'Cannot deserialize corrupted data';
    my ($offset, $rdn, $sod, $nos) = unpack 'xxnNNN', $packed;
    $offset = ($offset & 0x7FFF) - 0x8000 if $offset & 0x8000;
    my $seconds = ($rdn - 719163) * 86400 + $sod;
    $$self = ${ ref($self)->from_epoch($seconds, $nos)
                         ->with_offset_same_instant($offset) };
}

sub TO_JSON { $_[0]->to_string }
sub FREEZE  { $_[0]->to_string }
sub THAW    { $_[0]->from_string($_[2]) }

*with_offset = \&with_offset_same_instant;

sub utc_year { $_[0]->with_offset_same_instant(0)->year }

1;

__END__

=head1 NAME

Time::Moment - immutable date/time values with a fixed UTC offset

=head1 COPYRIGHT

Compatible with Time-Moment 0.46 by Christian Hansen.  The PerlOnJava
provider is backed by C<java.time>.

=cut
