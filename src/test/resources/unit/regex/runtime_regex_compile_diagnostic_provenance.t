use strict;
use warnings;
use Test::More tests => 53;

sub eval_match_error {
    use re 'eval';
    my ($pattern) = @_;
    local $@;
    local $SIG{__WARN__} = sub { };
    eval { '(' =~ /$pattern/ };
    return $@;
}

sub eval_substitution_error {
    use re 'eval';
    my ($pattern) = @_;
    my $target = '(';
    local $@;
    local $SIG{__WARN__} = sub { };
    eval { $target =~ s/$pattern/Z/ };
    return $@;
}

sub noeval_match_error {
    no re 'eval';
    my ($pattern) = @_;
    local $@;
    local $SIG{__WARN__} = sub { };
    eval { '(' =~ /$pattern/ };
    return $@;
}

sub noeval_substitution_error {
    no re 'eval';
    my ($pattern) = @_;
    my $target = '(';
    local $@;
    local $SIG{__WARN__} = sub { };
    eval { $target =~ s/$pattern/Z/ };
    return $@;
}

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

my $source = "BEGIN{\$^H=0x200000}\ns/[(?{//xx";
my $result = eval $source;
my $error = $@;

ok(!defined($result), 'runtime executable regex source fails compilation');
is(scalar(@warnings), 0, 'compile diagnostic is fatal rather than a warning');
like($error, qr/^Unmatched \[ in regex;/,
     'fatal category retains the unmatched character-class diagnostic');
like($error, qr/marked by <-- HERE in m\/\[ <-- HERE \(\?\{\//,
     'diagnostic marker points after the unmatched opening bracket');
like($error, qr/ at \(eval \d+\) line 1\.\n\z/,
     'runtime regex compiler owns an eval source beginning at line one');
unlike($error, qr/ line 2\./,
       'outer regex operator line does not replace runtime-source line one');
unlike($error, qr/ at - line /,
       'runtime-source failure does not fall back to the fresh program file');

@warnings = ();
eval "BEGIN{\$^H=0x200000}\ns/[//";
like($@, qr/ at \(eval \d+\) line 2\.\n\z/,
     'ordinary malformed regex retains its outer operator line');

@warnings = ();
my $closed = eval "BEGIN{\$^H=0x200000}\nqr/[(?{]/xx";
ok(defined($closed),
   'executable-looking text in a closed character class stays literal');
is($@, '', 'closed character class has no fatal diagnostic');
is(scalar(@warnings), 0, 'closed character class has no warning diagnostic');

@warnings = ();
eval "BEGIN{\$^H=0x200000}\ns/[(?{[:alpha:]//xx";
my $posix_error = $@;
like($posix_error,
     qr/marked by <-- HERE in m\/\[ <-- HERE \(\?\{\[:alpha:\]\//,
     'nested POSIX term does not hide the unmatched outer class marker');
like($posix_error, qr/ at \(eval \d+\) line 1\.\n\z/,
     'nested POSIX term retains runtime-regex source provenance');
is(scalar(@warnings), 0,
   'nested POSIX boundary failure remains fatal rather than a warning');

@warnings = ();
my $closed_posix = eval "BEGIN{\$^H=0x200000}\nqr/[(?{[:alpha:]]/xx";
ok(defined($closed_posix),
   'closed outer class with a nested POSIX term stays literal');
is($@, '', 'closed nested POSIX class has no fatal diagnostic');
is(scalar(@warnings), 0, 'closed nested POSIX class has no warning diagnostic');

for my $case (
    ['code',                '(?{',   1],
    ['dynamic',             '(??{',  1],
    ['postponed',           '(*{',   0],
    ['condition code',      '(?(?{', 1],
    ['condition postponed', '(?(*{', 0],
) {
    my ($name, $candidate, $promoted) = @$case;
    for my $operation (
        ['match',        \&eval_match_error],
        ['substitution', \&eval_substitution_error],
    ) {
        my $error = $operation->[1]->("[$candidate");
        if ($promoted) {
            like($error, qr/ at \(eval \d+\) line 1\.\n\z/,
                 "$name unterminated class $operation->[0] uses runtime source");
        } else {
            like($error, qr/ at \Q$0\E line \d+\.\n\z/,
                 "$name unterminated class $operation->[0] keeps outer source");
        }
    }
}

for my $case (
    ['code',                '(?{'],
    ['dynamic',             '(??{'],
    ['postponed',           '(*{'],
    ['condition code',      '(?(?{'],
    ['condition postponed', '(?(*{'],
) {
    my ($name, $candidate) = @$case;
    is(noeval_match_error("[$candidate]"), '',
       "$name is literal in a closed dynamic match class");
    is(noeval_substitution_error("[$candidate]"), '',
       "$name is literal in a closed dynamic substitution class");
}

for my $operation (
    ['match',        \&eval_match_error],
    ['substitution', \&eval_substitution_error],
) {
    like($operation->[1]->('[(?{[:alpha:]'),
         qr/ at \(eval \d+\) line 1\.\n\z/,
         "nested POSIX term preserves unterminated class $operation->[0] source");
}

for my $operation (
    ['match',        \&eval_match_error],
    ['substitution', \&eval_substitution_error],
) {
    like($operation->[1]->('[[:alpha:](?{'),
         qr/ at \(eval \d+\) line 1/,
         "leading nested POSIX term preserves unterminated class $operation->[0] source");
}

for my $pattern ('[](?{]', '[^](?{]') {
    like(eval_match_error($pattern),
         qr/^Sequence \(\?\{\.\.\.\}\) not terminated with '\)' at \(eval \d+\) line 1\.\n\z/,
         "$pattern eval match terminates after one runtime-source compilation");
    like(eval_substitution_error($pattern),
         qr/^Sequence \(\?\{\.\.\.\}\) not terminated with '\)' at \(eval \d+\) line 1\.\n\z/,
         "$pattern eval substitution terminates after one runtime-source compilation");
    is(noeval_match_error($pattern), '',
       "$pattern no-eval match treats initial close bracket literally");
    is(noeval_substitution_error($pattern), '',
       "$pattern no-eval substitution treats initial close bracket literally");
}

for my $operation (
    ['match',        \&eval_match_error],
    ['substitution', \&eval_substitution_error],
) {
    like($operation->[1]->('(??{"x"})[](?{]'),
         qr/^Sequence \(\?\{\.\.\.\}\) not terminated with '\)' at \(eval \d+\) line 1\.\n\z/,
         "malformed class candidate owns mixed-pattern $operation->[0] diagnostic");
}

like(noeval_match_error('[(?{[.a.]]'),
     qr/^POSIX syntax \[\. \.\] is reserved for future extensions/,
     'collating term closes independently of its outer class');
like(noeval_match_error('[(?{[=a=]]'),
     qr/^POSIX syntax \[= =\] is reserved for future extensions/,
     'equivalence term closes independently of its outer class');
