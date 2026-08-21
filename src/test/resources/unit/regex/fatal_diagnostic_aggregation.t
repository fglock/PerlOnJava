use strict;
use warnings;
use Test::More;

sub compile_probe {
    my ($source, $handler_dies) = @_;
    my @warnings;
    local $SIG{__WARN__} = $handler_dies
        ? sub { die "handler died: $_[0]" }
        : sub { push @warnings, @_ };
    my $value = eval $source;
    return ($value, $@, join('', @warnings));
}

my ($value, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'all'; /() {}/X; 1});
ok(!defined $value, 'fatal warning plus invalid modifier rejects eval');
like($error,
    qr{\AUnknown regexp modifier "/X" at \(eval \d+\) line 1, at end of line\nUnescaped left brace in regex is passed through in regex; marked by <-- HERE in m/\(\) \{ <-- HERE \}/ at \(eval \d+\) line 1\.\n\z},
    'fatal warning is aggregated after the modifier error');
is($warning, '', 'aggregated fatal warning bypasses warning handler');

($value, $error, $warning) = compile_probe(
    q{use warnings; /() {}/X; 1});
ok(!defined $value, 'nonfatal warning plus invalid modifier rejects eval');
like($error,
    qr{\AUnknown regexp modifier "/X" at \(eval \d+\) line 1, at end of line\n\z},
    'nonfatal case retains only the modifier in eval error');
like($warning,
    qr{\AUnescaped left brace in regex is passed through in regex; marked by <-- HERE in m/\(\) \{ <-- HERE \}/ at \(eval \d+\) line 1\.\n\z},
    'nonfatal pending diagnostic reaches warning handler');

($value, $error, $warning) = compile_probe(
    q{no warnings; /() {}/X; 1});
like($error,
    qr{\AUnknown regexp modifier "/X" at \(eval \d+\) line 1, at end of line\n\z},
    'disabled warning leaves the modifier error intact');
is($warning, '', 'disabled pending diagnostic stays suppressed');

($value, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'regexp'; /[\8]/X; 1});
like($error,
    qr{\AUnknown regexp modifier "/X".*\nUnrecognized escape \\8 in character class passed through in regex; marked by <-- HERE in m/\[\\8 <-- HERE \]/.*\n\z}s,
    'fatal native warning aggregates after parser modifier error');
is($warning, '', 'fatal escape diagnostic is not dispatched as warning');

($value, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'regexp'; /() {} [\8]/X; 1});
like($error,
    qr{\AUnknown regexp modifier "/X".*\nUnescaped left brace.*\nUnrecognized escape \\8.*\n\z}s,
    'multiple fatal diagnostics retain source order');
is($warning, '', 'multiple fatal diagnostics bypass warning handler');

($value, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'all'; /[z-a]/X; 1});
like($error, qr{\AInvalid \[\] range "z-a" in regex},
    'truly fatal native syntax remains the primary stop');
unlike($error, qr{Unknown regexp modifier},
    'modifier is not appended after unrecoverable native syntax');
is($warning, '', 'native syntax failure dispatches no warning');

($value, $error, $warning) = compile_probe(
    q{use warnings; /() {}/X; 1}, 1);
like($error, qr{\Ahandler died: Unescaped left brace.* line 1\.\n\z}s,
    'dying warning handler replaces pending modifier error');
unlike($error, qr{Unknown regexp modifier},
    'replaced error contains no stale modifier diagnostic');
is($warning, '', 'dying warning handler leaves no captured warning');

($value, $error, $warning) = compile_probe(
    q{use warnings FATAL => 'regexp'; $_ = ''; /() {}/; 1});
like($error,
    qr{\AUnescaped left brace.* at \(eval \d+\) line 1\.\n\z}s,
    'standalone fatal regex warning retains eval location');
is($warning, '', 'standalone fatal warning bypasses warning handler');

done_testing;
