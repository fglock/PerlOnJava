package Text::CSV;

use strict;
use Exporter;
use Carp ();
use vars qw( $VERSION $DEBUG @ISA @EXPORT_OK %EXPORT_TAGS );
@ISA = qw( Exporter );

BEGIN {
    $VERSION = '2.06';
    $DEBUG   = 0;
}

# if use CSV_XS, requires version
my $Module_XS  = 'Text::CSV_XS';
my $Module_PP  = 'Text::CSV_PP';
my $XS_Version = '1.60';

my $Is_Dynamic = 0;

my @PublicMethods = qw/
    version error_diag error_input
    known_attributes
    PV IV NV CSV_TYPE_PV CSV_TYPE_IV CSV_TYPE_NV
    CSV_FLAGS_IS_QUOTED CSV_FLAGS_IS_BINARY CSV_FLAGS_ERROR_IN_FIELD CSV_FLAGS_IS_MISSING
    /;

%EXPORT_TAGS = (
    CONSTANTS => [qw(
            CSV_FLAGS_IS_QUOTED
            CSV_FLAGS_IS_BINARY
            CSV_FLAGS_ERROR_IN_FIELD
            CSV_FLAGS_IS_MISSING
            CSV_TYPE_PV
            CSV_TYPE_IV
            CSV_TYPE_NV
    )],
);
@EXPORT_OK = (qw(csv PV IV NV), @{$EXPORT_TAGS{CONSTANTS}});

#

# Check the environment variable to decide worker module.

unless ($Text::CSV::Worker) {
    $Text::CSV::DEBUG and Carp::carp("Check used worker module...");

    if (exists $ENV{PERL_TEXT_CSV}) {
        if ($ENV{PERL_TEXT_CSV} eq '0' or $ENV{PERL_TEXT_CSV} eq 'Text::CSV_PP') {
            _load_pp() or Carp::croak $@;
        }
        elsif ($ENV{PERL_TEXT_CSV} eq '1' or $ENV{PERL_TEXT_CSV} =~ /Text::CSV_XS\s*,\s*Text::CSV_PP/) {
            _load_xs() or _load_pp() or Carp::croak $@;
        }
        elsif ($ENV{PERL_TEXT_CSV} eq '2' or $ENV{PERL_TEXT_CSV} eq 'Text::CSV_XS') {
            _load_xs() or Carp::croak $@;
        }
        else {
            Carp::croak "The value of environmental variable 'PERL_TEXT_CSV' is invalid.";
        }
    }
    else {
        _load_xs() or _load_pp() or Carp::croak $@;
    }

}

sub new { # normal mode
    my $proto = shift;
    my $class = ref($proto) || $proto;

    unless ($proto) { # for Text::CSV_XS/PP::new(0);
        return eval qq| $Text::CSV::Worker\::new( \$proto ) |;
    }

    #if (ref $_[0] and $_[0]->{module}) {
    #    Carp::croak("Can't set 'module' in non dynamic mode.");
    #}

    if (my $obj = $Text::CSV::Worker->new(@_)) {
        $obj->{_MODULE} = $Text::CSV::Worker;
        bless $obj, $class;
        return $obj;
    }
    else {
        return;
    }

}

sub csv {
    if (@_ && ref $_[0] eq __PACKAGE__ or ref $_[0] eq __PACKAGE__->backend) {
        splice @_, 0, 0, "csv";
    }
    my $backend = __PACKAGE__->backend;
    no strict 'refs';
    &{"$backend\::csv"}(@_);
}

sub require_xs_version { $XS_Version; }

sub module {
    my $proto = shift;
    return !ref($proto)          ? $Text::CSV::Worker
        : ref($proto->{_MODULE}) ? ref($proto->{_MODULE}) : $proto->{_MODULE};
}

*backend = *module;

sub is_xs {
    return $_[0]->module eq $Module_XS;
}

sub is_pp {
    return $_[0]->module eq $Module_PP;
}

sub is_dynamic { $Is_Dynamic; }

sub _load_xs { _load($Module_XS, $XS_Version) }

sub _load_pp { _load($Module_PP) }

sub _load {
    my ($module, $version) = @_;
    $version ||= '';

    $Text::CSV::DEBUG and Carp::carp "Load $module.";

    eval qq| use $module $version |;

    return if $@;

    push @Text::CSV::ISA, $module;
    $Text::CSV::Worker = $module;

    local $^W;
    no strict qw(refs);

    for my $method (@PublicMethods) {
        *{"Text::CSV::$method"} = \&{"$module\::$method"};
    }
    return 1;
}

1;
