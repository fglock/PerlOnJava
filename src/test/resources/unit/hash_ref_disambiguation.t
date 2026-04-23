use strict;
use warnings;
use Test::More tests => 14;

# Regression tests for block-vs-hashref disambiguation inside `{ ... }`.
# Real Perl treats `{ KEY, VALUE, ... }` as an anonymous hash when the
# first content token is a STRING or NUMBER literal (or fat-comma key)
# and a plain comma appears at depth 1.  Bare identifiers as the first
# token make it a BLOCK — `{ foo, 1 }` could be `foo()` called with
# `1` as arg.  Matching that behaviour also keeps our pre-parse scanner
# conservative w.r.t. string contents it can't see past.

# --- hashref forms ---

is(ref(sub { {"a","b"} }->()),            'HASH', '{"a","b"} is a hashref');
is(ref(sub { {1,2,3,4} }->()),            'HASH', '{1,2,3,4} is a hashref');
is(ref(sub { {"foo",1,"bar",2} }->()),    'HASH', '{"foo",1,"bar",2} is a hashref');
is(ref(sub { { a => 1 } }->()),           'HASH', 'fat-comma hash still works');
is(ref(sub { +{"a","b"} }->()),           'HASH', 'explicit +{} still works');
is(ref(sub { {} }->()),                   'HASH', 'empty {} still a hashref');

# --- content is preserved ---

{
    my $h = sub { {"a","1","b","2"} }->();
    is_deeply($h, {a=>1, b=>2}, '{"a","1","b","2"} content');
}

# --- block forms are not mis-classified ---

my @m = map { $_ + 1 } (1..3);
is_deeply(\@m, [2,3,4], 'map { $_ + 1 } still a block');

my @g = grep { $_ > 1 } (1..3);
is_deeply(\@g, [2,3], 'grep { $_ > 1 } still a block');

my @s = sort { $a <=> $b } (3,1,2);
is_deeply(\@s, [1,2,3], 'sort { $a <=> $b } still a block');

my $d = do { 5 + 6 };
is($d, 11, 'do { ... } still a block');

my $e = eval { 7 * 8 };
is($e, 56, 'eval { ... } still a block');

my $r = sub { return { k => 1 } }->();
is(ref($r), 'HASH', 'return {...} still a hashref');

# Block with a leading keyword and comma expression
my @x = do { "foo", "bar" };
is_deeply(\@x, ["foo","bar"], 'do { "foo", "bar" } is a block (list context)');