#!/usr/bin/env perl
# Regression tests for nested eval STRING lexical scoping.
#
# In standard Perl, a string-eval's compile-time lexical scope includes every
# `my` visible at the call site — including variables declared inside an
# enclosing eval STRING. PerlOnJava's interpreter backend previously broke
# this for named subs defined inside nested evals: strict-vars fired at
# parse time, or (once parsing was fixed) the closure captured the wrong
# thing at runtime.
#
# See dev/design/nested-eval-string-lexicals.md.

use strict;
use warnings;
use Test::More;

# --- Case A: direct reference in nested eval --------------------------------
{
    my $r = eval q{
        my $x = 41;
        eval q{ $x + 1 };
    };
    is( $r, 42, 'direct scalar ref in nested eval' );
}

# --- Case B: named sub inside nested eval reads outer my scalar -------------
{
    my $r = eval q{
        my $y = 99;
        eval q{ sub ned_bar { return $y } 1 };
        die $@ if $@;
        ned_bar();
    };
    is( $r, 99, 'named sub in nested eval captures outer my scalar' );
}

# --- Case C: anonymous sub inside nested eval ------------------------------
{
    my $r = eval q{
        my $z = 77;
        my $code = eval q{ sub { return $z } };
        die $@ if $@;
        $code->();
    };
    is( $r, 77, 'anon sub in nested eval captures outer my scalar' );
}

# --- Case D: array subscript inside nested eval named sub -------------------
{
    my $r = eval q{
        my @countries = ('', 'JP', 'US');
        eval q{ sub ned_country_for { return $countries[$_[0]] } 1 };
        die $@ if $@;
        ned_country_for(1);
    };
    is( $r, 'JP', 'named sub in nested eval reads @countries[idx]' );
}

# --- Case E: three-deep nesting --------------------------------------------
{
    my $r = eval q{
        my $a = 10;
        eval q{
            my $b = 20;
            eval q{
                sub ned_three { return $a + $b }
                1;
            };
            die $@ if $@;
            ned_three();
        };
    };
    is( $r, 30, 'three-deep nested eval with named sub + 2 outer my vars' );
}

# --- Case F: plain compiled outer (not eval) still works --------------------
{
    my $u = 11;
    eval q{ sub ned_e { return $u } 1 };
    is( ned_e(), 11, 'single-eval named sub captures compiled outer my' );
}

# --- Case G: my declared and used inside same eval (no outer) ---------------
my $g = eval q{
    my $v = 22;
    sub ned_g { return $v }
    ned_g();
};
is( $g, 22, 'named sub inside single eval sees sibling my' );

# --- Case H: hash lookup in nested eval named sub ---------------------------
{
    my $r = eval q{
        my %map = ( a => 1, b => 2 );
        eval q{ sub ned_h { return $map{$_[0]} } 1 };
        die $@ if $@;
        ned_h('b');
    };
    is( $r, 2, 'named sub in nested eval reads %map{key}' );
}

done_testing();
