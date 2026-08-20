use strict;
use warnings;
use Test::More;

sub compile_error {
    my ($source) = @_;
    local $@;
    eval $source;
    return $@;
}

like(
    compile_error("qr'a" . chr(92)),
    qr/^Search pattern not terminated/,
    'unterminated apostrophe-delimited pattern is diagnosed',
);
like(
    compile_error(q{qr/abc)/}),
    qr/^Unmatched \) in regex/,
    'unmatched closing parenthesis is diagnosed',
);

for my $reference ('\\g-1', '\\g{-1}', '\\g{ -1 }') {
    like(
        compile_error("qr/$reference/"),
        qr/^Reference to nonexistent or unclosed group in regex/,
        "unclosed relative group reference $reference is diagnosed",
    );
}

for my $reference ('\\g0', '\\g-0', '\\g{0}', '\\g{ 0 }', '\\g{-0}', '\\g{ -0 }') {
    like(
        compile_error("qr/$reference/"),
        qr/^Reference to invalid group 0 in regex/,
        "group zero reference $reference is diagnosed",
    );
}

like(
    compile_error(q{qr/(?(1?)a|b)/}),
    qr/^Switch condition not recognized in regex/,
    'malformed conditional switch is diagnosed',
);
like(
    compile_error(q{qr/(?(1)a|b|c)/}),
    qr/^Switch \(\?\(condition\)\.\.\. contains too many branches in regex/,
    'conditional switch with three branches is diagnosed',
);

my @permissive_posix = (
    [qr/([[:]+)/,  'a:[b]:', ':[' ],
    [qr/([[=]+)/,  'a=[b]=', '=[' ],
    [qr/([[.]+)/,  'a.[b].', '.[' ],
    [qr/[a[:]b[:c]/, 'abc',  'abc'],
);
for my $case (@permissive_posix) {
    my ($regex, $subject, $expected) = @$case;
    ok($subject =~ /$regex/, 'incomplete POSIX-looking syntax remains a valid pattern');
    is($&, $expected, 'incomplete POSIX-looking syntax preserves Perl match semantics');
}

my @warning;
my $reversed;
{
    local $SIG{__WARN__} = sub { push @warning, @_ };
    $reversed = eval q{qr/((def){37,17})?ABC/};
}
ok('ABC' =~ /$reversed/, 'reversed quantifier bounds compile as an impossible optional group');
like(
    join('', @warning),
    qr/^Quantifier \{n,m\} with n > m can't match in regex/,
    'reversed quantifier bounds emit Perl warning',
);

my @suppressed_warning;
{
    no warnings 'regexp';
    local $SIG{__WARN__} = sub { push @suppressed_warning, @_ };
    qr/((def){37,17})?ABC/;
}
is(scalar(@suppressed_warning), 0,
    q{no warnings 'regexp' suppresses the reversed-bounds warning});

my $nonfatal = eval q{
    use warnings FATAL => 'all';
    no warnings 'regexp';
    qr/((def){37,17})?ABC/;
    1;
};
ok($nonfatal, q{no warnings 'regexp' overrides fatal warnings for reversed bounds});

my $outer_nonfatal;
{
    use warnings FATAL => 'all';
    no warnings 'regexp';
    $outer_nonfatal = eval q{ qr/((def){37,17})?ABC/; 1 };
}
ok($outer_nonfatal,
    q{eval STRING inherits the caller's no warnings 'regexp' scope});

my ($misc_error, $syntax_error);
{
    use warnings FATAL => 'all';
    no warnings 'regexp';
    eval q{ qr/[A\EB]/ };
    $misc_error = $@;
    eval q{ qr/\c1/ };
    $syntax_error = $@;
}
like($misc_error, qr/^Useless use of \\E/,
    q{no warnings 'regexp' retains fatal misc regex diagnostics});
like($syntax_error, qr/^"\\c1" is more clearly written simply as "q"/,
    q{no warnings 'regexp' retains fatal syntax regex diagnostics});

my ($match_misc_error, $match_syntax_error);
{
    use warnings FATAL => 'all';
    no warnings 'regexp';
    eval q{ 'ABc' =~ m/[A\EB]c/ };
    $match_misc_error = $@;
    eval q{ 'q' =~ m/\c1/ };
    $match_syntax_error = $@;
}
like($match_misc_error, qr/^Useless use of \\E/,
    q{match use retains fatal misc diagnostics when regexp warnings are off});
like($match_syntax_error, qr/^"\\c1" is more clearly written simply as "q"/,
    q{match use retains fatal syntax diagnostics when regexp warnings are off});

like(
    compile_error(q{qr/abc\N{def}/}),
    qr/^Unknown charname 'def'/,
    'unknown named character is diagnosed at regex compilation',
);

done_testing;
