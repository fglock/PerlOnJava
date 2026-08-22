use strict;
use warnings;
use Test::More tests => 9;

{
    my $number = 123;
    $number =~ /\d/;
    my @matches;
    for (0 .. 1) {
        my $matched = m?? + 0;
        push @matches, $matched;
    }
    is_deeply(\@matches, [1, 0],
              'empty m?? retains match-once state while reusing the last pattern');

    $number =~ /(\d)/;
    my $digits = join '' => $number =~ //g;
    is($digits, $number,
       'ordinary empty //g reuse does not inherit the match-once flag');
}

{
    local $_;
    my @attempts;
    for ('y', 'x', 'x') {
        my $matched = m?x? + 0;
        push @attempts, $matched;
    }
    is_deeply(\@attempts, [0, 1, 0],
              'a failed m?PAT? attempt does not consume its one successful match');
}

{
    local $_ = 'x';
    ok(m?x?, 'first match-once callsite succeeds');
    ok(m?x?, 'a separate match-once callsite has independent state');
}

{
    local $_ = 'xx';
    my @ordinary;
    for my $iteration (1 .. 2) {
        my $matched = m/x/ + 0;
        push @ordinary, $matched;
    }
    is_deeply(\@ordinary, [1, 1],
              'ordinary slash-delimited matches remain reusable');
}

{
    local $_;
    my @first;
    my @second;
    for ('x', 'x') {
        my $first_match = m?x? + 0;
        my $second_match = m?x? + 0;
        push @first, $first_match;
        push @second, $second_match;
    }
    is_deeply(\@first, [1, 0], 'first loop callsite is match-once');
    is_deeply(\@second, [1, 0], 'second loop callsite is independently match-once');
    is(scalar(@first) + scalar(@second), 4,
       'match-once controls execute the complete sequence');
}
