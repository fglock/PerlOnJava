package Wanted;

use strict;
use warnings;
use Config ();
use Exporter 'import';

our $VERSION = '0.1.2';
our @EXPORT = qw(want rreturn lnoreturn);
our @EXPORT_OK = qw(context howmany wantref);

# Wanted's XS implementation also inspects the parent op tree for reference,
# assignment, boolean, and lvalue contexts.  Perl's caller() interface exposes
# scalar/list/void context, which covers the portable subset without XS.
sub _caller_gimme {
    my ($level) = @_;
    my @frame = caller($level + 1);
    return @frame ? $frame[5] : undef;
}

sub context {
    my $gimme = _caller_gimme(1);
    return 'VOID' unless defined $gimme;
    return $gimme ? 'LIST' : 'SCALAR';
}

sub wantref {
    return '';
}

sub howmany {
    my $gimme = _caller_gimme(1);
    return 0 unless defined $gimme;
    return undef if $gimme;
    return 1;
}

sub want {
    my @wanted = map { split } @_;
    my $gimme = _caller_gimme(1);

    for my $wanted (@wanted) {
        my $negative = $wanted =~ s/^!//;
        my $matches;

        if ($wanted eq 'VOID') {
            $matches = !defined $gimme;
        } elsif ($wanted eq 'LIST') {
            $matches = defined($gimme) && $gimme;
        } elsif ($wanted eq 'SCALAR') {
            $matches = defined($gimme) && !$gimme;
        } elsif ($wanted eq 'RVALUE') {
            $matches = 1;
        } elsif ($wanted eq 'COUNT') {
            return howmany();
        } elsif ($wanted =~ /^\d+$/) {
            my $count = defined($gimme) ? ($gimme ? undef : 1) : 0;
            $matches = !defined($count) || $count >= $wanted;
        } elsif (lc($wanted) eq 'infinity') {
            $matches = defined($gimme) && $gimme;
        } else {
            # Parent-op-only contexts (ASSIGN, LVALUE, BOOL and reference
            # types) cannot be inferred through caller().
            $matches = 0;
        }

        return 0 if $negative ? $matches : !$matches;
    }
    return 1;
}

sub rreturn(@) {
    return wantarray ? @_ : $_[-1];
}

our $LNORETURN_VALUE;
sub lnoreturn() :lvalue {
    $LNORETURN_VALUE = undef;
}

if ($Config::Config{archname} =~ /^java-/) {
    require XSLoader;
    XSLoader::load('Wanted', $VERSION);
}

1;
