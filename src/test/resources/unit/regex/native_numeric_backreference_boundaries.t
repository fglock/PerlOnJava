use strict;
use warnings;
use Test::More;

for my $pattern (q{\87}, q{a\87}, q{a\97}) {
    my $compiled = eval "qr/$pattern/";
    like($@, qr/Reference to nonexistent group in regex/,
         "$pattern is a nonexistent decimal backreference");
}

for my $digits (qw(2147483648 2147483649 2147483650
                   4294967296 4294967297 4294967298)) {
    for my $prefix ('', 'a') {
        for my $form ("\\g$digits}", "\\g{$digits}", "\\g{ $digits }") {
            my $compiled = eval "qr/${prefix}(.)$form/";
            like($@, qr/Reference to nonexistent group in regex/,
                 "$form rejects overflow safely after ${prefix}capture");
        }
    }

    my ($octal, $tail) = $digits =~ /^([0-7]{1,3})(.*)$/;
    for my $prefix ('', 'a') {
        my $pattern = "${prefix}(.)\\$digits";
        my $compiled = eval "qr/$pattern/";
        ok(defined($compiled), "$pattern compiles as octal plus literal tail");
        my $subject = $prefix . 'b' . chr(oct($octal)) . $tail;
        ok($subject =~ $compiled && $1 eq 'b',
           "$pattern matches its octal boundary without integer overflow");
    }
}

done_testing;
