use strict;
use warnings;
use Test::More tests => 8;

package RuntimeCodePhase8b;

sub value ($) { "first:$_[0]" }

package main;

is(prototype('RuntimeCodePhase8b::value'), '$',
    'named sub exposes its installed prototype');
is(RuntimeCodePhase8b::value('one'), 'first:one',
    'qualified direct call resolves the installed code slot');
ok(exists &RuntimeCodePhase8b::value,
    'exists observes the installed code slot');
ok(defined &RuntimeCodePhase8b::value,
    'defined observes the installed subroutine');

{
    no warnings qw(redefine prototype);
    *RuntimeCodePhase8b::value = sub ($$) { "second:$_[0]:$_[1]" };
}

is(prototype('RuntimeCodePhase8b::value'), '$$',
    'glob assignment replaces the code slot prototype');
is(eval q{ RuntimeCodePhase8b::value('two', 'args') }, 'second:two:args',
    'qualified call observes the replacement code slot');

undef &RuntimeCodePhase8b::value;
ok(exists &RuntimeCodePhase8b::value,
    'undef leaves a declared code slot');
ok(!defined &RuntimeCodePhase8b::value,
    'undef clears the installed subroutine body');
