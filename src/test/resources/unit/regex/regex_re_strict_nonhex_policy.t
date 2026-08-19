use strict;
use warnings;
use Test::More;

sub capture_eval_string {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $value = eval $source;
    return ($value, $@, \@warnings);
}

sub compile_default {
    my ($pattern) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $value = eval { qr/$pattern/ };
    return ($value, $@, \@warnings);
}

{
    no warnings 'experimental::re_strict';
    use re 'strict';

    sub compile_strict {
        my ($pattern) = @_;
        my @warnings;
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        my $value = eval { qr/$pattern/ };
        return ($value, $@, \@warnings);
    }
}

my @cases = (
    ['\\xAG',            'm/\\xAG <-- HERE /'],
    ['[\\xAG]',          'm/[\\xAG <-- HERE ]/'],
    ['\\x{ABCDEFG}',     'm/\\x{ABCDEFG <-- HERE }/'],
    ['[\\x{ABCDEFG}]',   'm/[\\x{ABCDEFG <-- HERE }]/'],
    ['\\x{ 5 0 }',       'm/\\x{ 5  <-- HERE 0 }/'],
);

for my $case (@cases) {
    my ($pattern, $marked_pattern) = @$case;
    my ($value, $error, $warnings) = compile_default($pattern);
    ok(defined($value) && $error eq '', "non-hex escape passes by default: $pattern");
    is(scalar(@$warnings), 1, "default non-hex escape warns exactly once: $pattern");
    like($warnings->[0] // '', qr/^Non-hex character '.+' terminates \\x early\.  Resolved as /,
        "default non-hex warning retained: $pattern");

    ($value, $error, $warnings) = compile_strict($pattern);
    ok(!defined($value), "non-hex escape is fatal under lexical strict: $pattern");
    like($error, qr/^Non-hex character in regex; marked by <-- HERE in \Q$marked_pattern\E at /,
        "strict non-hex marker: $pattern");
    is(scalar(@$warnings), 0, "strict non-hex fatal emits no warning: $pattern");
}

my ($literal, $literal_error, $literal_warnings) = capture_eval_string(q!qr/\xAG/!);
ok(defined($literal) && $literal_error eq '', 'literal unbraced non-hex escape compiles');
is(scalar(@$literal_warnings), 1, 'literal unbraced non-hex escape warns exactly once');
like($literal_warnings->[0], qr/^Non-hex character 'G' terminates \\x early/,
    'literal unbraced non-hex warning keeps Perl text');

done_testing;
