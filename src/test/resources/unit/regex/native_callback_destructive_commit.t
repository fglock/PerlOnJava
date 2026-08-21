use strict;
use warnings;
use Test::More;

for my $verb (qw(FAIL PRUNE SKIP THEN COMMIT)) {
    my @events;
    my $pattern = qr/\A(?{ push @events, $verb })(*$verb)(*FAIL)\z/;
    ok('x' !~ $pattern, "$verb path fails");
    is_deeply(\@events, [$verb], "$verb commits callback mutation");
}

my @marked;
ok('x' !~ /\A(?{ push @marked, 'MARK' })(*MARK:plain)z\z/,
   'MARK path fails normally');
is_deeply(\@marked, [], 'MARK does not commit callback mutation');

done_testing;
