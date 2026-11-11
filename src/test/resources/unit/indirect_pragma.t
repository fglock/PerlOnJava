#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 15;

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    my $code = eval q{ no indirect; sub { new Local::Apple } };
    ok($code, 'no indirect compiles an indirect constructor in warning mode');
}
like($warning, qr/Indirect call of method "new" on object "Local::Apple"/s,
    'warning mode reports the indirect method and its source');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    my $code = eval q{ no indirect; { use indirect; sub { new Local::Pear } } };
    ok($code, 'use indirect disables the pragma in its lexical scope');
}
is($warning, '', 'disabled lexical scope emits no warning');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    my $code = eval q{ no indirect; sub { new { Local::Pear->new } } };
    ok($code, 'block invocants compile under warning mode');
}
like($warning, qr/Indirect call of method "new"/,
    'block invocants are reported as indirect calls');

my $hook_code = eval q{
    no indirect hook => sub { @main::indirect_hook = @_ };
    sub { new Local::Plum }
};
ok($hook_code, 'hook mode compiles');
is($main::indirect_hook[1], 'new', 'hook receives the indirect method name');
like($main::indirect_hook[0], qr/Local::Plum/,
    'hook receives the indirect invocant');
like($main::indirect_hook[2], qr/indirect_pragma\.t|eval/,
    'hook receives the source file');
like($main::indirect_hook[3], qr/^\d+$/,
    'hook receives the source line');

my $fatal = eval q{ no indirect 'fatal'; sub { new Local::Banana } };
ok(!$fatal && $@ =~ /Indirect call of method.*new/s,
    'fatal mode rejects an indirect call while compiling');

$warning = '';
my $global_setup = eval q{ no indirect 'global'; 1 };
ok($global_setup, 'global mode can be enabled');
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    my $code = eval q{ sub { new Local::Kiwi } };
    ok($code, 'global mode compiles indirect calls in later code');
}
like($warning, qr/Indirect call of method.*new/s,
    'global policy applies to a later compilation unit');
