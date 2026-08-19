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

    my $ascii_strict_dynamic = qr/^(??{"s"})$/iaa;
    ok("\x{17F}" !~ $ascii_strict_dynamic,
        'dynamic source observes the enclosing aa modifier');
}

my @literal_patterns = (
    [q|(?#( (?{1+)a|,       'a',       'comment text is not executable source'],
    [q|ab[(?{1]|,            'ab1',     'character-class text is not executable source'],
    [q|ab[(?{1\](?{2]|,     'ab2',     'escaped class close keeps text non-executable'],
    [q|ab[c\](??{"]d|,     'abcd',    'class text after an escaped bracket is non-executable'],
);

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
