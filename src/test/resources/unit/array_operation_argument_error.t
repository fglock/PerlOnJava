use Test::More;

for my $case (
    ['push %a, 1', 'push', 'hash dereference'],
    ['pop %a', 'pop', 'hash dereference'],
    ['shift %a', 'shift', 'hash dereference'],
    ['unshift %a, 1', 'unshift', 'hash dereference'],
    ['push *a, 1', 'push', 'ref-to-glob cast'],
    ['pop *a', 'pop', 'ref-to-glob cast'],
    ['shift *a', 'shift', 'ref-to-glob cast'],
    ['unshift *a, 1', 'unshift', 'ref-to-glob cast'],
) {
    my $ok = eval "$case->[0]; 1";
    ok(!$ok, "$case->[0] is rejected");
    like($@, qr/Type of arg 1 to \Q$case->[1]\E must be array \(not \Q$case->[2]\E\)/,
        'reports the operand category');
}

for my $case (
    ['push %a, 1', 'push'],
    ['pop %a', 'pop'],
    ['shift %a', 'shift'],
    ['unshift %a, 1', 'unshift'],
) {
    my $ok = eval "my %a; $case->[0]; 1";
    ok(!$ok, "lexical $case->[0] is rejected");
    like($@, qr/Type of arg 1 to \Q$case->[1]\E must be array \(not private hash\)/,
        'reports the private hash category');
}

my $multiple = eval q{
    push %a, 1;
    pop %a;
    shift %a;
    unshift %a, 1;
    push *a, 1;
    pop *a;
    shift *a;
    unshift *a, 1;
    1;
};
ok(!$multiple, 'multiple invalid array operations are rejected together');
is(scalar(() = $@ =~ /Type of arg 1 to (?:push|pop|shift|unshift) must be array/g), 8,
    'reports every invalid array operation in one compilation');

done_testing;
