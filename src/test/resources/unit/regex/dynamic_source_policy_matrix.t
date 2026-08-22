use strict;
use warnings;
use Test::More;

our $CALLS = 0;
our $STRINGIFIES = 0;

sub match_eval {
    use re 'eval';
    my ($pattern, $subject, $extended) = @_;
    return $extended ? ($subject =~ /$pattern/x ? 1 : 0)
                     : ($subject =~ /$pattern/  ? 1 : 0);
}

sub match_noeval {
    no re 'eval';
    my ($pattern, $subject, $extended) = @_;
    return $extended ? ($subject =~ /$pattern/x ? 1 : 0)
                     : ($subject =~ /$pattern/  ? 1 : 0);
}

sub subst_eval {
    use re 'eval';
    my ($pattern, $subject, $extended) = @_;
    my $count = $extended ? ($subject =~ s/$pattern/Z/x)
                          : ($subject =~ s/$pattern/Z/);
    return "$count:$subject";
}

sub subst_noeval {
    no re 'eval';
    my ($pattern, $subject, $extended) = @_;
    my $count = $extended ? ($subject =~ s/$pattern/Z/x)
                          : ($subject =~ s/$pattern/Z/);
    return "$count:$subject";
}

sub capture {
    my ($code) = @_;
    local $@;
    my $value = eval { $code->() };
    return ($value, $@);
}

sub succeeds_without_callback {
    my ($name, $code, $expected) = @_;
    $CALLS = 0;
    my ($value, $error) = capture($code);
    is($error, '', "$name has no error");
    is($value, $expected, "$name result");
    is($CALLS, 0, "$name keeps comment/class callback inert");
}

sub succeeds_with_callback {
    my ($name, $code, $expected) = @_;
    $CALLS = 0;
    my ($value, $error) = capture($code);
    is($error, '', "$name has no error");
    is($value, $expected, "$name result");
    is($CALLS, 1, "$name executes callback once");
}

sub rejected_without_callback {
    my ($name, $code, $diagnostic) = @_;
    $CALLS = 0;
    my (undef, $error) = capture($code);
    like($error, $diagnostic, "$name diagnostic");
    is($CALLS, 0, "$name does not execute callback");
}

sub succeeds_overloaded_callback {
    my ($name, $code, $expected) = @_;
    $CALLS = 0;
    $STRINGIFIES = 0;
    my ($value, $error) = capture($code);
    is($error, '', "$name has no error");
    is($value, $expected, "$name result");
    is($CALLS, 1, "$name executes callback once");
    is($STRINGIFIES, 1, "$name stringifies the pattern object once");
}

my $inline_comment = "(?x)# (?{ ++\$main::CALLS })\na";
for my $case (
    [ 'inline x match eval',    sub { match_eval($inline_comment, 'a', 0) },    1 ],
    [ 'inline x match noeval',  sub { match_noeval($inline_comment, 'a', 0) },  1 ],
    [ 'inline x subst eval',    sub { subst_eval($inline_comment, 'a', 0) },    '1:Z' ],
    [ 'inline x subst noeval',  sub { subst_noeval($inline_comment, 'a', 0) },  '1:Z' ],
) {
    succeeds_without_callback(@$case);
}

my $scoped_comment = "(?x:# (?{ ++\$main::CALLS })\na)";
for my $case (
    [ 'scoped x match eval',    sub { match_eval($scoped_comment, 'a', 0) },    1 ],
    [ 'scoped x match noeval',  sub { match_noeval($scoped_comment, 'a', 0) },  1 ],
    [ 'scoped x subst eval',    sub { subst_eval($scoped_comment, 'a', 0) },    '1:Z' ],
    [ 'scoped x subst noeval',  sub { subst_noeval($scoped_comment, 'a', 0) },  '1:Z' ],
) {
    succeeds_without_callback(@$case);
}

my $nested_active = '(?x:(?-x:#(?{ ++$main::CALLS })a))';
succeeds_with_callback('nested -x match eval',
    sub { match_eval($nested_active, '#a', 0) }, 1);
succeeds_with_callback('nested -x subst eval',
    sub { subst_eval($nested_active, '#a', 0) }, '1:Z');
rejected_without_callback('nested -x match noeval',
    sub { match_noeval($nested_active, '#a', 0) }, qr/Eval-group not allowed/);
rejected_without_callback('nested -x subst noeval',
    sub { subst_noeval($nested_active, '#a', 0) }, qr/Eval-group not allowed/);

# Perl's first interpolation pass under an operation-level /x does not admit
# executable source that becomes visible only after an inline (?-x).  The
# admitted retry therefore reports the eval-group policy error even when the
# outer lexical scope has use re 'eval'.
my $outer_x_disabled = '(?-x:#(?{ ++$main::CALLS })a)';
for my $case (
    [ 'outer x disabled match eval',
        sub { match_eval($outer_x_disabled, '#a', 1) } ],
    [ 'outer x disabled match noeval',
        sub { match_noeval($outer_x_disabled, '#a', 1) } ],
    [ 'outer x disabled subst eval',
        sub { subst_eval($outer_x_disabled, '#a', 1) } ],
    [ 'outer x disabled subst noeval',
        sub { subst_noeval($outer_x_disabled, '#a', 1) } ],
) {
    rejected_without_callback($case->[0], $case->[1],
        qr/Eval-group not allowed/);
}

my $outer_x_disabled_without_hash = '(?-x:(?{ ++$main::CALLS })a)';
succeeds_with_callback('outer x disabled no-hash match eval',
    sub { match_eval($outer_x_disabled_without_hash, 'a', 1) }, 1);
succeeds_with_callback('outer x disabled no-hash subst eval',
    sub { subst_eval($outer_x_disabled_without_hash, 'a', 1) }, '1:Z');
rejected_without_callback('outer x disabled no-hash match noeval',
    sub { match_noeval($outer_x_disabled_without_hash, 'a', 1) },
    qr/Eval-group not allowed/);
rejected_without_callback('outer x disabled no-hash subst noeval',
    sub { subst_noeval($outer_x_disabled_without_hash, 'a', 1) },
    qr/Eval-group not allowed/);

my $nested_restored = "(?x:(?-x:#a)(?x:# (?{ ++\$main::CALLS })\nb))";
for my $case (
    [ 'nested restore match eval',   sub { match_eval($nested_restored, '#ab', 0) },   1 ],
    [ 'nested restore match noeval', sub { match_noeval($nested_restored, '#ab', 0) }, 1 ],
    [ 'nested restore subst eval',   sub { subst_eval($nested_restored, '#ab', 0) },   '1:Z' ],
    [ 'nested restore subst noeval', sub { subst_noeval($nested_restored, '#ab', 0) }, '1:Z' ],
) {
    succeeds_without_callback(@$case);
}

my $closed_class = '(?x:[(?{])a';
for my $case (
    [ 'closed class match eval',   sub { match_eval($closed_class, '(a', 0) },   1 ],
    [ 'closed class match noeval', sub { match_noeval($closed_class, '(a', 0) }, 1 ],
    [ 'closed class subst eval',   sub { subst_eval($closed_class, '(a', 0) },   '1:Z' ],
    [ 'closed class subst noeval', sub { subst_noeval($closed_class, '(a', 0) }, '1:Z' ],
) {
    succeeds_without_callback(@$case);
}

my $open_class = '(?x:[(?{';
for my $case (
    [ 'open class match eval',   sub { match_eval($open_class, '(', 0) } ],
    [ 'open class match noeval', sub { match_noeval($open_class, '(', 0) } ],
    [ 'open class subst eval',   sub { subst_eval($open_class, '(', 0) } ],
    [ 'open class subst noeval', sub { subst_noeval($open_class, '(', 0) } ],
) {
    rejected_without_callback($case->[0], $case->[1], qr/Unmatched \[/);
}

my $escaped_hash = '\\#(?{ ++$main::CALLS })a';
for my $case (
    [ 'escaped hash match eval',   sub { match_eval($escaped_hash, '#a', 1) } ],
    [ 'escaped hash match noeval', sub { match_noeval($escaped_hash, '#a', 1) } ],
    [ 'escaped hash subst eval',   sub { subst_eval($escaped_hash, '#a', 1) } ],
    [ 'escaped hash subst noeval', sub { subst_noeval($escaped_hash, '#a', 1) } ],
) {
    rejected_without_callback($case->[0], $case->[1], qr/Eval-group not allowed/);
}

{
    package A147::ExecutableString;
    use overload '""' => sub {
        ++$main::STRINGIFIES;
        return '(?{ ++$main::CALLS })a';
    }, fallback => 1;
}

{
    package A147::PlainString;
    use overload '""' => sub { 'a+' }, fallback => 1;
}

my $executable_object = bless [], 'A147::ExecutableString';
for my $case (
    [ 'overloaded executable match eval',
        sub { match_eval($executable_object, 'a', 0) }, 1 ],
    [ 'overloaded executable subst eval',
        sub { subst_eval($executable_object, 'a', 0) }, '1:Z' ],
) {
    succeeds_overloaded_callback(@$case);
}
rejected_without_callback('overloaded executable match noeval',
    sub { match_noeval($executable_object, 'a', 0) },
    qr/Eval-group not allowed/);
rejected_without_callback('overloaded executable subst noeval',
    sub { subst_noeval($executable_object, 'a', 0) },
    qr/Eval-group not allowed/);

{
    use re 'eval';
    $CALLS = 0;
    $STRINGIFIES = 0;
    my $subject = 'AaB';
    my ($matched, $match_error) = capture(sub { $subject =~ /A${executable_object}B/ ? 1 : 0 });
    is($match_error, '', 'embedded overloaded match has no error');
    is($matched, 1, 'embedded overloaded match preserves source provenance');
    is($CALLS, 1, 'embedded overloaded match executes callback once');
    is($STRINGIFIES, 1, 'embedded overloaded match stringifies once');

    $CALLS = 0;
    $STRINGIFIES = 0;
    $subject = 'AaB';
    my ($count, $subst_error) = capture(sub { $subject =~ s/A$executable_object B/Z/x });
    is($subst_error, '', 'embedded overloaded substitution has no error');
    is($count, 1, 'embedded overloaded substitution reports one replacement');
    is($subject, 'Z', 'embedded overloaded substitution preserves both literal sides');
    is($CALLS, 1, 'embedded overloaded substitution executes callback once');
    is($STRINGIFIES, 1, 'embedded overloaded substitution stringifies once');
}

my $plain_object = bless [], 'A147::PlainString';
for my $case (
    [ 'overloaded plain match eval',
        sub { match_eval($plain_object, 'aaa', 0) }, 1 ],
    [ 'overloaded plain match noeval',
        sub { match_noeval($plain_object, 'aaa', 0) }, 1 ],
    [ 'overloaded plain subst eval',
        sub { subst_eval($plain_object, 'aaa', 0) }, '1:Z' ],
    [ 'overloaded plain subst noeval',
        sub { subst_noeval($plain_object, 'aaa', 0) }, '1:Z' ],
) {
    succeeds_without_callback(@$case);
}

{
    use re 'eval';
    $CALLS = 0;
    my (undef, $error) = capture(sub { match_eval($open_class, '(', 0) });
    like($error, qr/at \(eval \d+\) line 1/,
        'admitted malformed runtime source reports synthetic eval provenance');
}

{
    no re 'eval';
    $CALLS = 0;
    my (undef, $error) = capture(sub { match_noeval($open_class, '(', 0) });
    like($error, qr/at \Q@{[__FILE__]}\E line \d+/,
        'non-admitted malformed source retains outer operator provenance');
}

done_testing;
