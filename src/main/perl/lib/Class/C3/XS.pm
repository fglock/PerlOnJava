package Class::C3::XS;

# PerlOnJava: pure-Perl shim for the XS-only Class::C3::XS module.
#
# Class::C3::XS provides XS speedups for Class::C3. PerlOnJava can't
# load XS, but core mro (always available on perl >= 5.9.5) provides
# equivalent functionality, so we implement the four functions that
# Class::C3::XS exposes as pure-Perl wrappers over core mro and the
# symbol table. The dist's own lib/Class/C3/XS.pm sits earlier in
# @INC, calls XSLoader::load("Class::C3::XS") in its body, and then
# defines next::can / next::method / maybe::next::method on top of
# Class::C3::XS::_nextcan. PerlOnJava's XSLoader intercepts the load
# call, evals this file to install the four subs, and returns success
# so the dist's pm finishes wiring up next::*.

use strict;
use warnings;
use mro;

our $VERSION = '0.15';

# Class::C3::XS::calculateMRO($class) -- returns the C3 linearisation
# as a list. Trivially backed by core mro.
sub calculateMRO {
    my ($class) = @_;
    return @{ mro::get_linear_isa($class, 'c3') };
}

# Class::C3::XS::_plsubgen() -- returns a method-cache generation
# counter. Real Perl exposes PL_sub_generation, which is bumped any
# time a sub is (re-)defined. Class::C3 only consults this value to
# detect when its cached method-dispatch tables need invalidation, so
# returning a strictly increasing integer is sufficient.
{
    my $gen = 0;
    sub _plsubgen { ++$gen }
}

# Class::C3::XS::_calculate_method_dispatch_table($class, $merge_cache)
# -- Class::C3 only calls this on a perl that lacks core c3 mro. With
# core mro available (PerlOnJava reports 5.40+), c3 dispatch is handled
# by the interpreter itself, so this can be a no-op. This matches the
# early-return in Class::C3 0.34's pure-Perl
# _calculate_method_dispatch_table when $C3_IN_CORE is true.
sub _calculate_method_dispatch_table {
    return;
}

# Class::C3::XS::_nextcan($self_or_class, $wantsub)
#
# Locate the next method in C3 MRO order, starting from the method
# that is currently executing (identified via caller()). Returns a
# code ref, or undef when nothing is found and $wantsub is false.
# When $wantsub is true and nothing is found, croaks with the same
# wording the XS implementation uses, which several CPAN test suites
# match against.
sub _nextcan {
    my ($self, $wantsub) = @_;
    my $class = ref($self) || $self;

    # Walk up the call stack past the next:: / maybe::next:: dispatch
    # subs (and any user shim that goto'd into them) to the real
    # method we are "next-ing" out of.
    my $level = 1;
    my $caller_sub;
    while (1) {
        my @c = caller($level);
        last unless @c;
        my $sub = $c[3];
        if (defined $sub
            && $sub ne '(eval)'
            && $sub !~ /^(?:next|maybe::next)::/)
        {
            $caller_sub = $sub;
            last;
        }
        $level++;
    }

    unless (defined $caller_sub) {
        return undef unless $wantsub;
        require Carp;
        Carp::croak("Can't determine calling sub for next::method on $class");
    }

    my ($caller_pkg, $method) = $caller_sub =~ /^(.+)::([^:]+)$/;
    unless (defined $method) {
        return undef unless $wantsub;
        require Carp;
        Carp::croak("Can't extract method name from $caller_sub");
    }

    no strict 'refs';
    my $found = 0;
    for my $pkg (@{ mro::get_linear_isa($class) }) {
        if (!$found) {
            $found = 1 if $pkg eq $caller_pkg;
            next;
        }
        if (defined &{"${pkg}::${method}"}) {
            return \&{"${pkg}::${method}"};
        }
    }

    return undef unless $wantsub;
    require Carp;
    Carp::croak("No next::method '$method' found for $class");
}

# Mirror the next::* / maybe::next::* dispatch subs the dist's
# lib/Class/C3/XS.pm installs after XSLoader::load returns. PerlOnJava's
# MakeMaker shim skips installing the dist's .pm (it's superseded by the
# bundled jar shim), so we have to define these ourselves. Real Perl's
# next::method / next::can are also re-defined here in regular Perl;
# replacing them is the whole point of loading Class::C3::XS, and tests
# such as t/36_next_goto.t rely on `goto &next::can` resolving to a real
# coderef, which the core implementation does not provide.
package # hide me from PAUSE
    next;

sub can { Class::C3::XS::_nextcan($_[0], 0) }

sub method {
    my $method = Class::C3::XS::_nextcan($_[0], 1);
    goto &$method;
}

package # hide me from PAUSE
    maybe::next;

sub method {
    my $method = Class::C3::XS::_nextcan($_[0], 0);
    goto &$method if defined $method;
    return;
}

package Class::C3::XS;

1;
