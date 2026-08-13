use strict;
use warnings;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - $name\n");
}

for my $case (
    [ 1,  undef, 'XXfrompswiggle',  'positive offset to end' ],
    [ 1,  -1,    'XXffrompswiggl', 'positive offset to negative end' ],
    [ -5, undef, 'le',             'negative offset to end' ],
    [ -5, -1,    'gl',             'negative offset to negative end' ],
) {
    my ($offset, $length, $expected, $name) = @$case;
    my $string = 'abcdef';
    if (defined $length) {
        for (substr($string, $offset, $length)) {
            $_ = 'XX';
            $string .= 'frompswiggle';
            check($_ eq $expected, $name);
        }
    } else {
        for (substr($string, $offset)) {
            $_ = 'XX';
            $string .= 'frompswiggle';
            check($_ eq $expected, $name);
        }
    }
}
