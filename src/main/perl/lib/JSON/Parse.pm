# Pure-Perl compatibility implementation for the XS-backed JSON::Parse.
#
# When a CPAN-installed JSON::Parse calls XSLoader, PerlOnJava evaluates this
# file from jar:PERL5LIB.  Keep the public API in Perl and delegate JSON
# decoding to the bundled JSON::PP implementation.

package JSON::Parse;

use strict;
use warnings;
use Carp qw(carp croak);
use JSON::PP ();
require Exporter;

our $VERSION = '0.62';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    assert_valid_json json_file_to_perl json_to_perl parse_json parse_json_safe
    read_json valid_json validate_json
);
our %EXPORT_TAGS = (all => \@EXPORT_OK);
our $null;

sub _decoder {
    my ($self) = @_;
    my $decoder = JSON::PP->new->allow_nonref(1);
    $decoder->max_depth($self ? $self->{max_depth} : 10_000);
    return $decoder;
}

sub _check_input {
    my ($json) = @_;
    croak 'JSON error: empty input' unless defined $json && $json =~ /\S/;
}

sub _replace_literals {
    my ($self, $value) = @_;
    if (ref($value) eq 'ARRAY') {
        for my $i (0 .. $#$value) {
            $value->[$i] = _replace_literals($self, $value->[$i]);
        }
    }
    elsif (ref($value) eq 'HASH') {
        for my $key (keys %$value) {
            $value->{$key} = _replace_literals($self, $value->{$key});
        }
    }
    elsif (!defined $value) {
        return exists $self->{null} ? $self->{null} : undef;
    }
    elsif (JSON::PP::is_bool($value)) {
        return $value ? (exists $self->{true} ? $self->{true} : 1)
                      : (exists $self->{false} ? $self->{false} : 0);
    }
    return $value;
}

# Detect repeated object keys before JSON::PP coalesces them.  This is a small
# JSON lexer, used only when the caller explicitly requests this JSON::Parse
# feature; decoding and validation remain JSON::PP's responsibility.
sub _check_collisions {
    my ($json) = @_;
    my @objects;
    my $i = 0;
    while ($i < length $json) {
        my $char = substr($json, $i, 1);
        if ($char eq '{') {
            push @objects, {};
            ++$i;
            next;
        }
        if ($char eq '}') {
            pop @objects;
            ++$i;
            next;
        }
        if ($char ne '"') {
            ++$i;
            next;
        }
        my $start = $i++;
        my $escaped = 0;
        while ($i < length $json) {
            my $next = substr($json, $i++, 1);
            if ($escaped) { $escaped = 0; next; }
            if ($next eq '\\') { $escaped = 1; next; }
            last if $next eq '"';
        }
        my $quoted = substr($json, $start, $i - $start);
        my $after = $i;
        ++$after while $after < length($json) && substr($json, $after, 1) =~ /\s/;
        next unless $after < length($json) && substr($json, $after, 1) eq ':' && @objects;
        my $key = eval { JSON::PP->new->decode($quoted) };
        next if $@;
        croak qq(JSON error: Name is not unique: "$key") if $objects[-1]{$key}++;
    }
}

sub new {
    my ($class) = @_;
    return bless {
        max_depth => 10_000,
        copy_literals => 0,
        detect_collisions => 0,
        diagnostics_hash => 0,
        warn_only => 0,
        upgrade_utf8 => 0,
    }, $class;
}

sub run_internal {
    my ($self, $json) = @_;
    _check_input($json);
    _check_collisions($json) if $self->{detect_collisions};
    my $value = _decoder($self)->decode($json);
    return _replace_literals($self, $value);
}

sub run {
    my ($self, $json) = @_;
    if ($self->{warn_only}) {
        my $value = eval { $self->run_internal($json) };
        warn $@ if $@;
        return $value;
    }
    return $self->run_internal($json);
}

sub parse { goto &run }

sub parse_json {
    my ($json) = @_;
    return __PACKAGE__->new->run_internal($json);
}

sub parse_json_safer {
    my ($json) = @_;
    my $parser = __PACKAGE__->new;
    $parser->{copy_literals} = 1;
    $parser->{detect_collisions} = 1;
    return $parser->run_internal($json);
}

sub parse_json_safe {
    my $value = eval { parse_json_safer(@_) };
    if ($@) {
        my $error = $@;
        $error =~ s/\s+at\s+\S+\s+line\s+\d+\.\s*\z//;
        carp "JSON::Parse::parse_json_safe: $error";
        return undef;
    }
    return $value;
}

sub assert_valid_json { parse_json($_[0]); return }

sub check { $_[0]->run_internal($_[1]); return }

sub valid_json {
    return 0 unless defined $_[0] && $_[0] =~ /\S/;
    return eval { assert_valid_json($_[0]); 1 } || 0;
}

sub read_file {
    my ($file) = @_;
    croak "File does not exist: '$file'" unless -f $file;
    open my $fh, '<:encoding(UTF-8)', $file or croak "Error opening $file: $!";
    local $/;
    my $json = <$fh>;
    close $fh or croak $!;
    return $json;
}

sub read_json { return parse_json(read_file($_[0])) }
sub json_to_perl { goto &parse_json }
sub validate_json { goto &assert_valid_json }
sub json_file_to_perl { goto &read_json }
sub read { return $_[0]->run(read_file($_[1])) }

sub copy_literals { $_[0]->{copy_literals} = $_[1] ? 1 : 0; return }
sub detect_collisions { $_[0]->{detect_collisions} = $_[1] ? 1 : 0; return }
sub diagnostics_hash { $_[0]->{diagnostics_hash} = $_[1] ? 1 : 0; return }
sub warn_only { $_[0]->{warn_only} = $_[1] ? 1 : 0; return }
sub get_warn_only { return $_[0]->{warn_only} ? 1 : 0 }
sub upgrade_utf8 { $_[0]->{upgrade_utf8} = $_[1] ? 1 : 0; return }
sub set_max_depth {
    croak "Invalid max depth $_[1]" if $_[1] < 0;
    $_[0]->{max_depth} = $_[1] || 10_000;
    return;
}
sub get_max_depth { return $_[0]->{max_depth} }
sub set_true { $_[0]->{true} = $_[1]; return }
sub set_false { $_[0]->{false} = $_[1]; return }
sub set_null { $_[0]->{null} = $_[1]; return }
sub delete_true { delete $_[0]->{true}; return }
sub delete_false { delete $_[0]->{false}; return }
sub delete_null { delete $_[0]->{null}; return }
sub no_warn_literals { return }

1;
