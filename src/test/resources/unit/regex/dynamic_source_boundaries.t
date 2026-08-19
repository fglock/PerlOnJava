use strict;
use warnings;
use Test::More;

{
    use re 'eval';

    ok('x' =~ /(??{})/,
        'an undefined dynamic pattern result is an empty pattern');

    my $subject = 'snofooefoofoowaa';
    ok($subject =~ /(?<n>foo)(??{ $+{n} })/,
        'dynamic source can use a named capture from its prefix');
    is($+{n}, 'foo', 'dynamic named-capture source preserves the capture value');

    ok($subject =~ /(?P<n>foo)(??{ $+{n} })/,
        'dynamic source accepts Python-style named capture syntax');
    is($+{n}, 'foo', 'Python-style named capture feeds dynamic source');

    ok($subject =~ /(?'n'foo)(??{ $+{n} })/,
        'dynamic source accepts apostrophe named capture syntax');
    is($+{n}, 'foo', 'apostrophe named capture feeds dynamic source');

}

my @literal_patterns = (
    [q|(?#( (?{1+)a|,       'a',       'comment text is not executable source'],
    [q|ab[(?{1]|,            'ab1',     'character-class text is not executable source'],
    [q|ab[(?{1\](?{2]|,     'ab2',     'escaped class close keeps text non-executable'],
    [q|ab[c\](??{"]d|,     'abcd',    'class text after an escaped bracket is non-executable'],
);

ok('a' =~ m'(?#( (?{1+)a',
    'apostrophe-delimited comment text stays inside the regex literal');
ok('a' =~ m'a# (?{1+'x,
    'extended-mode line comments hide callback-looking text');
ok('ab1' =~ m'ab[(?{1]',
    'character-class callback-looking text stays inside the regex literal');
ok('ab2' =~ m'ab[(?{1\](?{2]',
    'escaped class close keeps callback-looking text inside the class');
ok("ab\\;c" =~ m'ab\\[(??{1;})]c',
    'apostrophe delimiter handles callback-looking text after a literal bracket');
ok('abcd' =~ m'ab[c\](??{"]d',
    'apostrophe delimiter handles callback-looking text after an escaped bracket');

{
    use re 'eval';

    for my $case (@literal_patterns) {
        my ($pattern, $subject, $description) = @{$case};
        my $regex = eval { qr/$pattern/ };
        ok(defined($regex) && $subject =~ $regex, $description)
            or diag($@);
    }
}

done_testing;
