package Archive::Zip::MemberRead;

use strict;
use warnings;

use Archive::Zip qw(:ERROR_CODES);

our $VERSION = '1.68';

my $nl = $^O eq 'MSWin32' ? "\r\n" : "\n";
my $DEFAULT_BUFFER_SIZE = 32768;

sub Archive::Zip::Member::readFileHandle {
    return Archive::Zip::MemberRead->new(shift);
}

sub new {
    my ($class, $zip, $file) = @_;
    my $member;

    if ($zip && $file) {
        $member = ref($file) ? $file : $zip->memberNamed($file);
    }
    elsif ($zip && ref($zip)) {
        $member = $zip;
    }
    else {
        die 'Archive::Zip::MemberRead::new needs a zip and filename, zip and member, or member';
    }

    my $self = bless {}, $class;
    $self->set_member($member);
    return $self;
}

sub set_member {
    my ($self, $member) = @_;
    $self->{member} = $member;
    $self->{contents} = defined $member ? $member->contents : '';
    $self->rewind;
}

sub setLineEnd {
    shift;
    $nl = shift;
}

sub rewind {
    my $self = shift;
    $self->{offset} = 0;
    $self->{line_no} = 0;
    $self->{at_end} = 0;
    delete $self->{buffer};
}

sub input_record_separator {
    my $self = shift;
    if (@_) {
        $self->{sep} = shift;
        $self->{sep_re} = _sep_as_re($self->{sep});
    }
    return exists $self->{sep} ? $self->{sep} : $/;
}

sub _sep_re {
    my $self = shift;
    return exists $self->{sep} ? $self->{sep_re} : _sep_as_re($/);
}

sub _sep_as_re {
    my $sep = shift;
    if (defined $sep) {
        if ($sep eq '') {
            return "(?:$nl){2,}";
        }
        else {
            $sep =~ s/\n/$nl/og;
            return quotemeta $sep;
        }
    }
    return undef;
}

sub input_line_number {
    my $self = shift;
    return $self->{line_no};
}

sub close {
    my $self = shift;
    $self->rewind;
}

sub buffer_size {
    my ($self, $size) = @_;
    if (!$size) {
        return $self->{chunkSize} || $DEFAULT_BUFFER_SIZE;
    }
    $self->{chunkSize} = $size;
}

sub getline {
    my ($self, $argref) = @_;

    my $size = $self->buffer_size;
    my $sep = $self->_sep_re;

    my $preserve_line_ending;
    if (ref $argref eq 'HASH') {
        $preserve_line_ending = $argref->{preserve_line_ending};
        $sep =~ s/\\([^A-Za-z_0-9])+/$1/g if defined $sep;
    }

    while (1) {
        if (defined($sep) && defined($self->{buffer}) && $self->{buffer} =~ s/^(.*?)$sep//s) {
            my $line = $1;
            $self->{line_no}++;
            return $preserve_line_ending ? $line . $sep : $line;
        }
        elsif ($self->{at_end}) {
            $self->{line_no}++ if $self->{buffer};
            return delete $self->{buffer};
        }

        my $chunk;
        my $read = $self->read($chunk, $size);
        die 'ERROR: Error reading chunk from archive' unless defined $read;
        $self->{at_end} = !$read;
        $self->{buffer} .= $chunk if $read;
    }
}

sub read {
    my $self = $_[0];
    my $size = $_[2];

    return undef unless defined $self->{contents};
    $size = 0 unless defined $size;

    my $offset = $self->{offset} || 0;
    my $available = length($self->{contents}) - $offset;
    if ($available <= 0 || $size <= 0) {
        $_[1] = '';
        return 0;
    }

    my $bytes = $available < $size ? $available : $size;
    $_[1] = substr($self->{contents}, $offset, $bytes);
    $self->{offset} = $offset + $bytes;
    return length($_[1]);
}

1;
