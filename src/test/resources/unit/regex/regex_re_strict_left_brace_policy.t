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

my ($value, $error, $warnings) = compile_default('\\w{');
ok(!defined($value), 'ambiguous brace after escape is fatal by default');
like($error, qr/^Unescaped left brace in regex is illegal here in regex; marked by <-- HERE in m\/\\w\{ <-- HERE \/ at /,
    'default fatal marker follows brace');
is(scalar(@$warnings), 0, 'default fatal emits no warning');

($value, $error, $warnings) = compile_default(':{4,a}');
ok(defined($value) && $error eq '', 'malformed quantifier-like brace passes by default');
like($warnings->[0] // '', qr/^Unescaped left brace in regex is passed through in regex; marked by <-- HERE in m\/:\{ <-- HERE 4,a\}\/ at /,
    'default warning marker follows brace');

($value, $error, $warnings) = compile_strict(':{4,a}');
ok(!defined($value), 'malformed quantifier-like brace is fatal under lexical strict');
like($error, qr/^Unescaped left brace in regex is illegal here in regex; marked by <-- HERE in m\/:\{ <-- HERE 4,a\}\/ at /,
    'strict fatal marker follows brace');
is(scalar(@$warnings), 0, 'strict fatal emits no warning');

($value, $error, $warnings) = capture_eval_string(q{qr/:{4,a}/});
ok(defined($value) && $error eq '' && @$warnings == 1,
    'literal malformed brace warns outside strict');

($value, $error, $warnings) = capture_eval_string(
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/:{4,a}/});
ok(!defined($value) && $error =~ /^Unescaped left brace in regex is illegal here/,
    'literal malformed brace is fatal inside strict');

for my $pattern ('^{', 'foo|{', '\\s*{', 'a{3,4}{', 'foo(:?{bar)') {
    ($value, $error, $warnings) = compile_strict($pattern);
    ok(defined($value) && $error eq '' && @$warnings == 0,
        "allowed brace context remains quiet: $pattern");
}

done_testing;
