package HTTP::Cookies::Netscape;

use strict;
use warnings;
use Carp ();

our $VERSION = '6.11';

require HTTP::Cookies;
our @ISA = qw(HTTP::Cookies);

sub load {
    my ($self, $file) = @_;
    $file ||= $self->{file} || return;

    local $/ = "\n";
    open my $fh, '<', $file or return;
    my $magic = <$fh>;
    chomp $magic if defined $magic;
    unless (defined $magic && $magic =~ /^#(?: Netscape)? HTTP Cookie File/) {
        warn "$file does not look like a netscape cookies file";
        return;
    }

    my $now = time() - $HTTP::Cookies::EPOCH_OFFSET;
    while (my $line = <$fh>) {
        chomp $line;
        $line =~ s/\s*\#HttpOnly_//;
        next if $line =~ /^\s*\#/;
        next if $line =~ /^\s*$/;
        $line =~ tr/\n\r//d;
        my ($domain, $bool, $path, $secure, $expires, $key, $val) =
            split /\t/, $line;
        $secure = defined($secure) && $secure eq 'TRUE';
        $self->set_cookie(undef, $key, $val, $path, $domain, undef, 0,
            $secure, $expires - $now, 0);
    }
    return 1;
}

sub save {
    my $self = shift;
    my %args = (
        file           => $self->{file},
        ignore_discard => $self->{ignore_discard},
        @_ == 1 ? (file => $_[0]) : @_,
    );
    Carp::croak('Unexpected argument to save method') if keys %args > 2;
    my $file = $args{file} || return;

    open my $fh, '>', $file or return;
    print {$fh} <<'EOT';
# Netscape HTTP Cookie File
# http://www.netscape.com/newsref/std/cookie_spec.html
# This is a generated file!  Do not edit.

EOT

    my $now = time() - $HTTP::Cookies::EPOCH_OFFSET;
    $self->scan(sub {
        my ($version, $key, $val, $path, $domain, $port, $path_spec,
            $secure, $expires, $discard) = @_;
        return if $discard && !$args{ignore_discard};
        $expires = $expires ? $expires - $HTTP::Cookies::EPOCH_OFFSET : 0;
        return if $now > $expires;
        $secure = $secure ? 'TRUE' : 'FALSE';
        my $bool = $domain =~ /^\./ ? 'TRUE' : 'FALSE';
        print {$fh} join("\t", $domain, $bool, $path, $secure,
            $expires, $key, $val), "\n";
    });
    return 1;
}

1;
