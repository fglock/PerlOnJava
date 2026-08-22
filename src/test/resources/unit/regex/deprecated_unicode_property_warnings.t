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
    return ($value, $@, \@warnings);
}

my ($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\p{Hyphen=no}/; 1});
is($value, 1, 'Hyphen value compiles');
is($error, '', 'Hyphen value has no compile error');
is(scalar @$warnings, 1, 'Hyphen value warns once');
like($warnings->[0],
    qr{\AUse of 'Hyphen=no'.*Supplanted by Line_Break property values.* at \(eval \d+\) line 1\.\n\z}s,
    'Hyphen warning preserves spelling, reason, and eval location');

($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\P{Is_Hyphen=  y}/; 1});
is(scalar @$warnings, 1, 'Is_Hyphen value warns once through P escape');
like($warnings->[0], qr{Use of 'Is_Hyphen=  y'},
    'Hyphen alias warning preserves whitespace spelling');

($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\p{Line_Break=surrogate}/; 1});
is($value, 1, 'Line_Break surrogate compiles');
is(scalar @$warnings, 1, 'Line_Break surrogate warns once');
like($warnings->[0],
    qr{\AUse of 'Line_Break=surrogate'.*Surrogates should never appear in well-formed text.* at \(eval \d+\) line 1\.\n\z}s,
    'Line_Break warning preserves spelling, reason, and eval location');

($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\p{Is_Lb=  SG}/; 1});
is(scalar @$warnings, 1, 'short Line_Break alias warns once');
like($warnings->[0], qr{Use of 'Is_Lb=  SG'},
    'short Line_Break alias preserves spelling');

($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\p{Hyphen=no}\P{Line_Break=surrogate}/; 1});
is(scalar @$warnings, 2, 'two deprecated properties warn twice');
like($warnings->[0], qr{Use of 'Hyphen=no'},
    'first warning retains source order');
like($warnings->[1], qr{Use of 'Line_Break=surrogate'},
    'second warning retains source order');

($value, $error, $warnings) = compile_probe(
    q{no warnings 'deprecated::unicode_property_name'; qr/\p{Hyphen=no}\p{Line_Break=surrogate}/; 1});
is($value, 1, 'suppressed deprecated properties compile');
is($error, '', 'suppressed deprecated properties have no error');
is(scalar @$warnings, 0, 'lexical suppression emits no warnings');

($value, $error, $warnings) = compile_probe(
    q{use warnings FATAL => 'deprecated::unicode_property_name'; qr/\p{Hyphen=no}/; 1});
ok(!defined $value, 'fatal deprecated property rejects eval');
like($error,
    qr{\AUse of 'Hyphen=no'.* at \(eval \d+\) line 1\.\n\z}s,
    'fatal warning becomes located eval error');
is(scalar @$warnings, 0, 'fatal property warning bypasses warning handler');

($value, $error, $warnings) = compile_probe(
    q{use warnings; qr/\p{Line_Break=surrogate}/; 1}, 1);
ok(!defined $value, 'dying warning handler rejects eval');
like($error,
    qr{\Ahandler died: Use of 'Line_Break=surrogate'.* line 1\.\n\z}s,
    'warning handler receives complete located property diagnostic');
is(scalar @$warnings, 0, 'dying handler leaves no captured warning');

done_testing;
