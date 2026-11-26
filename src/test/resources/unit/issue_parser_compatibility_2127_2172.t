use strict;
use warnings;
use Test::More;

my $role_calls = 0;
sub role (&) { $role_calls++; $_[0]->() }
role { $role_calls += 10 };
is($role_calls, 11, 'role block syntax calls the prototyped function');

sub whitespace_args {
    my @args = @ '_;
    return scalar @args;
}
is(whitespace_args(1, 2), 2, 'whitespace before apostrophe separator in @_ is accepted');

no strict 'subs';
sub coerce { }
sub from (@) { }
sub via (&;@) { $_[0]->(); return @_ }
my $callback_calls = 0;
coerce X => from Int, via { $callback_calls++ } => from Str, via { $callback_calls += 10 };
is($callback_calls, 11, 'prototype callback leaves following fat-comma chain intact');

package Issue2158::Probe;
sub Thing { return Issue2158::Probe::Thing->create(@_) }
package Issue2158::Probe::Thing;
sub create { return bless { name => $_[1] }, $_[0] }
package main;
my $thing = Issue2158::Probe::Thing();
is(ref($thing), 'Issue2158::Probe::Thing', 'qualified class method is not recursive same-named sub call');

our @import_args;
sub Term::ReadLine::import { @main::import_args = @_ }
{
    no warnings 'uninitialized';
    import Term::ReadLine /new/;
}
ok(@import_args && $import_args[0] eq 'Term::ReadLine',
    'indirect import call accepts a slash-delimited expression');

{
    use feature 'lexical_subs';
    my sub socket ($) { $_[0] }
    my $value = 'socket DSL value';
    is(socket $value, $value,
        'lexically imported socket term takes precedence over the core builtin');
}

{
    package Issue2172::LexicalAttribute;
    our @dispatch;
    sub MODIFY_CODE_ATTRIBUTES {
        my ($package, $code, @attributes) = @_;
        push @dispatch, [map { (caller($_))[0] // '' } 0 .. 8];
        return grep { $_ ne 'Lexical' } @attributes;
    }
    sub marked : Lexical { 1 }
}
ok(@Issue2172::LexicalAttribute::dispatch
        && grep { $_ eq 'attributes' } @{$Issue2172::LexicalAttribute::dispatch[0]},
    'named code attribute dispatch preserves the attributes::import caller frame');

done_testing();
