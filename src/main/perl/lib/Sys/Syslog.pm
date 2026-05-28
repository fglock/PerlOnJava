package Sys::Syslog;

use strict;
use warnings;
use Carp qw(croak);
use Exporter 'import';
use XSLoader ();

our $VERSION = '0.36';
our $AUTOLOAD;
our $facility = '';

our %EXPORT_TAGS = (
    standard => [qw(openlog syslog closelog setlogmask)],
    extended => [qw(setlogsock)],
    macros => [
        qw(
            LOG_ALERT LOG_CRIT LOG_DEBUG LOG_EMERG LOG_ERR
            LOG_INFO LOG_NOTICE LOG_WARNING
            LOG_AUTH LOG_AUTHPRIV LOG_CRON LOG_DAEMON LOG_FTP LOG_KERN
            LOG_LOCAL0 LOG_LOCAL1 LOG_LOCAL2 LOG_LOCAL3 LOG_LOCAL4
            LOG_LOCAL5 LOG_LOCAL6 LOG_LOCAL7 LOG_LPR LOG_MAIL LOG_NEWS
            LOG_SYSLOG LOG_USER LOG_UUCP
            LOG_INSTALL LOG_LAUNCHD LOG_NETINFO LOG_RAS LOG_REMOTEAUTH
            LOG_CONSOLE LOG_NTP LOG_SECURITY LOG_AUDIT LOG_LFMT
            LOG_CONS LOG_PID LOG_NDELAY LOG_NOWAIT LOG_ODELAY LOG_PERROR
            LOG_FACMASK LOG_NFACILITIES LOG_PRIMASK
            LOG_MASK LOG_UPTO
        )
    ],
);
$EXPORT_TAGS{DEFAULT} = $EXPORT_TAGS{standard};

our @EXPORT = @{ $EXPORT_TAGS{standard} };
our @EXPORT_OK = (
    @{ $EXPORT_TAGS{extended} },
    @{ $EXPORT_TAGS{macros} },
);

XSLoader::load('Sys::Syslog', $VERSION);

my $ident = '';
my $maskpri = LOG_UPTO(LOG_DEBUG());
my %options = (
    cons   => 0,
    ndelay => 0,
    noeol  => 0,
    nofatal => 0,
    nonul  => 0,
    nowait => 0,
    odelay => 0,
    perror => 0,
    pid    => 0,
);

sub AUTOLOAD {
    my $constname = $AUTOLOAD;
    $constname =~ s/.*:://;
    return if $constname eq 'DESTROY';
    croak "Sys::Syslog::constant() not defined" if $constname eq 'constant';

    my ($error, $value) = constant($constname);
    croak $error if $error;

    no strict 'refs';
    *$AUTOLOAD = sub () { $value };
    goto &$AUTOLOAD;
}

sub openlog {
    ($ident, my $logopt, $facility) = @_;

    $ident ||= _default_ident();
    $logopt ||= '';
    $facility ||= 'user';

    %options = map { $_ => 0 } keys %options;
    for my $opt (split /[,\s]+/, lc $logopt) {
        next unless length $opt;
        $options{$opt} = 1 if exists $options{$opt};
    }

    openlog_xs($ident, _numeric_options(), xlate($facility));
    return 1;
}

sub syslog {
    my ($priority, $format, @args) = @_;

    openlog() unless length $ident;
    croak 'syslog: expecting argument $priority' unless defined $priority;
    croak 'syslog: expecting argument $format' unless defined $format;

    my ($numpri, $numfac) = _priority_and_facility($priority);
    croak "syslog: invalid level/facility: $priority" if $numpri < 0;
    return 0 unless LOG_MASK($numpri) & $maskpri;

    $numfac = xlate($facility || 'user') if !defined $numfac;
    croak "syslog: invalid facility: $facility" if $numfac < 0;

    my $message = @args ? sprintf($format, @args) : $format;
    $message .= "\n" if !$options{noeol} && rindex($message, "\n") == -1;

    syslog_xs($numpri | $numfac, $message);
    return 1;
}

sub closelog {
    closelog_xs();
    %options = map { $_ => 0 } keys %options;
    $ident = '';
    $facility = '';
    return 1;
}

sub setlogmask {
    my $oldmask = $maskpri;
    $maskpri = $_[0] if @_ && $_[0] != 0;
    return $oldmask;
}

sub setlogsock {
    croak 'setlogsock(): Invalid number of arguments' unless @_ >= 1 && @_ <= 3;
    return 1;
}

sub xlate {
    my ($name) = @_;
    return $name + 0 if defined $name && $name =~ /^\s*\d+\s*$/;

    $name = uc($name || '');
    $name = 'ERR' if $name eq 'ERROR';
    $name = "LOG_$name" unless $name =~ /^LOG_/;

    my $value = constant($name);
    return -1 if !defined($value) || index($value, 'not a valid') >= 0;
    return $value;
}

sub _priority_and_facility {
    my ($priority) = @_;

    if ($priority =~ /^\d+$/) {
        my $pri = LOG_PRI($priority);
        my $fac = LOG_FAC($priority) << 3;
        return ($pri, $fac || undef);
    }

    my ($numpri, $numfac);
    for my $word (split /\W+/, $priority) {
        next unless length $word;
        my $value = xlate($word);
        return (-1, undef) if $value < 0;
        if ($value <= LOG_PRIMASK() && lc($word) ne 'kern') {
            croak "syslog: too many levels given: $word" if defined $numpri;
            $numpri = $value;
        }
        else {
            croak "syslog: too many facilities given: $word" if defined $numfac;
            $numfac = $value;
        }
    }

    croak 'syslog: level must be given' unless defined $numpri;
    return ($numpri, $numfac);
}

sub _numeric_options {
    my $value = 0;
    for my $opt (keys %options) {
        next unless $options{$opt};
        my $translated = xlate($opt);
        $value |= $translated if $translated >= 0;
    }
    return $value;
}

sub _default_ident {
    my $name = $0 || 'syslog';
    $name =~ s{.*[\\/]}{};
    return length($name) ? $name : 'syslog';
}

1;

__END__

=head1 NAME

Sys::Syslog - PerlOnJava syslog wrapper

=head1 DESCRIPTION

This module provides the standard Perl C<Sys::Syslog> load and export surface
on top of PerlOnJava's Java-backed XS implementation.

=cut
