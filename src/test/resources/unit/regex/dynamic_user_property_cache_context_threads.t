use strict;
use warnings;
use v5.16;
use utf8;
use charnames ':full';
use threads;
use Test::More;

our $forward;
BEGIN { $forward = qr/\p{InThreadCacheKana}/ }
sub InThreadCacheKana { "3040\t309f\n30a0\t30ff\n" }

my $result = threads->create(sub {
    my $initial_member = "\x{3040}" =~ $forward ? 1 : 0;
    my $fresh = eval q{qr/\p{InThreadCacheKana}/};
    return [
        $initial_member,
        $@,
        "\x{3040}" =~ $fresh ? 1 : 0,
        "\x{303f}" =~ $fresh ? 1 : 0,
    ];
})->join;

is_deeply(
    $result,
    [1, '', 1, 0],
    'thread clone preserves deferred property compilation context and cache safety',
);

done_testing;
