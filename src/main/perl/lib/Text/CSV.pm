package Text::CSV;
use strict;
use warnings;

our $VERSION = '2.06';

use constant cacheKey => "_CSVFormat";

# NOTE: Core functionality is implemented in:
#       src/main/java/org/perlonjava/perlmodule/TextCsv.java

# Additional pure-Perl convenience methods

sub new {
    my $class = shift;
    my %args = @_ == 1 && ref $_[0] eq 'HASH' ? %{$_[0]} : @_;

    # Set default attributes
    my $self = {
        sep_char           => ',',
        quote_char         => '"',
        escape_char        => undef,
        binary             => 0,
        auto_diag          => 0,
        always_quote       => 0,
        eol                => undef,
        allow_loose_quotes => 0,
        allow_whitespace   => 0,
        blank_is_undef     => 0,
        empty_is_undef     => 0,
        quote_empty        => 0,
        quote_space        => 1,
        quote_binary       => 1,
        decode_utf8        => 1,
        keep_meta_info     => 0,
        strict             => 0,
        formula            => 'none',
        column_names       => [],

        # Clear error state
        _ERROR_CODE        => 0,
        _ERROR_STR         => '',
        _ERROR_POS         => 0,
        _ERROR_FIELD       => 0,

        %args
    };

    return bless $self, $class;
}

sub sep_char {
    my ($self, $sep) = @_;

    if (defined $sep) {
        die "sep_char must be a single character" unless length($sep) == 1;
        $self->{sep_char} = $sep;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{sep_char};
}

sub quote_char {
    my ($self, $quote) = @_;

    if (defined $quote) {
        die "quote_char must be a single character" unless length($quote) == 1;
        $self->{quote_char} = $quote;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{quote_char};
}

sub escape_char {
    my ($self, $escape) = @_;

    if (@_ > 1) {
        $self->{escape_char} = $escape;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{escape_char};
}

sub binary {
    my ($self, $binary) = @_;

    if (defined $binary) {
        $self->{binary} = $binary ? 1 : 0;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{binary};
}

sub auto_diag {
    my ($self, $auto_diag) = @_;

    if (defined $auto_diag) {
        $self->{auto_diag} = $auto_diag ? 1 : 0;
    }

    return $self->{auto_diag};
}

sub always_quote {
    my ($self, $always_quote) = @_;

    if (defined $always_quote) {
        $self->{always_quote} = $always_quote ? 1 : 0;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{always_quote};
}

sub eol {
    my ($self, $eol) = @_;

    if (@_ > 1) {
        $self->{eol} = $eol;
        delete $self->{+cacheKey}; # Invalidate cache if needed
    }

    return $self->{eol};
}

sub string {
    my $self = shift;
    return $self->{_string};
}

sub fields {
    my $self = shift;
    return @{$self->{_fields} || []};
}

# Add this method after the fields() method:
sub getline {
    my ($self, $fh) = @_;

    # Read a line from the filehandle
    my $line = <$fh>;

    return undef unless defined $line;

    # Parse the line
    if ($self->parse($line)) {
        return $self->fields;
    }

    return undef;
}

sub column_names {
    my ($self, @names) = @_;

    if (@names) {
        # Flatten array ref if provided (e.g., $csv->column_names(\@headers))
        @names = @{$names[0]} if (scalar(@names) == 1 && ref($names[0]) eq 'ARRAY');
        $self->{column_names} = \@names;
    }

    return @{$self->{column_names} || []};
}

sub getline_hr {
    my ($self, $fh) = @_;

    # Check if column names are set
    my $col_names = $self->{column_names};
    unless ($col_names && @$col_names) {
        $self->_set_error(3002, "getline_hr() called before column_names()", 0, 0);
        return undef;
    }

    # Get a line
    my $fields = $self->getline($fh);
    return undef unless $fields;

    # Convert to hash
    my %hash;
    for my $i (0 .. $#$col_names) {
        $hash{$col_names->[$i]} = $fields->[$i] // undef;
    }

    return \%hash;
}

sub error_diag {
    my $self = shift;

    unless (ref $self) {
        # Class method call - return last global error
        return "";
    }

    # Instance method call
    if (wantarray) {
        return (
            $self->{_ERROR_CODE} // 0,
            $self->{_ERROR_STR} // "",
            $self->{_ERROR_POS} // 0,
            0, # record number
            $self->{_ERROR_FIELD} // 0
        );
    }
    else {
        # Scalar context - return error string
        return $self->{_ERROR_STR} // "";
    }
}

sub _set_error {
    my ($self, $code, $message, $pos, $field) = @_;

    $self->{_ERROR_CODE} = $code;
    $self->{_ERROR_STR} = $message;
    $self->{_ERROR_POS} = $pos;
    $self->{_ERROR_FIELD} = $field;

    # Handle auto_diag
    if ($self->{auto_diag}) {
        warn "# CSV ERROR: $code - $message\n";
    }
}

sub _clear_error {
    my $self = shift;
    $self->{_ERROR_CODE} = 0;
    $self->{_ERROR_STR} = '';
    $self->{_ERROR_POS} = 0;
    $self->{_ERROR_FIELD} = 0;
}

sub print {
    my ($self, $fh, $fields) = @_;

    # Validate arguments
    return 0 unless defined $fh && ref($fields) eq 'ARRAY';

    # Combine fields into a CSV string
    my $status = $self->combine(@$fields);
    return 0 unless $status;

    # Add EOL if configured
    my $output = $self->string;
    $output .= $self->{eol} if defined $self->{eol};

    # Print to filehandle
    print $fh $output;

    return 1;
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
        for (1 .. $offset) {
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
    }
    elsif (ref $in || -f $in) {
        # File or filehandle
        my $fh;
        if (ref $in) {
            $fh = $in;
        }
        else {
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
    }
    else {
        return $csv->getline_all($fh);
    }
}

sub _write_csv {
    my ($csv, $out, $data, $headers) = @_;

    my $fh;
    if (ref $out eq 'SCALAR') {
        open $fh, '>', $out or die $!;
    }
    elsif (ref $out || $out) {
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
            $csv->print($fh, [ @{$row}{@cols} ]);
        }
        else {
            $csv->print($fh, $row);
        }
    }

    close $fh unless ref $out;
}

# Re-export constants
use constant {
    CSV_FLAGS_IS_QUOTED      => 0x0001,
    CSV_FLAGS_IS_BINARY      => 0x0002,
    CSV_FLAGS_ERROR_IN_FIELD => 0x0004,
    CSV_FLAGS_IS_MISSING     => 0x0010,
};

1;

__END__

=head1 NAME

Text::CSV - comma-separated values manipulator

=head1 SYNOPSIS

  use Text::CSV;

  my $csv = Text::CSV->new({ binary => 1 });

  # Parse CSV string
  if ($csv->parse($line)) {
      my @fields = $csv->fields();
  }

  # Combine fields into CSV
  if ($csv->combine(@fields)) {
      my $string = $csv->string();
  }

  # Read from file
  open my $fh, '<', 'file.csv' or die $!;
  while (my $row = $csv->getline($fh)) {
      # Process row
  }

  # Read with headers
  $csv->column_names($csv->getline($fh));
  while (my $hr = $csv->getline_hr($fh)) {
      print $hr->{column_name};
  }

=head1 DESCRIPTION

Text::CSV provides facilities for the composition and decomposition of
comma-separated values using Text::CSV compatible API.

This is a PerlOnJava implementation that uses Apache Commons CSV internally.

=head1 METHODS

=head2 new

Create a new Text::CSV object with optional attributes.

=head2 parse

Parse a CSV string into fields.

=head2 fields

Return the fields from the last successful parse.

=head2 combine

Combine fields into a CSV string.

=head2 string

Return the CSV string from the last successful combine.

=head2 getline

Read and parse a line from a filehandle.

=head2 getline_hr

Read and parse a line, returning a hashref using column names.

=head2 getline_all

Read all remaining lines from a filehandle.

=head2 print

Print fields as CSV to a filehandle.

=head2 say

Print fields as CSV to a filehandle with record separator.

=head2 column_names

Get or set column names for hash-based operations.

=head2 header

Read the first line and use it as column names.

=head2 error_diag

Get error information from the last operation.

=head2 csv

Function interface for simple CSV operations.

=head1 ATTRIBUTES

=head2 sep_char

Field separator character (default: ',')

=head2 quote_char

Quote character (default: '"')

=head2 escape_char

Escape character (default: undef)

=head2 binary

Allow binary characters (default: 0)

=head2 auto_diag

Automatic error diagnostics (default: 0)

=head2 always_quote

Always quote fields (default: 0)

=head2 eol

End of line string (default: undef)

=cut
