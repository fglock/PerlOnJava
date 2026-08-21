use strict;
use warnings;
use Test::More;

my @punctuation = (
    [' ',  96], ['!',  97], ['"',  98], ['#',  99], ['$', 100], ['%', 101],
    ['&', 102], ["'", 103], ['(', 104], [')', 105], ['*', 106], ['+', 107],
    [',', 108], ['-', 109], ['.', 110], ['/', 111], [':', 122], [';', 123],
    ['<', 124], ['=', 125], ['>', 126], ['?', 127], ['@',   0], ['[',  27],
    ['\\', 28], [']',  29], ['^',  30], ['_',  31], ['`',  32], ['|',  60],
    ['}',  61], ['~',  62],
);

sub control_eval {
    my ($operand) = @_;
    my $source;
    if ($operand eq '\\') {
        $source = join '', chr(34), chr(92), 'c', chr(92), chr(92), chr(34);
    }
    else {
        my @pairs = (['(', ')'], ['[', ']'], ['{', '}'], ['<', '>'],
                     ['!', '!'], ['~', '~']);
        my ($open, $close) = @{(grep {
            $operand ne $_->[0] && $operand ne $_->[1]
        } @pairs)[0]};
        $source = 'qq' . $open . '\\c' . $operand . $close;
    }

    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $value = eval $source;
    return ($value, $@, \@warnings);
}

for my $case (@punctuation) {
    my ($operand, $expected) = @$case;
    my ($value, $error) = control_eval($operand);
    is $error, '', sprintf('\\c operand 0x%02X compiles', ord($operand));
    is ord($value), $expected,
            sprintf('\\c operand 0x%02X maps to %d', ord($operand), $expected);
}

my (undef, $brace_error) = control_eval('{');
like $brace_error, qr/Use ";" instead of "\\c\{"/,
        '\\c left brace retains its dedicated diagnostic';

{
    no warnings qw(syntax regexp experimental::regex_sets);
    like "\c#", qr/(?[\c#])/, 'regex_sets.t row 65 subject matches itself';
}

done_testing;
