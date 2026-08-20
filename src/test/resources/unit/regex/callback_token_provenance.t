use strict;
use warnings;
use Test::More;

our ($genuine, $ordinary, $dynamic);
our $raw_call = '(?{=CALL:0})';
our $raw_dynamic = '(?{=DYNAMIC:0})';
our $raw_ordinary = '(?{ ++$main::ordinary })';
our $raw_dynamic_perl = '(??{ ++$main::dynamic; "A" })';

sub reset_counts { ($genuine, $ordinary, $dynamic) = (0, 0, 0) }
sub counts { [$genuine, $ordinary, $dynamic] }

sub rejected_without_re_eval {
    my ($label, $operation) = @_;
    reset_counts();
    my $ok = eval { $operation->(); 1 };
    my $error = $@;
    ok(!$ok, "$label is rejected without use re eval");
    like($error, qr/^Eval-group not allowed at runtime, use re 'eval'/,
        "$label reports runtime eval-group authorization diagnostic");
    is_deeply(counts(), [0, 0, 0], "$label executes no callback before rejection");
}

rejected_without_re_eval('raw CALL token beside genuine callback',
    sub { "" =~ /(?{ ++$main::genuine })$raw_call/ });
rejected_without_re_eval('raw DYNAMIC token beside genuine callback',
    sub { "" =~ /(?{ ++$main::genuine })$raw_dynamic/ });
rejected_without_re_eval('two raw tokens beside genuine callback',
    sub { "" =~ /(?{ ++$main::genuine })$raw_call$raw_dynamic/ });
rejected_without_re_eval('ordinary interpolated callback',
    sub { "" =~ /(?{ ++$main::genuine })$raw_ordinary/ });
rejected_without_re_eval('ordinary interpolated dynamic expression',
    sub { "A" =~ /(?{ ++$main::genuine })$raw_dynamic_perl/ });

{
    use re 'eval';

    for my $case (
        ['raw CALL token', sub { "" =~ /(?{ ++$main::genuine })$raw_call/ }],
        ['raw DYNAMIC token', sub { "" =~ /(?{ ++$main::genuine })$raw_dynamic/ }],
    ) {
        reset_counts();
        my $ok = eval { $case->[1]->(); 1 };
        my $error = $@;
        ok(!$ok, "$case->[0] remains invalid Perl source with use re eval");
        like($error, qr/^syntax error at \(eval \d+\) line 1, near "/,
            "$case->[0] reports the Perl source syntax error");
        is_deeply(counts(), [0, 0, 0], "$case->[0] executes no callback on compile failure");
    }

    reset_counts();
    my $ok = eval { "" =~ /(?{ ++$main::genuine })$raw_call$raw_dynamic/; 1 };
    my $error = $@;
    ok(!$ok, 'two raw tokens remain invalid Perl source with use re eval');
    my @syntax_errors = $error =~ /^syntax error at \(eval \d+\) line 1, near "/mg;
    cmp_ok(scalar @syntax_errors, '>=', 1,
        'raw markers retain a Perl source syntax diagnostic');
    is_deeply(counts(), [0, 0, 0], 'two raw markers execute no callbacks on compile failure');

    reset_counts();
    my $matched;
    $ok = eval { $matched = "" =~ /(?{ ++$main::genuine })$raw_ordinary/; 1 };
    is($@, '', 'ordinary interpolated callback compiles with use re eval');
    ok($ok, 'ordinary interpolated callback executes without exception');
    ok($matched, 'ordinary interpolated callback pattern matches');
    is_deeply(counts(), [1, 1, 0], 'genuine and interpolated callbacks each execute once');

    reset_counts();
    $ok = eval { $matched = "A" =~ /(?{ ++$main::genuine })$raw_dynamic_perl/; 1 };
    is($@, '', 'ordinary interpolated dynamic expression compiles with use re eval');
    ok($ok, 'ordinary interpolated dynamic expression executes without exception');
    ok($matched, 'ordinary interpolated dynamic expression returns a matching program');
    is_deeply(counts(), [1, 0, 1], 'genuine and dynamic callbacks each execute once');

    reset_counts();
    my $left = '(?{ ++$main::ordinary })';
    my $right = '(?{ $main::ordinary += 10 })';
    $ok = eval { $matched = "" =~ /(?{ ++$main::genuine })$left$right/; 1 };
    is($@, '', 'multiple ordinary callback interpolations compile with use re eval');
    ok($ok, 'multiple ordinary callback interpolations execute without exception');
    ok($matched, 'multiple ordinary callback interpolations match');
    is_deeply(counts(), [1, 11, 0], 'multiple interpolations execute in pattern order');
}

done_testing;
