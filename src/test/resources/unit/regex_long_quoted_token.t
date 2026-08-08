use strict;
use warnings;
use Test::More tests => 5;

my $plain = '"' . ('a' x 20000) . '"';
ok(
    $plain =~ /\A\s*+"((\\["\\]|[^"])*)"/,
    'long quoted token matches without consuming the Java stack',
);
is(length $1, 20000, 'long quoted token capture is complete');

my $escaped = '"a\\"b"';
ok($escaped =~ /\A\s*+"((\\["\\]|[^"])*)"/, 'escaped delimiter matches');
is($1, 'a\\"b', 'escaped delimiter remains in the capture');

my $unterminated = '"' . ('a' x 20000);
ok(
    $unterminated !~ /\A\s*+"((\\["\\]|[^"])*)"/,
    'unterminated long token fails without backtracking the Java stack',
);
