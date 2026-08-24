use strict;
use warnings;
use utf8;
use Test::More;
no warnings qw(experimental::regex_sets experimental::uniprop_wildcards);

ok("A" =~ /\p{Block=Basic_Latin}/,
    'Block member matches outside a character class');
ok("\x{03c0}" !~ /\p{Block=Basic_Latin}/,
    'Block nonmember stays excluded');
ok("A" =~ /\p{Script=Latin}/,
    'Script member matches outside a character class');
ok("\x{30fc}" =~ /\p{Script_Extensions=Hiragana}/,
    'Script_Extensions includes a Common-script extension member');
ok("\x{20ac}" =~ /\p{Age=2.1}/,
    'Age exact value matches its introduced code point');
ok("\x{20ac}" =~ /\p{Present_In=3.0}/,
    'Present_In includes an earlier introduced code point');
ok("A" =~ /\p{Uppercase_Letter}/,
    'ordinary general-category alias remains usable');
ok("\x{a0}" =~ /\p{XPosixSpace}/,
    'ordinary Perl compatibility alias remains usable');

ok("A" =~ /[\p{Block=Basic_Latin}_]/,
    'Block remains native in a standard character class');
ok("\x{03c0}" !~ /[\p{Block=Basic_Latin}_]/,
    'standard class does not gain a Block nonmember');
ok("A" =~ /(?[ \p{Script=Latin} & \p{Block=Basic_Latin} ])/,
    'Script and Block remain native in an extended-class intersection');
ok("\x{30fc}" =~ /(?[ \p{Script_Extensions=Hiragana} - [A-Z] ])/,
    'Script_Extensions remains native in extended-class subtraction');
ok("\x{03c0}" =~ /\P{Block=Basic_Latin}/,
    'token-level property negation remains native');
ok("\x{03c0}" =~ /[^\p{Block=Basic_Latin}]/,
    'outer standard-class negation remains native');
ok("\x{03c0}" =~ /(?[ ! \p{Block=Basic_Latin} ])/,
    'extended-class unary complement remains native');

our ($deferred, $deferred_class, $extended_collision_error);
BEGIN {
    my $property = 'InGreek';
    $deferred = qr/\p{$property}/;
    $deferred_class = qr/[\p{$property}_]/;
    {
        no warnings 'experimental::regex_sets';
        $extended_collision_error = eval q{qr/(?[ \p{InGreek} + [_] ])/};
        $extended_collision_error = $@;
    }
}

our @calls;
sub InGreek {
    push @calls, $_[0] ? 'i' : 's';
    return "0600\n";
}

ok("\x{0600}" =~ $deferred && "\x{0370}" !~ $deferred,
    'deferred callback outranks colliding built-in outside a class');
ok("\x{0600}" =~ $deferred_class && "\x{0370}" !~ $deferred_class,
    'deferred callback outranks colliding built-in in a standard class');
like($extended_collision_error,
    qr/Unknown user-defined property name "InGreek"/,
    'forward extended-class collision remains fatal');
is_deeply(\@calls, ['s'],
    'deferred property is materialized once for its matching mode');

done_testing;
