use strict;
use warnings;
use Test::More;

{
    package Local::LanguageBFCallbackTable;

    sub step {
        my ($bf, $op) = @_;
        {
            '<' => sub { $bf->{sp} -= 1 },
            '>' => sub { $bf->{sp} += 1 },
            '+' => sub { $bf->{data}[ $bf->{sp} ]++ },
            '-' => sub { $bf->{data}[ $bf->{sp} ]-- },
            '.' => sub { push @{ $bf->{out} }, $bf->{data}[ $bf->{sp} ] },
            ',' => sub { $bf->{data}[ $bf->{sp} ] = shift @{ $bf->{in} } },
            '[' => sub {
                return if $bf->{data}[ $bf->{sp} ];
                my $nest = 1;
                while ($nest) {
                    $bf->{pc} += 1;
                    $nest +=
                        $bf->{code}[ $bf->{pc} ] eq '[' ? +1
                      : $bf->{code}[ $bf->{pc} ] eq ']' ? -1
                      : 0;
                    die "matching ] not found!"
                        if $bf->{pc} > scalar @{ $bf->{code} };
                }
            },
            ']' => sub {
                my $nest = 1;
                while ($nest) {
                    $bf->{pc} -= 1;
                    $nest -=
                        $bf->{code}[ $bf->{pc} ] eq '[' ? +1
                      : $bf->{code}[ $bf->{pc} ] eq ']' ? -1
                      : 0;
                    die "matching [ not found!" if $bf->{pc} < 0;
                }
                $bf->{pc}--;
            },
        }->{$op}();
        $bf->{pc}++;
    }
}

my $bf = { sp => 0, data => [0], out => [], in => [], pc => 0, code => [] };
Local::LanguageBFCallbackTable::step($bf, '>');
is($bf->{sp}, 1, 'callback table invokes the selected pointer callback');

$bf->{sp} = 0;
Local::LanguageBFCallbackTable::step($bf, '+');
is($bf->{data}[0], 1, 'callback table callback mutates its captured VM state');

$bf->{data}[0] = 0;
$bf->{pc} = 0;
$bf->{code} = ['[', '+', '[', '-', ']', ']'];
Local::LanguageBFCallbackTable::step($bf, '[');
is($bf->{pc}, 6, 'nested callback body evaluates its multi-line ternary loop scan');

done_testing;
