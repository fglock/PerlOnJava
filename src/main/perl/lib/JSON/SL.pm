package JSON::SL;

use strict;
use warnings;
use JSON::PP ();
use Exporter 'import';

our $VERSION = '1.0.7';
our @EXPORT_OK = qw(decode_json unescape_json_string);

sub decode_json {
    my $value = eval { JSON::PP::decode_json($_[0]) };
    if ($@) {
        my $error = $@;
        $error = "incomplete JSON input: $error" if $error =~ /unexpected end of string/i;
        die $error;
    }
    return _convert_booleans($value);
}

sub _convert_booleans {
    my ($value) = @_;
    if (ref($value) eq 'HASH') {
        $value->{$_} = _convert_booleans($value->{$_}) for keys %$value;
    } elsif (ref($value) eq 'ARRAY') {
        $_ = _convert_booleans($_) for @$value;
    } elsif (JSON::PP::is_bool($value)) {
        my $boolean = $value ? 1 : 0;
        return bless \$boolean, 'JSON::SL::Boolean';
    }
    return $value;
}

sub unescape_json_string {
    my ($text) = @_;
    return JSON::PP::decode_json('"' . $text . '"');
}

sub new {
    my ($class, $max_levels) = @_;
    return bless {
        max_levels => defined($max_levels) ? $max_levels : 512,
        input => '', results => [], pointers => undef, root => undef,
        options => { utf8 => 0, noqstr => 0, nopath => 0, max_size => 0, object_drip => 0 },
        emitted => {},
    }, $class;
}

sub set_jsonpointer {
    my ($self, $paths) = @_;
    die 'set_jsonpointer expects an array reference' unless ref($paths) eq 'ARRAY';
    die 'set_jsonpointer can only be called before feed' if length $self->{input};
    $self->{pointers} = [@$paths];
    return $self;
}

sub feed {
    my ($self, $input) = @_;
    $self->{input} .= defined($input) ? $input : '';
    my $limit = $self->{options}{max_size};
    die 'JSON text exceeds maximum size' if $limit && length($self->{input}) > $limit;

    while (1) {
        $self->{input} =~ s/^[\s,]+//;
        last unless length $self->{input};
        my ($value, $offset);
        my $ok = eval {
            ($value, $offset) = JSON::PP->new->allow_nonref->decode_prefix($self->{input});
            1;
        };
        if ($ok && defined($offset) && $offset > 0) {
            $value = _convert_booleans($value);
            $self->{root} = $value;
            $self->{options}{source_text} = $self->{input};
            $self->{root_callback}->($value) if $self->{root_callback};
            for my $result (@{$self->_select_results($value)}) {
                my $key = ($result->{JSONPointer} // '') . "\0" . ($result->{Path} // '');
                next if $self->{emitted}{$key}++;
                push @{$self->{results}}, $result;
            }
            substr($self->{input}, 0, $offset, '');
            next;
        }
        if (!$ok) {
            my $partial = _closed_prefix($self->{input});
            if (defined $partial) {
                my $partial_root = eval { JSON::PP::decode_json($partial) };
                if (!$@) {
                    $partial_root = _convert_booleans($partial_root);
                    $self->{root} = $partial_root;
                    $self->{options}{source_text} = $self->{input};
                    for my $result (@{$self->_select_results($partial_root)}) {
                        my $key = ($result->{JSONPointer} // '') . "\0" . ($result->{Path} // '');
                        next if $self->{emitted}{$key}++;
                        push @{$self->{results}}, $result;
                        _remove_path($partial_root, $result->{Path}) if defined $result->{Path};
                    }
                    return $self->_take_results if @{$self->{results}};
                }
            }
        }
        die $@ if !$ok && _looks_complete($self->{input});
        last;
    }
    return $self->_take_results;
}

sub _closed_prefix {
    my ($text) = @_;
    my ($quoted, $escaped) = (0, 0);
    my @close;
    for my $char (split //, $text) {
        if ($quoted) {
            if ($escaped) { $escaped = 0; next }
            if ($char eq '\\') { $escaped = 1; next }
            $quoted = 0 if $char eq '"';
            next;
        }
        if ($char eq '"') { $quoted = 1 }
        elsif ($char eq '{') { push @close, '}' }
        elsif ($char eq '[') { push @close, ']' }
        elsif (($char eq '}' || $char eq ']') && @close) { pop @close }
    }
    return undef if $quoted || !@close;
    $text =~ s/[,\s]+$//;
    return $text . join('', reverse @close);
}

sub _remove_path {
    my ($root, $path) = @_;
    my @parts = grep { length } split m{/}, $path;
    my $last = pop @parts;
    my $parent = $root;
    for my $part (@parts) {
        $parent = ref($parent) eq 'ARRAY' ? $parent->[$part] : $parent->{$part};
        return unless ref $parent;
    }
    if (ref($parent) eq 'ARRAY') { $parent->[$last] = undef }
    elsif (ref($parent) eq 'HASH') { delete $parent->{$last} }
}

sub _looks_complete {
    my ($text) = @_;
    my ($depth, $quoted, $escaped) = (0, 0, 0);
    for my $char (split //, $text) {
        if ($quoted) {
            if ($escaped) { $escaped = 0; next }
            if ($char eq '\\') { $escaped = 1; next }
            $quoted = 0 if $char eq '"';
            next;
        }
        if ($char eq '"') { $quoted = 1 }
        elsif ($char eq '{' || $char eq '[') { $depth++ }
        elsif ($char eq '}' || $char eq ']') { $depth-- }
    }
    return !$quoted && $depth <= 0;
}

sub incr_parse { goto &feed }

sub fetch {
    my ($self) = @_;
    return $self->_take_results;
}

sub _take_results {
    my ($self) = @_;
    return wantarray ? splice(@{$self->{results}}) : shift @{$self->{results}};
}

sub _select_results {
    my ($self, $root) = @_;
    return [$root] unless $self->{pointers};
    my @results;
    for my $query (@{$self->{pointers}}) {
        my @parts = grep { length } split m{/}, $query;
        _walk_pointer($root, \@parts, '', $query, \@results, $self->{options});
    }
    return \@results;
}

sub _walk_pointer {
    my ($value, $parts, $path, $query, $results, $options) = @_;
    if (!@$parts) {
        push @$results, {
            Value => $value,
            ($options->{noqstr} ? () : (JSONPointer => $query)),
            ($options->{nopath} ? () : (Path => $path)),
        };
        return;
    }
    my $part = shift @$parts;
    if (ref($value) eq 'HASH') {
        my @keys = $part eq '^'
            ? sort { index($options->{source_text} // '', '"' . $a . '"')
                    <=> index($options->{source_text} // '', '"' . $b . '"') } keys %$value
            : ($part);
        for my $key (@keys) {
            next unless exists $value->{$key};
            _walk_pointer($value->{$key}, [@$parts], "$path/$key", $query, $results, $options);
        }
    } elsif (ref($value) eq 'ARRAY') {
        my @indices = $part eq '^' ? (0 .. $#$value) : ($part);
        for my $index (@indices) {
            next unless $index =~ /^\d+$/ && $index <= $#$value;
            _walk_pointer($value->[$index], [@$parts], "$path/$index", $query, $results, $options);
        }
    }
}

sub root { $_[0]{root} }
sub reset {
    my ($self) = @_;
    $self->{input} = '';
    $self->{results} = [];
    $self->{root} = undef;
    $self->{emitted} = {};
    return $self;
}
sub CLONE_SKIP { 1 }

sub root_callback {
    my ($self, @callback) = @_;
    my $previous = $self->{root_callback};
    if (@callback) {
        die 'root_callback expects a code reference or undef'
            if defined($callback[0]) && ref($callback[0]) ne 'CODE';
        $self->{root_callback} = $callback[0];
    }
    return $previous;
}

for my $option (qw(utf8 noqstr nopath max_size object_drip)) {
    no strict 'refs';
    *{$option} = sub {
        my ($self, @value) = @_;
        $self->{options}{$option} = $value[0] if @value;
        return $self->{options}{$option};
    };
}

package JSON::SL::Boolean;
use overload '0+' => sub { ${$_[0]} }, 'bool' => sub { ${$_[0]} }, fallback => 1;

1;

__END__

=head1 NAME

JSON::SL - JSON::PP-backed compatibility facade for JSON::SL 1.0.7

=head1 DESCRIPTION

This bundled compatibility facade reuses JSON::PP for decoding and JSON
Pointer selection. C<feed> can deliver matches from completed subtrees before
the enclosing document ends, but it does not provide JSON::SL's native
incremental parser or Tuba callback API.

=cut
