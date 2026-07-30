# Copyright (c) 2015-2018 by Pali <pali@cpan.org>
#
# PerlOnJava compatibility implementation of the object and parsing surface
# used by Email::Sender.  The CPAN distribution's parser is implemented in XS.

package Email::Address::XS;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '1.05';
our @EXPORT_OK = qw(
    parse_email_addresses parse_email_groups
    format_email_addresses format_email_groups
    compose_address split_address
);

sub compose_address {
    my ($user, $host) = @_;
    return undef unless defined $user && defined $host;
    if ($user =~ /[\s()<>\[\]:;@\\,"]/) {
        $user =~ s/([\\"])/\\$1/g;
        $user = qq{"$user"};
    }
    return "$user\@$host";
}

sub split_address {
    my ($address) = @_;
    return (undef, undef) unless defined $address;
    $address =~ s/^\s+|\s+$//g;
    return (undef, undef)
        unless $address =~ /\A("(?:\\.|[^"])*"|[^@]+)\@([^@]+)\z/;
    my ($user, $host) = ($1, $2);
    if ($user =~ /\A"(.*)"\z/s) {
        $user = $1;
        $user =~ s/\\(.)/$1/g;
    }
    $host =~ s/^\s+|\s+$//g;
    return ($user, $host);
}

sub new {
    my ($class, @args) = @_;
    my %args;
    if (@args && @args % 2 == 0 && $args[0] =~ /\A(?:phrase|address|user|host|comment|copy)\z/) {
        %args = @args;
    } else {
        @args{qw(phrase address comment)} = @args;
    }
    if (my $copy = $args{copy}) {
        %args = map { $_ => $copy->{$_} } qw(phrase user host comment);
    }
    my $self = bless {}, $class;
    @{$self}{qw(phrase comment)} = @args{qw(phrase comment)};
    if (exists $args{address}) {
        $self->address($args{address});
    } else {
        @{$self}{qw(user host)} = @args{qw(user host)};
    }
    return $self;
}

sub parse_email_addresses {
    my ($string, $class) = @_;
    $class ||= __PACKAGE__;
    return () unless defined $string;
    my @parts;
    my ($part, $quoted, $angle) = ('', 0, 0);
    for my $char (split //, $string) {
        $quoted = !$quoted if $char eq '"' && $part !~ /\\\z/;
        $angle++ if !$quoted && $char eq '<';
        $angle-- if !$quoted && $char eq '>';
        if (!$quoted && !$angle && $char eq ',') {
            push @parts, $part;
            $part = '';
        } else {
            $part .= $char;
        }
    }
    push @parts, $part if length $part;

    my @objects;
    for (@parts) {
        s/^\s+|\s+$//g;
        next unless length;
        my ($phrase, $address);
        if (/\A(.*?)\s*<([^>]+)>/) {
            ($phrase, $address) = ($1, $2);
            $phrase =~ s/^\s+|\s+$//g;
            $phrase =~ s/\A"(.*)"\z/$1/;
        } else {
            ($address = $_) =~ s/\s*\([^)]*\)\s*\z//;
        }
        push @objects, $class->new(phrase => $phrase, address => $address);
    }
    return @objects;
}

sub parse_email_groups {
    my ($string, $class) = @_;
    return (undef, [parse_email_addresses($string, $class)]);
}

sub parse {
    my ($class, $string) = @_;
    my @objects = parse_email_addresses($string, $class);
    return @objects if wantarray;
    return @objects ? $objects[0] : $class->new;
}

sub parse_bare_address {
    my ($class, $string) = @_;
    return $class->new(address => $string);
}

sub format_email_addresses { join ', ', map { $_->format } @_ }
sub format_email_groups {
    my @args = @_;
    my @groups;
    while (@args) {
        my ($name, $addresses) = splice @args, 0, 2;
        my $formatted = format_email_addresses(@$addresses);
        push @groups, defined($name) ? "$name: $formatted;" : $formatted;
    }
    return join ', ', @groups;
}

sub format {
    my ($self) = @_;
    my $address = $self->address;
    return '' unless defined $address;
    my $result = $address;
    if (defined $self->{phrase} && length $self->{phrase}) {
        my $phrase = $self->{phrase};
        $phrase = qq{"$phrase"} if $phrase =~ /[",]/;
        $result = "$phrase <$address>";
    }
    $result .= " ($self->{comment})"
        if defined $self->{comment} && length $self->{comment};
    return $result;
}

sub is_valid {
    my ($self) = @_;
    return defined($self->{user}) && defined($self->{host}) && length($self->{host});
}

sub phrase  { $_[0]->{phrase}  = $_[1] if @_ > 1; $_[0]->{phrase} }
sub user    { $_[0]->{user}    = $_[1] if @_ > 1; $_[0]->{user} }
sub host    { $_[0]->{host}    = $_[1] if @_ > 1; $_[0]->{host} }
sub comment { $_[0]->{comment} = $_[1] if @_ > 1; $_[0]->{comment} }
sub name {
    my ($self) = @_;
    return $self->{phrase}  if defined $self->{phrase}  && length $self->{phrase};
    return $self->{comment} if defined $self->{comment} && length $self->{comment};
    return defined $self->{user} ? $self->{user} : '';
}
sub address {
    my ($self, $value) = @_;
    if (@_ > 1) {
        @{$self}{qw(user host)} = split_address($value);
    }
    return compose_address(@{$self}{qw(user host)});
}
sub as_string { $_[0]->format }
sub original { $_[0]->{original} }
sub is_obj { ref($_[1]) && eval { $_[1]->isa(__PACKAGE__) } }
sub purge_cache { }
sub disable_cache { }
sub enable_cache { }

1;
