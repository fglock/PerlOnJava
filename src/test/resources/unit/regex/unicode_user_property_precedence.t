use strict;
use warnings;
use utf8;
use Test::More;

our ($deferred, $deferred_class, $deferred_negated_class);
BEGIN {
    my $property = 'InLatin1';
    $deferred = qr/\p{$property}/;
    $deferred_class = qr/[\p{$property}]/;
    $deferred_negated_class = qr/[^\p{$property}]/;
}

our @calls;
sub InLatin1 {
    push @calls, $_[0] ? 'i' : 's';
    return $_[0] ? "0200\t0200\n" : "0100\t0100\n";
}

ok("\x{0100}" =~ $deferred,
    'forward user property matches its sensitive definition');
ok("\x{00ff}" !~ $deferred,
    'forward user property outranks the colliding built-in block');

my $fresh = eval q{qr/\p{InLatin1}/};
is($@, '', 'fresh colliding user property compiles');
ok("\x{0100}" =~ $fresh, 'fresh user property reuses its sensitive definition');
ok("\x{00ff}" !~ $fresh, 'fresh user property still outranks the block');

my $folded = eval q{qr/\p{InLatin1}/i};
is($@, '', 'fresh folded colliding user property compiles');
ok("\x{0200}" =~ $folded, 'folded mode uses its distinct user definition');
ok("\x{0100}" !~ $folded, 'folded mode does not reuse the sensitive cache');

ok("\x{0100}" =~ $deferred_class,
    'forward user property wins inside a standard class');
ok("\x{00ff}" !~ $deferred_class,
    'standard class does not retain the colliding built-in block');
ok("\x{0100}" !~ $deferred_negated_class,
    'negated standard class excludes the user-defined member');
ok("\x{00ff}" =~ $deferred_negated_class,
    'negated standard class includes the built-in-only member');

is_deeply(\@calls, ['s', 'i'],
    'each case mode resolves its user property once');

done_testing;
