use strict;
use warnings;
use re 'eval';
use Test::More tests => 12;

{
    local $_ = 'ab';
    our (@count, @start, @end, @bounds);
    @count = @start = @end = @bounds = ();

    my $matched = /(.){1,}(?{
        push @count, 0 + @-;
        push @start, join ',', map { defined $_ ? $_ : 'U' } @-;
        push @end, join ',', map { defined $_ ? $_ : 'U' } @+;
        push @bounds, join ':', $-[0], $+[0], $-[1], $+[1];
    })(.){1,}(?{})^/;

    ok(!$matched, 'the backtracking pattern ultimately fails');
    is_deeply(\@count, [2, 2, 2],
              '@- has no undefined array slots during failed-match callbacks');
    is_deeply(\@start, ['0,1', '0,0', '1,1'],
              '@- publishes every provisional backtracking capture');
    is_deeply(\@end, ['2,2,U', '1,1,U', '2,2,U'],
              '@+ publishes provisional and unmatched captures');
    is_deeply(\@bounds, ['0:2:1:2', '0:1:0:1', '1:2:1:2'],
              'indexed offset arrays agree with their provisional array views');
}

{
    local $_ = 'ab';
    our (@successful_count, @successful_bounds);
    @successful_count = @successful_bounds = ();

    my $matched = /(.){1,}(?{
        push @successful_count, 0 + @-;
        push @successful_bounds, join ':', $-[0], $+[0], $-[1], $+[1];
    })$/;

    ok($matched, 'the successful callback control matches');
    ok(@successful_count >= 1, 'the successful control executes its callback');
    ok(!(grep { $_ != 2 } @successful_count),
       'the successful control also exposes two defined @- slots');
    is(scalar @successful_bounds, scalar @successful_count,
       'each successful callback publishes one complete offset snapshot');
    like($successful_bounds[-1], qr/^0:2:/,
         'the accepted callback state covers the complete match');
}

{
    'seed' =~ /(ee)/;
    my @before_start = @-;
    my @before_end = @+;
    local $_ = 'ab';
    /z(?{ die 'unreachable callback' })/;
    is_deeply([@-], \@before_start,
              'a callback-free failed match preserves the preceding @-');
    is_deeply([@+], \@before_end,
              'a callback-free failed match preserves the preceding @+');
}
