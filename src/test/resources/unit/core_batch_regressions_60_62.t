use strict;
use warnings;
use Test::More;

our @sparse_reverse;
$sparse_reverse[3] = 1;
my $value = 2;
for my $element (reverse @sparse_reverse) {
    $element = $value++;
}
is_deeply(\@sparse_reverse, [5, 4, 3, 2],
    'foreach over reverse aliases sparse source slots');

my @reverse_assignment = (1, 2, 3, 4);
delete $reverse_assignment[1];
@reverse_assignment = reverse @reverse_assignment;
ok(!exists $reverse_assignment[2],
    'assignment from reverse preserves a deleted array slot');

{
    require Tie::Array;
    tie my @tied_reverse, 'Tie::StdArray';
    @tied_reverse = (1, 2, 3, 4);
    delete $tied_reverse[1];
    @tied_reverse = reverse @tied_reverse;
    ok(!exists $tied_reverse[2],
        'assignment from reverse preserves a deleted tied array slot');
    is(join('', @tied_reverse[0, 1, 3]), '431',
        'reverse assignment retains defined tied array values');
}

{
    no warnings 'deprecated';
    my $iteration = 0;
    'abc' =~ /b/;
    LOOP: while (1) {
        ++$iteration;
        is($` . $& . $', 'abc',
            "while loop control restores regex captures at iteration $iteration");
        {
            'end' =~ /end/;
            redo LOOP if $iteration == 1;
            next LOOP if $iteration == 2;
            last LOOP if $iteration == 3;
        }
    }
}

{
    use feature 'unicode_strings';
    is(quotemeta("\x{df}"), "\x{df}",
        'quotemeta applies Unicode word rules under unicode_strings');
}

srand(1);
is(int rand(1000), 41, 'srand uses Perl-compatible first drand48 value');
is(int rand(1000), 454, 'srand uses Perl-compatible second drand48 value');

is do {{ &{sub { 'discarded' }}, last }}, undef,
    'last leaves no value in a surrounding block expression';

done_testing;
