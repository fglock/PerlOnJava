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
