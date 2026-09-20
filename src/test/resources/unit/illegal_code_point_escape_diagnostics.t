use strict;
use warnings;
use Config;
use Test::More;

my $hex = $Config{uvsize} < 8 ? '8000_0000' : '8000_0000_0000_0000';
my $octal = $Config{uvsize} < 8 ? '20_000_000_000' : '1_000_000_000_000_000_000_000';
my $value = $hex =~ s/_//gr;

for my $case (
    [ 'hexadecimal', 'my $x = "\\x{' . $hex . '}"' ],
    [ 'octal',       'my $x = "\\o{' . $octal . '}"' ],
) {
    my ($name, $source) = @$case;
    my $result = eval $source;

    ok(!defined $result, "$name escape above the signed-IV limit fails");
    like($@,
        qr{\AUse of code point 0x\Q$value\E is not allowed; the permissible max is 0x7FFFFFFFFFFFFFFF at \(eval \d+\) line 1\.\n?\z},
        "$name escape reports the signed-IV bound");
}

done_testing;
