use strict;
use warnings;
use Test::More;

my $evaluations = 0;
ok('ab' =~ /^(??{ ++$evaluations; 'ab|a' })b$/,
    'nested alternatives backtrack into the outer suffix');
is($evaluations, 1, 'one dynamic entry retains its nested alternatives');

my $repeat_count = 0;
ok('aa' =~ /^(?:(??{ ++$repeat_count; 'a' })){2}$/,
    'a quantified dynamic program executes at each entry');
is($repeat_count, 2, 'quantified dynamic expression is reevaluated');

my ($seen_capture, $entry_pos);
ok('abc' =~ /^(a)(??{ $seen_capture = $1; $entry_pos = pos; '(b)' })(c)$/,
    'dynamic program sees provisional state and may contain captures');
is($seen_capture, 'a', 'dynamic expression sees the preceding outer capture');
is($entry_pos, 1, 'pos reports the dynamic entry offset');
is($1, 'a', 'outer capture before the dynamic program is preserved');
is($2, 'c', 'nested captures do not consume outer capture numbers');

my $returned_qr = qr/b/i;
ok('aB' =~ /^a(??{ $returned_qr })$/,
    'dynamic expression accepts a compiled qr value');
ok(!('aB' =~ /^a(??{ qr{b} })$/i),
    'returned qr retains its own modifiers');
ok('aB' =~ /^a(??{ 'b' })$/i,
    'returned string inherits outer modifiers');

my $seed;
ok('seed' =~ /^(?{ $seed = 'seed' })(??{ $^R })$/,
    'dynamic expression sees the prior callback result');
is($^R, 'seed', 'successful dynamic execution preserves callback result');

my $failed_effects = 0;
ok(!('x' =~ /^(??{ ++$failed_effects; 'y' })$/),
    'a returned program may fail normally');
is($failed_effects, 1, 'ordinary dynamic side effects survive failure');

my $unreached = 0;
ok('x' =~ /^x|y(??{ ++$unreached; 'z' })$/,
    'an earlier branch can bypass a dynamic program');
is($unreached, 0, 'an unreached dynamic expression is not executed');

my $exception_ok = eval { 'x' =~ /^(??{ die "dynamic boom\n" })$/; 1 };
ok(!$exception_ok, 'dynamic exception aborts matching');
is($@, "dynamic boom\n", 'dynamic exception propagates unchanged');

{
    no warnings 'uninitialized';
    ok('a' =~ /^a(??{ undef })$/, 'undef dynamic result is an empty pattern');
}
ok('a' =~ /^a(??{ '' })$/, 'empty dynamic result is an empty pattern');

my $wide = "\x{100}";
ok($wide =~ /^(??{ $wide })$/u, 'Unicode dynamic source preserves its scalar');
{
    use bytes;
    my $octet = "\xE9";
    ok($octet =~ /^(??{ $octet })$/,
        'byte-mode dynamic source preserves its octet provenance');
}

my $recursive;
$recursive = qr{ \( (?: [^()]+ | (??{ $recursive }) )* \) }x;
ok('(a(b)c)' =~ /^$recursive$/,
    'self-referential dynamic qr matches nested input');
ok(!('(a(b)' =~ /^$recursive$/),
    'self-referential dynamic qr still rejects unbalanced input');

done_testing;
