my @cases = (
    [
        q{sub f ($) { } f $x /2;},
        'adjacent slash after a unary prototype argument',
    ],
    [
        q{sub { print $fh /2 }},
        'adjacent slash after a print filehandle',
    ],
);

print '1..', scalar(@cases) * 2, "\n";
my $test = 0;
for my $case (@cases) {
    my $ok = eval $case->[0];
    my $error = $@;
    ++$test;
    print((!$ok ? 'ok' : 'not ok'), " $test - $case->[1] fails compilation\n");
    ++$test;
    print(($error =~ /^Search pattern not terminated/ ? 'ok' : 'not ok'),
        " $test - $case->[1] reports unterminated regex\n");
}
