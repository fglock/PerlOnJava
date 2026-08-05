package Algorithm::ConsistentHash::Ketama;

use strict;
use Digest::MD5 qw(md5);
use Algorithm::ConsistentHash::Ketama::Bucket;

our $VERSION = '0.00012';
use constant HASHFUNC1 => 1;
use constant HASHFUNC2 => 2;
our $DEFAULT_HASHFUNC = HASHFUNC1;
our %VALID_HASHFUNCS = (HASHFUNC1 => 1, HASHFUNC2 => 1);

sub new {
    my ($class, %args) = @_;
    my $hf = $VALID_HASHFUNCS{$args{use_hashfunc}} ? $args{use_hashfunc} : $DEFAULT_HASHFUNC;
    return bless { hashfunc => $hf, buckets => [], continuum => undef }, $class;
}

sub add_bucket {
    my ($self, $label, $weight) = @_;
    push @{$self->{buckets}}, { label => $label, weight => $weight };
    $self->{continuum} = undef;
}

sub remove_bucket {
    my ($self, $label) = @_;
    @{$self->{buckets}} = grep { $_->{label} ne $label } @{$self->{buckets}};
    $self->{continuum} = undef;
}

sub buckets {
    my ($self) = @_;
    return map { Algorithm::ConsistentHash::Ketama::Bucket->new($_) } @{$self->{buckets}};
}

sub clone {
    my ($self) = @_;
    my $copy = bless {
        hashfunc => $self->{hashfunc},
        buckets => [ map { { %$_ } } @{$self->{buckets}} ],
        continuum => undef,
    }, ref $self;
    $copy->_continuum if $self->{continuum};
    return $copy;
}

sub _continuum {
    my ($self) = @_;
    return $self->{continuum} if $self->{continuum};
    my $total = 0; $total += $_->{weight} for @{$self->{buckets}};
    my @points;
    return ($self->{continuum} = \@points) unless $total;
    for my $bucket (@{$self->{buckets}}) {
        my $limit = int(($bucket->{weight} / $total) * 40 * @{$self->{buckets}});
        for my $k (0 .. $limit - 1) {
            my $digest = md5($bucket->{label} . "-$k");
            for my $h (0 .. 3) {
                my $offset = $h * 4;
                my @bytes = unpack('C4', substr($digest, $offset, 4));
                my $point = $bytes[0] | ($bytes[1] << 8)
                    | ($bytes[2] << 16) | ($bytes[3] << 24);
                push @points, [$point, $bucket->{label}];
            }
        }
    }
    @points = sort { $a->[0] <=> $b->[0] } @points;
    return ($self->{continuum} = \@points);
}

sub _hash_number {
    my ($value) = @_;
    my @bytes = unpack('C4', substr(md5($value), 0, 4));
    return $bytes[0] | ($bytes[1] << 8) | ($bytes[2] << 16) | ($bytes[3] << 24);
}

sub _label {
    my ($self, $number) = @_;
    my $points = $self->_continuum;
    return undef unless @$points;
    if ($self->{hashfunc} == HASHFUNC2) {
        my ($lo, $hi) = (0, scalar @$points);
        while ($lo < $hi) {
            my $mid = $lo + int(($hi - $lo) / 2);
            $points->[$mid][0] > $number ? ($hi = $mid) : ($lo = $mid + 1);
        }
        $lo = 0 if $lo >= @$points;
        return $points->[$lo][1];
    }
    # The legacy implementation selects the point immediately preceding the
    # hash (wrapping at the beginning); HASHFUNC2 selects the following point.
    my ($lo, $hi) = (0, scalar @$points);
    while ($lo < $hi) {
        my $mid = $lo + int(($hi - $lo) / 2);
        $points->[$mid][0] <= $number ? ($lo = $mid + 1) : ($hi = $mid);
    }
    $lo = @$points if $lo == 0;
    return $points->[$lo - 1][1];
}

sub hash {
    my ($self, $value) = @_;
    return $self->_label(_hash_number($value));
}

sub hash_with_hashnum {
    my ($self, $value) = @_;
    my $number = _hash_number($value);
    return ($self->_label($number), $number);
}

sub label_from_hashnum { $_[0]->_label($_[1]) }

1;
