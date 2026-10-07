package Tie::File;

use strict;
use warnings;
use Tie::Array;

our @ISA = qw(Tie::Array);
our $VERSION = '1.09';

sub TIEARRAY {
    my ($class, $target, %options) = @_;
    my $fh = _is_filehandle($target) ? $target : undef;
    my $self = bless {
        filename => $fh ? undef : $target,
        fh       => $fh,
        recsep   => exists $options{recsep} ? $options{recsep} : $/,
        records  => [],
        trailing => 0,
    }, $class;
    $self->_load;
    return $self;
}

sub _is_filehandle {
    my ($target) = @_;
    return 0 unless defined $target;
    return 1 if ref $target;
    return eval { defined fileno($target) } ? 1 : 0;
}

sub _load {
    my ($self) = @_;
    if (my $fh = $self->{fh}) {
        my $pos = tell($fh);
        seek($fh, 0, 0) or return;
        local $/;
        my $content = <$fh>;
        $content = '' unless defined $content;
        seek($fh, $pos, 0) if defined $pos && $pos >= 0;
        return $self->_load_content($content);
    }

    my $filename = $self->{filename};
    return unless defined $filename && -e $filename;

    open my $fh, '<', $filename or return;
    local $/;
    my $content = <$fh>;
    close $fh;
    $content = '' unless defined $content;

    $self->_load_content($content);
}

sub _load_content {
    my ($self, $content) = @_;
    my $sep = defined $self->{recsep} ? $self->{recsep} : "\n";
    if ($sep eq '') {
        $self->{records} = length($content) ? [ $content ] : [];
        $self->{trailing} = 0;
        return;
    }

    $self->{trailing} = length($content) >= length($sep)
        && substr($content, -length($sep)) eq $sep
        ? 1 : 0;
    my @records = split /\Q$sep\E/, $content, -1;
    pop @records if $self->{trailing};
    $self->{records} = \@records;
}

sub _flush {
    my ($self) = @_;
    if (my $fh = $self->{fh}) {
        seek($fh, 0, 0) or return;
        my $sep = defined $self->{recsep} ? $self->{recsep} : "\n";
        print {$fh} join $sep, @{ $self->{records} } or return;
        print {$fh} $sep if $self->{trailing} && @{ $self->{records} };
        my $end = tell($fh);
        truncate($fh, $end) if defined $end && $end >= 0;
        seek($fh, $end, 0) if defined $end && $end >= 0;
        return;
    }

    my $filename = $self->{filename};
    return unless defined $filename;

    open my $fh, '>', $filename or return;
    my $sep = defined $self->{recsep} ? $self->{recsep} : "\n";
    print {$fh} join $sep, @{ $self->{records} };
    print {$fh} $sep if $self->{trailing} && @{ $self->{records} };
    close $fh;
}

sub FETCHSIZE { scalar @{ $_[0]{records} } }

sub STORESIZE {
    my ($self, $size) = @_;
    $#{ $self->{records} } = $size - 1;
    $self->_flush;
}

sub FETCH { $_[0]{records}[ $_[1] ] }

sub STORE {
    my ($self, $index, $value) = @_;
    $self->{records}[$index] = $value;
    $self->_flush;
}

sub CLEAR {
    my ($self) = @_;
    @{ $self->{records} } = ();
    $self->{trailing} = 0;
    $self->_flush;
}

sub PUSH {
    my $self = shift;
    push @{ $self->{records} }, @_;
    $self->_flush;
    return $self->FETCHSIZE;
}

sub POP {
    my ($self) = @_;
    my $value = pop @{ $self->{records} };
    $self->_flush;
    return $value;
}

sub SHIFT {
    my ($self) = @_;
    my $value = shift @{ $self->{records} };
    $self->_flush;
    return $value;
}

sub UNSHIFT {
    my $self = shift;
    unshift @{ $self->{records} }, @_;
    $self->_flush;
    return $self->FETCHSIZE;
}

sub SPLICE {
    my $self = shift;
    my @removed;
    if (@_ == 0) {
        @removed = splice @{ $self->{records} };
    }
    elsif (@_ == 1) {
        my $offset = shift;
        @removed = splice @{ $self->{records} }, $offset;
    }
    else {
        my $offset = shift;
        my $length = shift;
        @removed = splice @{ $self->{records} }, $offset, $length, @_;
    }
    $self->_flush;
    return wantarray ? @removed : $removed[-1];
}

sub EXISTS { exists $_[0]{records}[ $_[1] ] }

sub DELETE {
    my ($self, $index) = @_;
    my $value = delete $self->{records}[$index];
    $self->_flush;
    return $value;
}

sub UNTIE { $_[0]->_flush }
sub DESTROY { $_[0]->_flush }

1;
