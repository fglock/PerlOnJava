use strict;
use warnings;
use re ();
use Test::More;

my $exact = re::optimization(qr/abc/);
is(ref($exact), 'HASH', 'optimization returns a hash reference');
is($exact->{minlen}, 3, 'exact minimum length');
is($exact->{minlenret}, 3, 'returned minimum length');
is($exact->{anchored}, 'abc', 'fixed-offset exact string is anchored');
is($exact->{'anchored min offset'}, 0, 'anchored offset');
is($exact->{checking}, 'anchored', 'anchored string is checked');
is($exact->{isall}, 1, 'plain exact pattern is entirely optimized');
is(re::optimization(qr/a()bc/)->{isall}, 0,
    'capture op prevents an all-exact optimization');

my $floating = re::optimization(qr/x?abc/);
is($floating->{minlen}, 3, 'optional prefix does not raise minimum length');
is($floating->{floating}, 'abc', 'variable-offset exact string is floating');
is($floating->{'floating min offset'}, 0, 'floating minimum offset');
is($floating->{'floating max offset'}, 1, 'floating maximum offset');
is($floating->{checking}, 'floating', 'floating string is checked');

my $variable = re::optimization(qr/a(b){2,3}c/);
is($variable->{anchored}, 'abb', 'variable repetition retains anchored prefix');
is($variable->{floating}, 'bbc', 'variable repetition reports floating tail');
is($variable->{'floating min offset'}, 1,
    'variable repetition reports floating tail offset');
is($variable->{checking}, 'floating', 'floating tail is checked');

my $sbol = re::optimization(
    qr/^(?:mat1|mat2|(?:mat3|mat4)|mat5|(?:mat6|mat7))$/);
is($sbol->{'anchor SBOL'}, 1, 'beginning anchor is reported as SBOL');

for my $casefold ('', 'i') {
    my $trie_tail = re::optimization(eval "qr/(?:(?:cat|dog|fish)|bird)x/$casefold");
    is($trie_tail->{floating}, 'x',
        "casefold trie expansion preserves its trailing floating literal /$casefold");
}

my $nested_trie_tail = re::optimization(
    qr/(?:(?:(?:a|b)|(?:c|d))|(?:(?:e|f)|(?:g|h)))z/);
is($nested_trie_tail->{anchored}, 'z',
    'nested single-character trie preserves its fixed-offset tail');
is($nested_trie_tail->{'anchored min offset'}, 1,
    'fixed-offset tail reports its mandatory prefix length');
ok(!defined $nested_trie_tail->{floating},
    'fixed-offset tail is not reported as floating');

my $nested_two_char_trie_tail = re::optimization(
    qr/(?:(?:(?:aa|ab|ac)|(?:ba|bb|bc))|(?:(?:ca|cb|cc)|(?:da|db|dc)))e/);
is($nested_two_char_trie_tail->{anchored}, 'e',
    'nested two-character trie preserves its fixed-offset tail');
is($nested_two_char_trie_tail->{'anchored min offset'}, 2,
    'two-character trie reports its mandatory prefix length');
ok(!defined $nested_two_char_trie_tail->{floating},
    'two-character fixed-offset tail is not reported as floating');

my $empty = re::optimization(qr//);
is($empty->{minlen}, 0, 'empty pattern minimum length');
is($empty->{checking}, 'none', 'empty pattern has no exact check');
ok(!defined(re::optimization('abc')), 'non-regex input returns undef');

done_testing;
