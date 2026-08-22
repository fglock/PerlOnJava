use strict;
use warnings;
use utf8;
use Test::More;

sub captures {
    my ($subject, $re, $count) = @_;
    return '<no match>' unless $subject =~ /(?:)$re(?:)/;
    return join '-', map { defined $_ ? $_ : '<undef>' }
        ($1, $2, $3)[0 .. $count - 1];
}

sub matches {
    my ($subject, $re) = @_;
    return $subject =~ /(?:)$re(?:)/;
}

is captures('AB', qr/^(a)((??{'b'}))$/i, 2), 'A-B',
    'embedded qr dynamic source inherits /i and preserves captures';
is captures("AB\nC", qr/^(A)((??{'B$'}))(\nC)$/m, 3), "A-B-\nC",
    'embedded qr dynamic source inherits /m and preserves captures';
is captures("A\nB", qr/^(A)((??{'.'}))(B)$/s, 3), "A-\n-B",
    'embedded qr dynamic source inherits /s and preserves captures';
is captures('A B', qr/^(A) ((??{' .'}))(B)$/x, 3), 'A- -B',
    'embedded qr dynamic source inherits /x and preserves captures';

my $arabic_indic_zero = "\x{660}";
my $ascii_digit = qr/^((??{'\d'}))$/a;
my $default_digit = qr/^((??{'\d'}))$/;
ok(!matches($arabic_indic_zero, $ascii_digit),
    'embedded qr dynamic class inherits /a');
ok(matches($arabic_indic_zero, $default_digit),
    'default dynamic class remains Unicode-capable');

my $long_s = "\x{17f}";
my $fold = qr/^(??{'s'})$/i;
my $ascii_fold = qr/^(??{'s'})$/ia;
my $strict_ascii_fold = qr/^(??{'s'})$/iaa;
ok(matches($long_s, $fold),
    'embedded qr dynamic exact inherits /i folding');
ok(matches($long_s, $ascii_fold),
    'embedded qr dynamic exact inherits /ia folding');
ok(!matches($long_s, $strict_ascii_fold),
    'embedded qr dynamic exact inherits /iaa fold restriction');

done_testing;
