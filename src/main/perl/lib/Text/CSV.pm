package Text::CSV;
use strict;
use warnings;

our $VERSION = '2.06';

# NOTE: Core functionality is implemented in:
#       src/main/java/org/perlonjava/perlmodule/TextCsv.java

# Additional pure-Perl convenience methods

sub new {
    my $class = shift;
    my %args = @_ == 1 && ref $_[0] eq 'HASH' ? %{$_[0]} : @_;

    # Set default attributes
    my $self = {
        sep_char            => ',',
        quote_char          => '"',
        escape_char         => undef,
        binary              => 0,
        auto_diag           => 0,
        always_quote        => 0,
        eol                 => undef,
        allow_loose_quotes  => 0,
        allow_whitespace    => 0,
        blank_is_undef      => 0,
        empty_is_undef       => 0,
        quote_empty         => 0,
        quote_space         => 1,
        quote_binary        => 1,
        decode_utf8        => 1,
        keep_meta_info      => 0,
        strict              => 0,
        formula             => 'none',
        column_names        => [],

        # Clear error state
        _ERROR_CODE         => 0,
        _ERROR_STR          => '',
        _ERROR_POS          => 0,
        _ERROR_FIELD        => 0,

        %args
    };

    return bless $self, $class;
}

sub sep_char {
    my ($self, $sep) = @_;

    if (defined $sep) {
        die "sep_char must be a single character" unless length($sep) == 1;
        $self->{sep_char} = $sep;
        delete $self->{cacheKey};  # Invalidate cache if needed
    }

    return $self->{sep_char};
}

sub quote_char {
    my ($self, $quote) = @_;

    if (defined $quote) {
        die "quote_char must be a single character" unless length($quote) == 1;
        $self->{quote_char} = $quote;
        delete $self->{cacheKey};  # Invalidate cache if needed
    }

    return $self->{quote_char};
}

sub column_names {
    my ($self, @names) = @_;

    if (@names) {
        # Flatten array ref if provided (e.g., $csv->column_names(\@headers))
        @names = @{ $names[0] } if (scalar(@names) == 1 && ref($names[0]) eq 'ARRAY');
        $self->{column_names} = \@names;
    }

    return @{ $self->{column_names} || [] };
}

sub say {
    my ($self, $fh, $fields) = @_;

    # Save current eol setting
    my $saved_eol = $self->eol;

    # Set eol to $/ if not defined
    $self->eol($/) unless defined $saved_eol;

    # Print the fields
    my $result = $self->print($fh, $fields);

    # Restore eol setting
    $self->eol($saved_eol);

    return $result;
}

sub getline_all {
    my ($self, $fh, $offset, $length) = @_;
    my @rows;

    # Handle offset
    if (defined $offset && $offset > 0) {
        for (1..$offset) {
            last unless $self->getline($fh);
        }
    }

    # Read rows
    my $count = 0;
    while (my $row = $self->getline($fh)) {
        push @rows, $row;
        $count++;
        last if defined $length && $count >= $length;
    }

    return \@rows;
}

sub header {
    my ($self, $fh, $opts) = @_;
    $opts ||= {};

    # Read first line
    my $row = $self->getline($fh);
    return unless $row;

    # Set column names
    $self->column_names(@$row);

    # Return column names in list context
    return @$row if wantarray;

    # Return self in scalar context
    return $self;
}

sub csv {
    # Function interface implementation
    my %opts = @_;

    my $in = delete $opts{in} or die "csv: missing 'in' parameter";
    my $out = delete $opts{out};
    my $headers = delete $opts{headers};

    # Create CSV object
    my $csv = Text::CSV->new(\%opts) or die Text::CSV->error_diag;

    # Handle input
    my $data;
    if (ref $in eq 'SCALAR') {
        # Parse string
        open my $fh, '<', $in or die $!;
        $data = _read_csv($csv, $fh, $headers);
        close $fh;
    } elsif (ref $in || -f $in) {
        # File or filehandle
        my $fh;
        if (ref $in) {
            $fh = $in;
        } else {
            open $fh, '<', $in or die "$in: $!";
        }
        $data = _read_csv($csv, $fh, $headers);
        close $fh unless ref $in;
    }

    # Handle output
    if ($out) {
        _write_csv($csv, $out, $data, $headers);
    }

    return $data;
}

sub _read_csv {
    my ($csv, $fh, $headers) = @_;

    if ($headers && $headers eq 'auto') {
        $csv->header($fh);
        my @rows;
        while (my $row = $csv->getline_hr($fh)) {
            push @rows, $row;
        }
        return \@rows;
    } else {
        return $csv->getline_all($fh);
    }
}

sub _write_csv {
    my ($csv, $out, $data, $headers) = @_;

    my $fh;
    if (ref $out eq 'SCALAR') {
        open $fh, '>', $out or die $!;
    } elsif (ref $out || $out) {
        $fh = ref $out ? $out : do {
            open my $fh, '>', $out or die "$out: $!";
            $fh;
        };
    }

    # Write header if needed
    if ($headers && ref $data eq 'ARRAY' && @$data && ref $data->[0] eq 'HASH') {
        my @cols = $csv->column_names;
        @cols = keys %{$data->[0]} unless @cols;
        $csv->print($fh, \@cols);
    }

    # Write data
    for my $row (@$data) {
        if (ref $row eq 'HASH') {
            my @cols = $csv->column_names;
            $csv->print($fh, [@{$row}{@cols}]);
        } else {
            $csv->print($fh, $row);
        }
    }

    close $fh unless ref $out;
}

# Re-export constants
use constant {
    CSV_FLAGS_IS_QUOTED => 0x0001,
    CSV_FLAGS_IS_BINARY => 0x0002,
    CSV_FLAGS_ERROR_IN_FIELD => 0x0004,
    CSV_FLAGS_IS_MISSING => 0x0010,
};

1;

__END__

=head1 NAME

Text::CSV - comma-separated values manipulator

=head1 DESCRIPTION

Text::CSV provides facilities for the composition and decomposition of
comma-separated values using Text::CSV compatible API.

This is a PerlOnJava implementation that uses Apache Commons CSV internally.

=cut