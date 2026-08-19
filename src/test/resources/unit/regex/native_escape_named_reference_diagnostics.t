use strict;
use warnings;
use Test::More;

for my $case (
    ['\c`', ' ', qr/^"\\c`" is more clearly written simply as "\\ "/],
    ['\c1', 'q', qr/^"\\c1" is more clearly written simply as "q"/],
) {
    my ($pattern, $subject, $expected) = @$case;
    my @warnings;
    my $compiled;
    {
        local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
        $compiled = qr/$pattern/;
    }
    like(join('', @warnings), $expected, "$pattern has the Perl clarity warning");
    ok($subject =~ $compiled, "$pattern retains its Perl character value");
}

for my $pattern (q{foo \k'n'}, q{foo \k<n>}, q{foo \k'_0_'}, q{foo \k<_0_>}) {
    eval { qr/$pattern/ };
    like($@, qr/^Reference to nonexistent named group/,
        "$pattern reports a nonexistent named group");
}

for my $pattern (q{foo \k'0'}, q{foo \k<12>}, q{foo \k'1a'}) {
    eval { qr/$pattern/ };
    like($@, qr/^Group name must start with a non-digit word character/,
        "$pattern rejects a digit-leading group name");
}

my $brace_backref = q{(?<as>as) (\w+) \k{ as } (\w+)};
ok('as easy as pie' =~ /$brace_backref/ && "$1-$2-$3" eq 'as-easy-pie',
    'brace named backreference trims surrounding whitespace');

my $angle_space = q{(?<as>as) (\w+) \k< as> (\w+)};
eval { qr/$angle_space/ };
like($@, qr/^Group name must start with a non-digit word character/,
    'angle named backreference rejects leading whitespace');

my $g_backref = q{(?'n'foo) \g{ n }};
ok('..foo foo..' =~ /$g_backref/ && $1 eq 'foo',
    'g-brace named backreference trims surrounding whitespace');

done_testing;
