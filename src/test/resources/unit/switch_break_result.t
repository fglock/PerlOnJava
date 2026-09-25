use v5.36;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my @results;
for my $value (0, 1) {
    push @results, scalar do {
        given ($value) {
            when (0) { { value => 0 } }
            when (1) { break }
        }
    };
}

is ref $results[0], 'HASH', 'given returns the matching when value';
ok !defined $results[1], 'break clears the given result instead of reusing a prior value';

done_testing;
